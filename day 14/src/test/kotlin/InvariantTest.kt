import java.util.Collections
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Фейковый клиент: для запросов контролёра инвариантов отдаёт заданный JSON, иначе — обычный текст.
 */
private class InvFakeClient(
    private val guardJson: String = "{\"violations\":[]}",
    private val reply: String = "ответ агента",
) : ChatClient {
    val systemPrompts: MutableList<String> = Collections.synchronizedList(mutableListOf())

    override fun chat(messages: List<ChatMessage>): ChatResult {
        val sys = messages.firstOrNull { it.role == "system" }?.content.orEmpty()
        systemPrompts.add(sys)
        val isGuard = sys.contains("контролёр инвариантов")
        val content = if (isGuard) guardJson else reply
        return ChatResult(content, 1, 1, 1, 2, false, 0.0)
    }
}

class InvariantTest {

    private val rxInvariant = Invariant(
        id = "stack-no-rxjava",
        category = "стек",
        rule = "RxJava запрещена.",
        forbid = listOf("rxjava", "io.reactivex"),
    )

    // ---------- Детерминированная проверка ----------

    @Test
    fun `deterministic check finds a forbidden token by word boundary`() {
        val set = InvariantSet(listOf(rxInvariant))
        assertTrue(set.deterministicViolations("Добавим RxJava для потоков").isNotEmpty())
        assertTrue(set.deterministicViolations("import io.reactivex.Flowable").isNotEmpty())
    }

    @Test
    fun `deterministic check does not over-trigger on substrings`() {
        // forbid "java" не должен срабатывать на "javascript".
        val set = InvariantSet(listOf(Invariant("no-java", "стек", "Без Java", forbid = listOf("java"))))
        assertTrue(set.deterministicViolations("пишем на javascript").isEmpty())
        assertTrue(set.deterministicViolations("берём Java 17").isNotEmpty())
    }

    @Test
    fun `render lists rules and forbidden tokens`() {
        val text = InvariantSet(listOf(rxInvariant)).render()
        assertTrue(text.contains("RxJava запрещена"))
        assertTrue(text.contains("rxjava"))
        assertTrue(text.contains("НЕЛЬЗЯ нарушать"))
    }

    // ---------- Хранилище (отдельно от диалога) ----------

    @Test
    fun `store seeds defaults then loads them back`() {
        val store = InvariantStore(createTempDirectory("day14-inv"))
        val seeded = store.loadOrSeed()
        assertEquals(InvariantStore.DEFAULTS.size, seeded.size)
        assertNotNull(store.load())
        assertEquals(seeded.map { it.id }, store.load()!!.map { it.id })
    }

    // ---------- Контролёр: код + LLM ----------

    @Test
    fun `checker combines deterministic and llm violations (deduped)`() {
        val set = InvariantSet(
            listOf(
                rxInvariant,
                Invariant("arch", "архитектура", "Без логики во вью"),
            ),
        )
        // LLM находит семантическое нарушение арх-инварианта, а forbid ловит rxjava.
        val client = InvFakeClient(guardJson = "{\"violations\":[{\"id\":\"arch\",\"why\":\"логика во вью\"}]}")
        val violations = InvariantChecker(client).check("class View { logic(); import io.reactivex.X }", set, "код")
        val ids = violations.map { it.invariant.id }.toSet()
        assertTrue("stack-no-rxjava" in ids) // детерминированно
        assertTrue("arch" in ids)            // от LLM
        assertEquals(2, violations.size)     // без дублей
    }

    @Test
    fun `checker returns nothing when llm says clean and no forbidden tokens`() {
        val set = InvariantSet(listOf(rxInvariant))
        val client = InvFakeClient(guardJson = "{\"violations\":[]}")
        assertTrue(InvariantChecker(client).check("обычный безопасный код на корутинах", set, "код").isEmpty())
    }

    // ---------- Оркестратор: отказ и принуждение ----------

    private fun tempAgent(client: ChatClient): CodingAgent {
        val root = createTempDirectory("day14-agent")
        return CodingAgent(
            client = client,
            profiles = ProfileStore(root.resolve("profiles")),
            tasks = TaskStore(root.resolve("state")),
            invariantStore = InvariantStore(root.resolve("invariants")),
            pipeline = CodingPipeline(client),
        )
    }

    @Test
    fun `request that conflicts with an invariant is refused with explanation`() {
        val agent = tempAgent(InvFakeClient()) // сиды DEFAULTS содержат запрет RxJava
        val outcome = agent.startTask("Добавь RxJava для реактивности в проект")
        assertTrue(outcome is StageOutcome.Refused)
        outcome as StageOutcome.Refused
        assertTrue(outcome.violations.any { it.invariant.id == "stack-no-rxjava" })
        assertTrue(outcome.explanation.isNotBlank())
        // Задача не стартовала.
        assertTrue(agent.task.isEmpty())
    }

    @Test
    fun `defaults include the android-only domain invariant`() {
        assertTrue(InvariantStore.DEFAULTS.any { it.id == "domain-android-only" })
    }

    @Test
    fun `non-Android request is refused via the android-only invariant`() {
        // Доменный инвариант проверяется LLM-контролёром (без forbid-токена) — задаём его вердикт.
        val client = InvFakeClient(
            guardJson = "{\"violations\":[{\"id\":\"domain-android-only\",\"why\":\"это бэкенд, не Android\"}]}",
        )
        val agent = tempAgent(client)
        val outcome = agent.startTask("Сделай бэкенд-сервис на Node.js с REST API")
        assertTrue(outcome is StageOutcome.Refused)
        assertTrue((outcome as StageOutcome.Refused).violations.any { it.invariant.id == "domain-android-only" })
        assertTrue(agent.task.isEmpty())
    }

    @Test
    fun `clean request is accepted and runs planning`() {
        val agent = tempAgent(InvFakeClient())
        val outcome = agent.startTask("Сделай функцию суммирования списка чисел")
        assertTrue(outcome is StageOutcome.Ran)
        assertEquals(TaskStage.PLANNING, (outcome as StageOutcome.Ran).stage)
    }

    @Test
    fun `code that violates an invariant surfaces a warning after fix attempts`() {
        // Кодер упорно выдаёт RxJava-импорт — детерминированная проверка ловит его на каждом проходе.
        val client = InvFakeClient(reply = "Готово.\n```kotlin\nimport io.reactivex.Flowable\n```")
        val agent = tempAgent(client)
        agent.startTask("Сделай поток событий")     // запрос чистый — задача стартует
        val adv = agent.advance() as StageOutcome.Ran // execution
        assertEquals(TaskStage.EXECUTION, adv.stage)
        assertTrue(adv.violations.any { it.invariant.id == "stack-no-rxjava" })
    }
}
