class CompressingChatAgent(
    private val client: ChatClient,
    private val systemPrompt: String = Config.SYSTEM_PROMPT,
    recentMessageCount: Int = Config.recentMessageCount(),
    compressBatchSize: Int = Config.compressBatchSize(),
) {
    private val compressor = ContextCompressor(
        client = client,
        recentMessageCount = recentMessageCount,
        compressBatchSize = compressBatchSize,
        systemPrompt = systemPrompt,
    )

    private val displayTurns = mutableListOf<AgentTurn>()
    private val turnStats = mutableListOf<TurnStats>()

    val turns: List<AgentTurn> get() = displayTurns.toList()
    val stats: List<TurnStats> get() = turnStats.toList()
    val summary: String? get() = compressor.summaryText
    val compressionCount: Int get() = compressor.compressionCount

    val totalPromptTokens: Int get() = turnStats.sumOf { it.promptTokens }
    val totalCompletionTokens: Int get() = turnStats.sumOf { it.completionTokens }
    val totalCostUsd: Double get() = turnStats.sumOf { it.turnCostUsd }

    fun reset() {
        compressor.reset()
        displayTurns.clear()
        turnStats.clear()
    }

    fun processUserMessage(userText: String): AgentResult {
        val trimmed = userText.trim()
        require(trimmed.isNotEmpty()) { "Сообщение не может быть пустым" }

        val apiMessages = compressor.buildApiMessages(trimmed)
        val contextTokensEstimated = compressor.estimateContextTokens(trimmed)

        return try {
            val result = client.chat(apiMessages)
            compressor.appendTurn(trimmed, result.content)

            displayTurns += AgentTurn.User(trimmed)
            displayTurns += AgentTurn.Assistant(result.content)

            val stats = TurnStats(
                turnIndex = displayTurns.size / 2,
                promptTokens = result.promptTokens,
                completionTokens = result.completionTokens,
                contextTokensEstimated = contextTokensEstimated,
                fullHistoryTokensEstimated = compressor.estimateFullHistoryTokens(),
                turnCostUsd = result.estimatedCostUsd,
                compressionCount = compressor.compressionCount,
                summaryPresent = compressor.summaryText != null,
                latencyMs = result.latencyMs,
            )
            turnStats += stats

            AgentResult.Success(
                userMessage = trimmed,
                assistantMessage = result.content,
                stats = stats,
            )
        } catch (e: Exception) {
            AgentResult.Error(
                userMessage = trimmed,
                message = e.message ?: e.toString(),
            )
        }
    }
}

class PlainChatAgent(
    private val client: ChatClient,
    private val systemPrompt: String = Config.SYSTEM_PROMPT,
) {
    private val history = mutableListOf<ChatMessage>()
    private val displayTurns = mutableListOf<AgentTurn>()
    private val turnStats = mutableListOf<TurnStats>()

    val turns: List<AgentTurn> get() = displayTurns.toList()
    val stats: List<TurnStats> get() = turnStats.toList()

    val totalPromptTokens: Int get() = turnStats.sumOf { it.promptTokens }
    val totalCompletionTokens: Int get() = turnStats.sumOf { it.completionTokens }
    val totalCostUsd: Double get() = turnStats.sumOf { it.turnCostUsd }

    init {
        reset()
    }

    fun reset() {
        history.clear()
        displayTurns.clear()
        turnStats.clear()
        history += ChatMessage(role = "system", content = systemPrompt)
    }

    fun processUserMessage(userText: String): AgentResult {
        val trimmed = userText.trim()
        require(trimmed.isNotEmpty()) { "Сообщение не может быть пустым" }

        history += ChatMessage(role = "user", content = trimmed)
        val fullHistoryTokens = TokenCounter.estimateMessagesTokens(history)

        return try {
            val result = client.chat(history.toList())
            history += ChatMessage(role = "assistant", content = result.content)

            displayTurns += AgentTurn.User(trimmed)
            displayTurns += AgentTurn.Assistant(result.content)

            val stats = TurnStats(
                turnIndex = displayTurns.size / 2,
                promptTokens = result.promptTokens,
                completionTokens = result.completionTokens,
                contextTokensEstimated = fullHistoryTokens,
                fullHistoryTokensEstimated = TokenCounter.estimateMessagesTokens(history),
                turnCostUsd = result.estimatedCostUsd,
                compressionCount = 0,
                summaryPresent = false,
                latencyMs = result.latencyMs,
            )
            turnStats += stats

            AgentResult.Success(
                userMessage = trimmed,
                assistantMessage = result.content,
                stats = stats,
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

data class TurnStats(
    val turnIndex: Int,
    val promptTokens: Int,
    val completionTokens: Int,
    val contextTokensEstimated: Int,
    val fullHistoryTokensEstimated: Int,
    val turnCostUsd: Double,
    val compressionCount: Int,
    val summaryPresent: Boolean,
    val latencyMs: Long,
)

sealed class AgentTurn {
    data class User(val content: String) : AgentTurn()
    data class Assistant(val content: String) : AgentTurn()
    data class Summary(val content: String, val compressionIndex: Int) : AgentTurn()
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
