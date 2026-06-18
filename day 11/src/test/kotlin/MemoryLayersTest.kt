import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Фейковый клиент: на промпт роутера отдаёт заданный JSON, иначе — обычный ответ. */
private class FakeChatClient(
    private val routerJson: String = "{\"writes\":[]}",
    private val reply: String = "ответ ассистента",
) : ChatClient {
    var lastMainMessages: List<ChatMessage> = emptyList()
        private set

    override fun chat(messages: List<ChatMessage>): ChatResult {
        val isRouter = messages.firstOrNull()?.content == Config.ROUTER_PROMPT
        if (!isRouter) lastMainMessages = messages
        val content = if (isRouter) routerJson else reply
        return ChatResult(
            content = content,
            latencyMs = 1,
            promptTokens = TokenCounter.estimateMessagesTokens(messages),
            completionTokens = TokenCounter.estimateTextTokens(content),
            totalTokens = TokenCounter.estimateMessagesTokens(messages) + TokenCounter.estimateTextTokens(content),
            tokensFromApi = false,
            estimatedCostUsd = 0.0,
        )
    }
}

private fun tempStore(): MemoryStore = MemoryStore(createTempDirectory("day11-mem"))

class MemoryLayersTest {

    @Test
    fun `short-term keeps only the recent window for the api`() {
        val mem = ShortTermMemory(windowSize = 2)
        repeat(4) { i ->
            mem.appendUser("u$i")
            mem.appendAssistant("a$i")
        }
        assertEquals(8, mem.size)
        val window = mem.window()
        assertEquals(2, window.size)
        assertEquals(listOf("u3", "a3"), window.map { it.content })
    }

    @Test
    fun `working memory stores task data and stage`() {
        val w = WorkingMemory()
        assertTrue(w.isEmpty())
        w.setTask("Сервис авторизации")
        w.addRequirement("JWT-токены")
        w.addRequirement("JWT-токены") // дубликат игнорируется
        w.addNote("обсудить refresh")
        w.setStage(TaskStage.PLANNING)

        assertEquals("Сервис авторизации", w.taskTitle)
        assertEquals(1, w.requirements().size)
        assertEquals(TaskStage.PLANNING, w.stage)
        assertFalse(w.isEmpty())
    }

    @Test
    fun `long-term memory separates profile constraints decisions knowledge`() {
        val l = LongTermMemory()
        l.putProfile("стиль", "кратко")
        l.putConstraint("стек", "Kotlin + Ktor")
        l.addDecision("auth через JWT")
        l.addKnowledge("проект внутренний")

        assertEquals("кратко", l.profile()["стиль"])
        assertEquals("Kotlin + Ktor", l.constraints()["стек"])
        assertTrue(l.decisions().contains("auth через JWT"))
        assertTrue(l.knowledge().contains("проект внутренний"))
    }

    @Test
    fun `router routes writes to the correct layers`() {
        val routerJson = """
            {"writes":[
              {"layer":"long_term","kind":"constraint","key":"стек","value":"Kotlin"},
              {"layer":"long_term","kind":"profile","key":"стиль","value":"подробно"},
              {"layer":"working","kind":"task","key":"","value":"Парсер логов"},
              {"layer":"working","kind":"stage","key":"","value":"planning"}
            ]}
        """.trimIndent()
        val client = FakeChatClient(routerJson = routerJson)
        val agent = MemoryAgent(client = client, store = tempStore())

        val outcome = agent.processUserMessage("Сделаем парсер логов на Kotlin, отвечай подробно")
        assertTrue(outcome is TurnOutcome.Ok)

        assertEquals("Kotlin", agent.longTerm.constraints()["стек"])
        assertEquals("подробно", agent.longTerm.profile()["стиль"])
        assertEquals("Парсер логов", agent.working.taskTitle)
        assertEquals(TaskStage.PLANNING, agent.working.stage)
    }

    @Test
    fun `unknown layer is rejected not applied`() {
        val routerJson = """{"writes":[{"layer":"galaxy","kind":"task","key":"","value":"x"}]}"""
        val agent = MemoryAgent(client = FakeChatClient(routerJson = routerJson), store = tempStore())
        val outcome = agent.processUserMessage("привет") as TurnOutcome.Ok
        assertEquals(1, outcome.applied.size)
        assertFalse(outcome.applied.first().accepted)
        assertTrue(agent.working.isEmpty())
    }

    @Test
    fun `enabled layers control what reaches the prompt`() {
        val client = FakeChatClient(routerJson = """{"writes":[{"layer":"long_term","kind":"constraint","key":"стек","value":"Kotlin"}]}""")
        val agent = MemoryAgent(client = client, store = tempStore())

        // Только краткосрочная — долговременный блок в промпт не попадает.
        agent.enabledLayers.clear()
        agent.enabledLayers.add(MemoryLayer.SHORT_TERM)
        agent.processUserMessage("стек Kotlin")
        val systemBlocks = client.lastMainMessages.filter { it.role == "system" }
        assertEquals(1, systemBlocks.size) // только базовый системный промпт
        assertTrue(systemBlocks.none { it.content.contains("Долговременная память") })

        // Теперь включаем долговременную — блок появляется.
        agent.enabledLayers.add(MemoryLayer.LONG_TERM)
        agent.processUserMessage("продолжаем")
        assertTrue(client.lastMainMessages.any { it.content.contains("Долговременная память") })
    }

    @Test
    fun `each layer persists to its own file and survives reload`() {
        val store = tempStore()
        val routerJson = """
            {"writes":[
              {"layer":"long_term","kind":"profile","key":"роль","value":"бэкендер"},
              {"layer":"working","kind":"task","key":"","value":"Кэш"}
            ]}
        """.trimIndent()
        val agent = MemoryAgent(client = FakeChatClient(routerJson = routerJson), store = store)
        agent.processUserMessage("я бэкендер, делаем кэш")

        assertTrue(store.longTermFile.toFile().exists())
        assertTrue(store.workingFile.toFile().exists())
        assertTrue(store.shortTermFile.toFile().exists())

        // Новый агент с тем же store восстанавливает память.
        val reloaded = MemoryAgent(client = FakeChatClient(), store = store)
        assertEquals("бэкендер", reloaded.longTerm.profile()["роль"])
        assertEquals("Кэш", reloaded.working.taskTitle)
        assertEquals(2, reloaded.shortTerm.size)
    }

    @Test
    fun `new session clears short-term and working but keeps long-term`() {
        val store = tempStore()
        val routerJson = """
            {"writes":[
              {"layer":"long_term","kind":"constraint","key":"стек","value":"Kotlin"},
              {"layer":"working","kind":"task","key":"","value":"Задача A"}
            ]}
        """.trimIndent()
        val agent = MemoryAgent(client = FakeChatClient(routerJson = routerJson), store = store)
        agent.processUserMessage("стек Kotlin, задача A")

        agent.newSession()
        assertEquals(0, agent.shortTerm.size)
        assertNull(agent.working.taskTitle)
        assertEquals("Kotlin", agent.longTerm.constraints()["стек"]) // профиль сохранён
        assertFalse(store.workingFile.toFile().exists())
        assertTrue(store.longTermFile.toFile().exists())
    }

    @Test
    fun `empty message is rejected`() {
        val agent = MemoryAgent(client = FakeChatClient(), store = tempStore())
        val outcome = agent.processUserMessage("   ")
        assertTrue(outcome is TurnOutcome.Fail)
    }
}
