enum class ContextStrategy(val label: String, val description: String) {
    SLIDING_WINDOW(
        label = "Sliding Window",
        description = "Только последние N сообщений — остальное отбрасывается",
    ),
    FACTS(
        label = "Sticky Facts",
        description = "Блок key-value фактов + последние N сообщений",
    ),
    BRANCHING(
        label = "Branching",
        description = "Checkpoint и независимые ветки диалога",
    ),
}

data class TurnStats(
    val turnIndex: Int,
    val promptTokens: Int,
    val completionTokens: Int,
    val contextTokensEstimated: Int,
    val turnCostUsd: Double,
    val extraPromptTokens: Int = 0,
    val latencyMs: Long,
    val strategyMeta: String? = null,
)

sealed class AgentTurn {
    data class User(val content: String) : AgentTurn()
    data class Assistant(val content: String) : AgentTurn()
    data class SystemNote(val content: String) : AgentTurn()
    data class Checkpoint(val label: String, val turnIndex: Int) : AgentTurn()
}

sealed class AgentResult {
    data class Success(
        val userMessage: String,
        val assistantMessage: String,
        val stats: TurnStats,
    ) : AgentResult()

    data class Error(
        val userMessage: String,
        val message: String,
    ) : AgentResult()
}

interface ContextAgent {
    val strategy: ContextStrategy
    val turns: List<AgentTurn>
    val stats: List<TurnStats>
    val totalPromptTokens: Int
    val totalCostUsd: Double

    fun reset()
    fun processUserMessage(userText: String): AgentResult
}

fun takeRecentWindow(messages: List<ChatMessage>, windowSize: Int): List<ChatMessage> =
    messages
        .filter { it.role == "user" || it.role == "assistant" }
        .takeLast(windowSize)

fun formatFactsBlock(facts: Map<String, String>): String {
    if (facts.isEmpty()) return "(факты пока не зафиксированы)"
    return facts.entries.joinToString("\n") { (key, value) -> "• $key: $value" }
}
