import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.io.path.writeText

@Serializable
data class ChatMessage(val role: String, val text: String)

/** Сериализуемое состояние сессии: история + память задачи (переживает перезапуск CLI). */
@Serializable
data class SessionData(
    val history: List<ChatMessage> = emptyList(),
    val state: TaskState = TaskState(),
)

/**
 * Сессия мини-чата (ядро Дня 25):
 *  - хранит историю диалога (и персистит её на диск — история переживает перезапуск);
 *  - на каждый вопрос зовёт RAG-агента, передавая ему память задачи и последние ходы;
 *  - после ответа обновляет память задачи отдельным вызовом LLM.
 * И интерактивный чат, и прогоны сценариев ходят через этот же код.
 */
class ChatSession(
    private val agent: RagAgent,
    private val stateUpdater: TaskStateUpdater,
    private val persistPath: Path?,
) {
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }

    val history = mutableListOf<ChatMessage>()
    var state = TaskState()
        private set

    fun load() {
        val path = persistPath ?: return
        if (!Files.exists(path)) return
        try {
            val data = json.decodeFromString(SessionData.serializer(), path.readText())
            history += data.history
            state = data.state
        } catch (e: Exception) {
            println("(не удалось прочитать сохранённую сессию: ${e.message})")
        }
    }

    fun reset() {
        history.clear()
        state = TaskState()
        persist()
    }

    fun ask(userMessage: String): StructuredAnswer {
        val dialog = renderRecentDialog()
        val answer = agent.ask(userMessage, dialog, state)

        val assistantText = if (answer.canAnswer) answer.answer else answer.clarification
        history += ChatMessage("user", userMessage)
        history += ChatMessage("assistant", assistantText)

        // Память задачи обновляется после каждого хода — цель/уточнения/ограничения
        // не зависят от того, сколько истории влезает в окно.
        state = stateUpdater.update(state, userMessage, assistantText)
        persist()
        return answer
    }

    /** Последние N ходов — «сырая» история; всё, что старше, живёт в памяти задачи. */
    private fun renderRecentDialog(): String =
        history.takeLast(Config.historyTurns() * 2).joinToString("\n") { m ->
            (if (m.role == "user") "Пользователь: " else "Ассистент: ") + m.text.take(500)
        }

    private fun persist() {
        val path = persistPath ?: return
        Files.createDirectories(path.parent)
        path.writeText(json.encodeToString(SessionData.serializer(), SessionData(history, state)))
    }
}
