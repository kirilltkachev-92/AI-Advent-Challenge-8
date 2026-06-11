/**
 * Агент с подсчётом токенов: текущий запрос, вся история, ответ модели.
 */
class ChatAgent(
    private val client: ChatClient,
    private val systemPrompt: String = Config.SYSTEM_PROMPT,
    private val defaultContextLimit: Int = Config.contextLimitTokens(),
) {
    private var contextLimit: Int = defaultContextLimit
    private val history = mutableListOf<ChatMessage>()
    private val turnStats = mutableListOf<TurnTokenStats>()

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

    val tokenHistory: List<TurnTokenStats> get() = turnStats.toList()

    val messageCount: Int
        get() = history.count { it.role != "system" }

    val currentHistoryTokens: Int
        get() = TokenCounter.estimateMessagesTokens(history)

    val totalSessionCostUsd: Double
        get() = turnStats.lastOrNull()?.cumulativeCostUsd ?: 0.0

    val contextLimitTokens: Int
        get() = contextLimit

    init {
        reset()
    }

    fun reset() {
        contextLimit = defaultContextLimit
        history.clear()
        turnStats.clear()
        history += ChatMessage(role = "system", content = systemPrompt)
    }

    fun setContextLimit(tokens: Int) {
        contextLimit = tokens.coerceAtLeast(256)
    }

    fun previewTokens(userText: String): TokenPreview {
        val trimmed = userText.trim()
        val userTokens = if (trimmed.isEmpty()) 0 else TokenCounter.estimateUserMessageTokens(trimmed)
        val historyWithUser = if (trimmed.isEmpty()) {
            history
        } else {
            history + ChatMessage(role = "user", content = trimmed)
        }
        val historyTokens = TokenCounter.estimateMessagesTokens(historyWithUser)
        return TokenPreview(
            userMessageTokens = userTokens,
            historyTokensEstimated = historyTokens,
            contextLimit = contextLimit,
            wouldOverflow = historyTokens > contextLimit,
        )
    }

    fun seedHistory(messages: List<ChatMessage>) {
        history.clear()
        turnStats.clear()
        history.addAll(messages)
        if (history.none { it.role == "system" }) {
            history.add(0, ChatMessage(role = "system", content = systemPrompt))
        }
    }

    fun processUserMessage(
        userText: String,
        bypassLocalOverflowGuard: Boolean = false,
    ): AgentResult {
        val trimmed = userText.trim()
        require(trimmed.isNotEmpty()) { "Сообщение не может быть пустым" }

        val preview = previewTokens(trimmed)
        if (!bypassLocalOverflowGuard && preview.wouldOverflow) {
            return AgentResult.Overflow(
                userMessage = trimmed,
                historyTokensEstimated = preview.historyTokensEstimated,
                contextLimit = contextLimit,
                source = OverflowSource.LocalGuard,
            )
        }

        history += ChatMessage(role = "user", content = trimmed)
        val historyBeforeReply = history.toList()
        val historyTokensEstimated = TokenCounter.estimateMessagesTokens(historyBeforeReply)

        return try {
            val result = client.chat(historyBeforeReply)
            history += ChatMessage(role = "assistant", content = result.content)

            val turnCost = result.estimatedCostUsd
            val cumulative = totalSessionCostUsd + turnCost
            val stats = TurnTokenStats(
                turnIndex = turns.size - 1,
                userMessageTokens = preview.userMessageTokens,
                historyTokensEstimated = historyTokensEstimated,
                promptTokens = result.promptTokens,
                completionTokens = result.completionTokens,
                totalTokens = result.totalTokens,
                tokensFromApi = result.tokensFromApi,
                turnCostUsd = turnCost,
                cumulativeCostUsd = cumulative,
                contextUsagePercent = TokenCounter.contextUsagePercent(result.promptTokens, contextLimit),
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
            val apiOverflow = isLikelyContextOverflow(e.message ?: e.toString())
            if (apiOverflow) {
                AgentResult.Overflow(
                    userMessage = trimmed,
                    historyTokensEstimated = historyTokensEstimated,
                    contextLimit = contextLimit,
                    source = OverflowSource.ApiRejection,
                    apiMessage = e.message ?: e.toString(),
                )
            } else {
                AgentResult.Error(
                    userMessage = trimmed,
                    message = e.message ?: e.toString(),
                )
            }
        }
    }

    private fun isLikelyContextOverflow(message: String): Boolean {
        val lower = message.lowercase()
        return lower.contains("maximum context") ||
            lower.contains("context length") ||
            lower.contains("max context") ||
            lower.contains("too many tokens") ||
            lower.contains("token limit") ||
            lower.contains("context window") ||
            (lower.contains("exceeds") && lower.contains("token")) ||
            (lower.contains("context") && lower.contains("exceed"))
    }
}

data class TokenPreview(
    val userMessageTokens: Int,
    val historyTokensEstimated: Int,
    val contextLimit: Int,
    val wouldOverflow: Boolean,
) {
    val contextUsagePercent: Double =
        TokenCounter.contextUsagePercent(historyTokensEstimated, contextLimit)
}

data class TurnTokenStats(
    val turnIndex: Int,
    val userMessageTokens: Int,
    val historyTokensEstimated: Int,
    val promptTokens: Int,
    val completionTokens: Int,
    val totalTokens: Int,
    val tokensFromApi: Boolean,
    val turnCostUsd: Double,
    val cumulativeCostUsd: Double,
    val contextUsagePercent: Double,
    val latencyMs: Long,
)

sealed class AgentTurn {
    data class User(val content: String) : AgentTurn()
    data class Assistant(val content: String) : AgentTurn()
    data class SyntheticHistory(
        val messageCount: Int,
        val estimatedTokens: Int,
        val contextLimit: Int,
    ) : AgentTurn()
}

enum class OverflowSource {
    LocalGuard,
    ApiRejection,
}

sealed class AgentResult {
    data class Success(
        val userMessage: String,
        val assistantMessage: String,
        val stats: TurnTokenStats,
    ) : AgentResult()

    data class Error(
        val userMessage: String,
        val message: String,
    ) : AgentResult()

    data class Overflow(
        val userMessage: String,
        val historyTokensEstimated: Int,
        val contextLimit: Int,
        val source: OverflowSource,
        val apiMessage: String? = null,
    ) : AgentResult()
}
