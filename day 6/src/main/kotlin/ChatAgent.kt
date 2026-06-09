/**
 * Простой агент: принимает запрос пользователя, отправляет полную историю
 * диалога в LLM, получает ответ и сохраняет его в контексте.
 *
 * Вся логика «запрос → API → ответ → обновление истории» инкапсулирована здесь,
 * а не в UI.
 */
class ChatAgent(
    private val client: DeepSeekClient,
    private val systemPrompt: String = Config.SYSTEM_PROMPT,
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

    init {
        reset()
    }

    fun reset() {
        history.clear()
        history += ChatMessage(role = "system", content = systemPrompt)
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
