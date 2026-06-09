/**
 * Агент с сохранением контекста: история диалога переживает перезапуск приложения.
 */
class ChatAgent(
    private val client: DeepSeekClient,
    private val systemPrompt: String = Config.SYSTEM_PROMPT,
    private val historyStore: ChatHistoryStore = ChatHistoryStore(),
) {
    private val history = mutableListOf<ChatMessage>()

    val turns: List<AgentTurn>
        get() = history
            .filter { it.role != "system" }
            .map { msg ->
                when (msg.role) {
                    "user" -> AgentTurn.User(msg.content)
                    "assistant" -> AgentTurn.Assistant(msg.content)
                    else -> error("Неизвестная роль: ${msg.role}")
                }
            }

    val messageCount: Int
        get() = history.count { it.role != "system" }

    val restoredMessageCount: Int
        get() = _restoredMessageCount

    private var _restoredMessageCount = 0

    init {
        loadOrReset()
    }

    private fun loadOrReset() {
        val saved = historyStore.load()
        history.clear()

        if (saved != null && saved.isNotEmpty()) {
            history.addAll(saved)
            if (history.none { it.role == "system" }) {
                history.add(0, ChatMessage(role = "system", content = systemPrompt))
            }
            _restoredMessageCount = messageCount
        } else {
            resetWithoutPersist()
            _restoredMessageCount = 0
        }
    }

    fun reset() {
        resetWithoutPersist()
        historyStore.clear()
        _restoredMessageCount = 0
    }

    private fun resetWithoutPersist() {
        history.clear()
        history += ChatMessage(role = "system", content = systemPrompt)
    }

    private fun persist() {
        historyStore.save(history.toList())
    }

    fun processUserMessage(userText: String): AgentResult {
        val trimmed = userText.trim()
        require(trimmed.isNotEmpty()) { "Сообщение не может быть пустым" }

        history += ChatMessage(role = "user", content = trimmed)

        val started = System.nanoTime()
        return try {
            val reply = client.chat(history)
            val latencyMs = (System.nanoTime() - started) / 1_000_000

            history += ChatMessage(role = "assistant", content = reply)
            persist()

            AgentResult.Success(
                userMessage = trimmed,
                assistantMessage = reply,
                latencyMs = latencyMs,
                turnIndex = turns.size - 1,
            )
        } catch (e: Exception) {
            history.removeLast()
            AgentResult.Error(
                userMessage = trimmed,
                message = e.message ?: e.toString(),
            )
        }
    }
}

sealed class AgentTurn {
    data class User(val content: String) : AgentTurn()
    data class Assistant(val content: String) : AgentTurn()
}

sealed class AgentResult {
    data class Success(
        val userMessage: String,
        val assistantMessage: String,
        val latencyMs: Long,
        val turnIndex: Int,
    ) : AgentResult()

    data class Error(
        val userMessage: String,
        val message: String,
    ) : AgentResult()
}
