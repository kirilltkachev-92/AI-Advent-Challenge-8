class SlidingWindowAgent(
    private val client: ChatClient,
    private val systemPrompt: String = Config.SYSTEM_PROMPT,
    private val windowSize: Int = Config.windowSize(),
) : ContextAgent {
    override val strategy = ContextStrategy.SLIDING_WINDOW

    private val fullHistory = mutableListOf<ChatMessage>()
    private val displayTurns = mutableListOf<AgentTurn>()
    private val turnStats = mutableListOf<TurnStats>()

    override val turns: List<AgentTurn> get() = displayTurns.toList()
    override val stats: List<TurnStats> get() = turnStats.toList()
    override val totalPromptTokens: Int get() = turnStats.sumOf { it.promptTokens }
    override val totalCostUsd: Double get() = turnStats.sumOf { it.turnCostUsd }

    val droppedMessageCount: Int
        get() {
            val dialogMessages = fullHistory.count { it.role == "user" || it.role == "assistant" }
            val windowMessages = takeRecentWindow(fullHistory, windowSize).size
            return (dialogMessages - windowMessages).coerceAtLeast(0)
        }

    override fun reset() {
        fullHistory.clear()
        displayTurns.clear()
        turnStats.clear()
    }

    fun buildApiMessages(userText: String): List<ChatMessage> {
        val window = takeRecentWindow(fullHistory, windowSize)
        return buildList {
            add(ChatMessage(role = "system", content = systemPrompt))
            addAll(window)
            add(ChatMessage(role = "user", content = userText))
        }
    }

    fun estimateContextTokens(userText: String): Int =
        TokenCounter.estimateMessagesTokens(buildApiMessages(userText))

    override fun processUserMessage(userText: String): AgentResult {
        val trimmed = userText.trim()
        require(trimmed.isNotEmpty()) { "Сообщение не может быть пустым" }

        val apiMessages = buildApiMessages(trimmed)
        val contextTokens = TokenCounter.estimateMessagesTokens(apiMessages)

        return try {
            val result = client.chat(apiMessages)
            fullHistory += ChatMessage(role = "user", content = trimmed)
            fullHistory += ChatMessage(role = "assistant", content = result.content)

            displayTurns += AgentTurn.User(trimmed)
            displayTurns += AgentTurn.Assistant(result.content)

            val dropped = droppedMessageCount
            val stats = TurnStats(
                turnIndex = displayTurns.count { it is AgentTurn.User },
                promptTokens = result.promptTokens,
                completionTokens = result.completionTokens,
                contextTokensEstimated = contextTokens,
                turnCostUsd = result.estimatedCostUsd,
                latencyMs = result.latencyMs,
                strategyMeta = if (dropped > 0) "отброшено $dropped сообщ." else null,
            )
            turnStats += stats

            AgentResult.Success(trimmed, result.content, stats)
        } catch (e: Exception) {
            AgentResult.Error(trimmed, e.message ?: e.toString())
        }
    }
}
