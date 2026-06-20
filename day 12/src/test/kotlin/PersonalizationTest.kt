import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
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

/** Свежий агент с изолированными temp-хранилищами профилей и памяти. */
private fun tempAgent(client: ChatClient): MemoryAgent {
    val root: Path = createTempDirectory("day12")
    return MemoryAgent(
        client = client,
        profiles = ProfileStore(root.resolve("profiles")),
        memoryRoot = root.resolve("memory"),
    )
}

private fun systemBlocks(c: FakeChatClient) = c.lastMainMessages.filter { it.role == "system" }

class PersonalizationTest {

    @Test
    fun `profile store round-trips a profile through disk`() {
        val store = ProfileStore(createTempDirectory("day12-prof"))
        val p = UserProfile(
            id = "igor",
            name = "Игорь",
            context = Context(goal = "масса", level = "продвинутый"),
            style = Style(verbosity = "кратко", tone = "строго по делу"),
            format = Format(structure = "списком", numbers = true),
            constraints = listOf("только конкретика"),
        )
        store.save(p)
        val loaded = store.load("igor")
        assertNotNull(loaded)
        assertEquals("Игорь", loaded.name)
        assertEquals("кратко", loaded.style.verbosity)
        assertEquals(listOf("только конкретика"), loaded.constraints)
    }

    @Test
    fun `demo profiles are seeded only when store is empty`() {
        val store = ProfileStore(createTempDirectory("day12-seed"))
        assertTrue(store.isEmpty())
        val seeded = store.seedDemoProfiles()
        assertEquals(3, seeded.size)
        assertEquals(listOf("anna", "igor", "maria"), store.list())
        // Повторный посев ничего не делает.
        assertTrue(store.seedDemoProfiles().isEmpty())
    }

    @Test
    fun `profile is injected into every request as a personalization block`() {
        val client = FakeChatClient()
        val agent = tempAgent(client)
        agent.processUserMessage("составь тренировку")

        val profileBlock = systemBlocks(client).firstOrNull { it.content.contains("Персонализация — профиль") }
        assertNotNull(profileBlock)
        // Профиль реально несёт стиль/формат/ограничения активного пользователя.
        assertTrue(profileBlock.content.contains("Стиль общения:"))
        assertTrue(profileBlock.content.contains("Формат ответа:"))
    }

    @Test
    fun `turning personalization off removes the profile from the prompt`() {
        val client = FakeChatClient()
        val agent = tempAgent(client)

        agent.personalizationOn = false
        agent.processUserMessage("составь тренировку")
        assertTrue(systemBlocks(client).none { it.content.contains("Персонализация — профиль") })

        agent.personalizationOn = true
        agent.processUserMessage("ещё раз")
        assertTrue(systemBlocks(client).any { it.content.contains("Персонализация — профиль") })
    }

    @Test
    fun `different profiles inject different content for the same request`() {
        val client = FakeChatClient()
        val agent = tempAgent(client)

        assertTrue(agent.switchProfile("anna"))
        agent.processUserMessage("составь тренировку на сегодня")
        val annaBlock = systemBlocks(client).first { it.content.contains("Персонализация") }.content

        assertTrue(agent.switchProfile("igor"))
        agent.processUserMessage("составь тренировку на сегодня")
        val igorBlock = systemBlocks(client).first { it.content.contains("Персонализация") }.content

        assertTrue(annaBlock != igorBlock)
        assertTrue(annaBlock.contains("Анна"))
        assertTrue(igorBlock.contains("Игорь"))
        assertTrue(igorBlock.contains("кратко"))
        assertTrue(annaBlock.contains("подробно"))
    }

    @Test
    fun `each profile keeps its own memory and switching swaps it`() {
        val routerJson = """{"writes":[{"layer":"working","kind":"task","key":"","value":"План Анны"}]}"""
        val client = FakeChatClient(routerJson = routerJson)
        val agent = tempAgent(client)

        agent.switchProfile("anna")
        agent.processUserMessage("заведи задачу")
        assertEquals("План Анны", agent.working.taskTitle)

        // У Игоря своя память — задачи Анны там нет.
        agent.switchProfile("igor")
        assertNull(agent.working.taskTitle)

        // Вернулись к Анне — её рабочая память на месте.
        agent.switchProfile("anna")
        assertEquals("План Анны", agent.working.taskTitle)
    }

