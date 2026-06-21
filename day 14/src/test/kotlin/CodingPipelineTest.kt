import java.util.Collections
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Потокобезопасный фейковый клиент: считает запросы и запоминает system-промпты (агенты бегут
 * в рое параллельно). Возвращает фиксированный ответ.
 */
private class RecordingClient(private val reply: String = "артефакт-агента") : ChatClient {
    val systemPrompts: MutableList<String> = Collections.synchronizedList(mutableListOf())

    override fun chat(messages: List<ChatMessage>): ChatResult {
        messages.firstOrNull { it.role == "system" }?.let { systemPrompts.add(it.content) }
        return ChatResult(reply, 1, 1, 1, 2, false, 0.0)
    }

    val calls: Int get() = systemPrompts.size
}

class CodingPipelineTest {

    @Test
    fun `planning spawns a research swarm plus a planner`() {
        val client = RecordingClient()
        val r = CodingPipeline(client).runPlanning("сделай парсер", "профиль", "")
        assertEquals(TaskStage.PLANNING, r.stage)
        assertEquals(4, r.runs.size) // 3 исследователя + планировщик
        assertEquals(4, client.calls)
        assertTrue(r.runs.map { it.name }.containsAll(
            listOf("research:architecture", "research:risks", "research:libraries", "planner"),
        ))
    }

    @Test
    fun `execution runs coder then test-writer and merges artifact`() {
        val client = RecordingClient()
        val r = CodingPipeline(client).runExecution("сделай парсер", "план", "профиль", "")
        assertEquals(2, r.runs.size)
        assertTrue(r.runs.map { it.name }.containsAll(listOf("coder", "test-writer")))
        assertTrue(r.artifact.contains("## Реализация"))
        assertTrue(r.artifact.contains("## Тесты"))
    }

    @Test
    fun `validation spawns a reviewer swarm plus a validator`() {
        val client = RecordingClient()
        val r = CodingPipeline(client).runValidation("сделай парсер", "план", "код", "профиль", "")
        assertEquals(3, r.runs.size) // 2 ревьюера + валидатор
        assertTrue(r.runs.map { it.name }.containsAll(
            listOf("review:correctness", "review:security", "validator"),
        ))
    }

    @Test
    fun `profile block is injected into agents`() {
        val client = RecordingClient()
        CodingPipeline(client).runPlanning("сделай парсер", "[Профиль разработчика] Язык: Rust", "")
        assertTrue(client.systemPrompts.all { it.contains("[Профиль разработчика] Язык: Rust") })
    }

    // ---------- Уровень оркестратора ----------

    private fun tempAgent(client: ChatClient): CodingAgent {
        val root = createTempDirectory("day14")
        return CodingAgent(
            client = client,
            profiles = ProfileStore(root.resolve("profiles")),
            tasks = TaskStore(root.resolve("state")),
            invariantStore = InvariantStore(root.resolve("invariants")),
            pipeline = CodingPipeline(client),
        )
    }

    @Test
    fun `startTask runs planning and produces an artifact`() {
        val agent = tempAgent(RecordingClient())
        val out = agent.startTask("сделай CLI")
        assertTrue(out is StageOutcome.Ran)
        assertEquals(TaskStage.PLANNING, (out as StageOutcome.Ran).stage)
        assertNotNull(agent.task.artifact(TaskStage.PLANNING))
    }

    @Test
    fun `advance runs the next stage and illegal jump is blocked`() {
        val agent = tempAgent(RecordingClient())
        agent.startTask("сделай CLI")
        val adv = agent.advance() // planning → execution
        assertTrue(adv is StageOutcome.Ran)
        assertEquals(TaskStage.EXECUTION, agent.task.stage)
        assertNotNull(agent.task.artifact(TaskStage.EXECUTION))

        val blocked = agent.gotoStage("done") // execution → done запрещён
        assertTrue(blocked is StageOutcome.Blocked)
        assertEquals(TaskStage.EXECUTION, agent.task.stage)
    }

    @Test
    fun `task survives a restart at any stage (pause and resume)`() {
        val root = createTempDirectory("day14-resume")
        val client = RecordingClient()
        fun newAgent() = CodingAgent(
            client = client,
            profiles = ProfileStore(root.resolve("profiles")),
            tasks = TaskStore(root.resolve("state")),
            invariantStore = InvariantStore(root.resolve("invariants")),
            pipeline = CodingPipeline(client),
        )

        val agent = newAgent()
        agent.startTask("сделай CLI")
        agent.advance() // на execution
        assertEquals(TaskStage.EXECUTION, agent.task.stage)

        // «Перезапуск»: новый агент с тем же хранилищем.
        val reloaded = newAgent()
        assertEquals(TaskStage.EXECUTION, reloaded.task.stage)
        assertEquals("сделай CLI", reloaded.task.request)
        assertNotNull(reloaded.task.artifact(TaskStage.PLANNING))
        assertNotNull(reloaded.task.artifact(TaskStage.EXECUTION))
    }
}