    @Test
    fun `meta of an Ok turn reports the active profile`() {
        val client = FakeChatClient()
        val agent = tempAgent(client)
        agent.switchProfile("maria")
        val outcome = agent.processUserMessage("привет") as TurnOutcome.Ok
        assertEquals("Мария", outcome.profileName)
        assertTrue(outcome.personalizationOn)
    }

    @Test
    fun `router never edits the profile`() {
        // Даже если роутер «захотел» бы тронуть профиль — у него нет такого слоя.
        val before = ProfileStore(createTempDirectory("day12-imm"))
        val agent = MemoryAgent(
            client = FakeChatClient(routerJson = """{"writes":[{"layer":"long_term","kind":"knowledge","key":"","value":"любит бег"}]}"""),
            profiles = before,
            memoryRoot = createTempDirectory("day12-imm-mem"),
        )
        val nameBefore = agent.profile.name
        agent.processUserMessage("я люблю бег")
        // Профиль не изменился; факт ушёл в долговременную память, а не в профиль.
        assertEquals(nameBefore, agent.profile.name)
        assertTrue(agent.longTerm.knowledge().contains("любит бег"))
    }

    @Test
    fun `memory survives reload for the same profile`() {
        val root = createTempDirectory("day12-reload")
        val profiles = ProfileStore(root.resolve("profiles"))
        val memRoot = root.resolve("memory")
        val routerJson = """{"writes":[{"layer":"working","kind":"task","key":"","value":"Кэш"}]}"""

        val agent = MemoryAgent(FakeChatClient(routerJson = routerJson), profiles, memoryRoot = memRoot)
        agent.switchProfile("igor")
        agent.processUserMessage("делаем кэш")

        val reloaded = MemoryAgent(FakeChatClient(), ProfileStore(root.resolve("profiles")), memoryRoot = memRoot)
        reloaded.switchProfile("igor")
        assertEquals("Кэш", reloaded.working.taskTitle)
        assertEquals(2, reloaded.shortTerm.size)
    }

    @Test
    fun `new session clears working memory but keeps the profile`() {
        val routerJson = """{"writes":[{"layer":"working","kind":"task","key":"","value":"Задача"}]}"""
        val agent = tempAgent(FakeChatClient(routerJson = routerJson))
        val nameBefore = agent.profile.name
        agent.processUserMessage("заведи задачу")
        assertNotNull(agent.working.taskTitle)

        agent.newSession()
        assertNull(agent.working.taskTitle)
        assertEquals(nameBefore, agent.profile.name)
    }

    @Test
    fun `reset wipes all profiles and memory and returns to first-run state`() {
        val root = createTempDirectory("day12-reset")
        val profiles = ProfileStore(root.resolve("profiles"))
        val memRoot = root.resolve("memory")
        val agent = MemoryAgent(
            FakeChatClient(routerJson = """{"writes":[{"layer":"working","kind":"task","key":"","value":"X"}]}"""),
            profiles,
            memoryRoot = memRoot,
        )
        // Заводим личный профиль и немного памяти.
        agent.createProfile(UserProfile(id = "you", name = "Вы"))
        agent.processUserMessage("заведи задачу")
        assertTrue(agent.hasProfile("you"))
        assertNotNull(agent.working.taskTitle)

        agent.resetAll()

        assertFalse(agent.hasProfile("you"))                          // личный профиль удалён
        assertEquals(listOf("anna", "igor", "maria"), profiles.list()) // остались только демо
        assertNull(agent.working.taskTitle)                          // память чистая
        assertTrue(agent.personalizationOn)                          // режим сброшен
    }

    @Test
    fun `empty message is rejected`() {
        val agent = tempAgent(FakeChatClient())
        assertTrue(agent.processUserMessage("   ") is TurnOutcome.Fail)
    }

    @Test
    fun `renderProfile lists style format and constraints`() {
        val p = UserProfile(
            id = "x", name = "Икс",
            style = Style(verbosity = "кратко"),
            format = Format(structure = "по шагам"),
            constraints = listOf("беречь спину"),
        )
        val text = PromptBuilder.renderProfile(p)
        assertTrue(text.contains("Стиль общения"))
        assertTrue(text.contains("Формат ответа"))
        assertTrue(text.contains("беречь спину"))
        assertFalse(text.contains("[Долговременная"))
    }
}
