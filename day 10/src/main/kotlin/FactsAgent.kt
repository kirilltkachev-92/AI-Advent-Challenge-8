class FactsAgent(
    private val client: ChatClient,
    private val factsExtractor: FactsExtractor = FactsExtractor(client),
    private val systemPrompt: String = Config.SYSTEM_PROMPT,
    private val windowSize: Int = Config.windowSize(),
) : ContextAgent {
    override val strategy = ContextStrategy.FACTS

    private val fullHistory = mutableListOf<ChatMessage>()
    private val facts = linkedMapOf<String, String>()
    private val displayTurns = mutableListOf<AgentTurn>()
    private val turnStats = mutableListOf<TurnStats>()

    override val turns: List<AgentTurn> get() = displayTurns.toList()
    override val stats: List<TurnStats> get() = turnStats.toList()
    override val totalPromptTokens: Int get() = turnStats.sumOf { it.promptTokens }
    override val totalCostUsd: Double get() = turnStats.sumOf { it.turnCostUsd }

    val factsSnapshot: Map<String, String> get() = facts.toMap()
    val factsExtractionCalls: Int get() = factsExtractor.extractionCalls

    override fun reset() {
        fullHistory.clear()
        facts.clear()
        displayTurns.clear()
        turnStats.clear()
    }

    fun buildApiMessages(userText: String): List<ChatMessage> {
        val window = takeRecentWindow(fullHistory, windowSize)
        val factsBlock = formatFactsBlock(facts)
        return buildList {
            add(ChatMessage(role = "system", content = systemPrompt))
            add(
                ChatMessage(
                    role = "system",
                    content = "[Ключевые факты из диалога]\n$factsBlock",
                ),
            )
            addAll(window)
            add(ChatMessage(role = "user", content = userText))
        }
    }

    override fun processUserMessage(userText: String): AgentResult {
        val trimmed = userText.trim()
        require(trimmed.isNotEmpty()) { "Сообщение не может быть пустым" }

        val extractionTokens = factsExtractor.updateFacts(
            currentFacts = facts,
            userMessage = trimmed,
            recentContext = fullHistory,
        )

        val apiMessages = buildApiMessages(trimmed)
        val contextTokens = TokenCounter.estimateMessagesTokens(apiMessages)

        return try {
            val result = client.chat(apiMessages)
            fullHistory += ChatMessage(role = "user", content = trimmed)
            fullHistory += ChatMessage(role = "assistant", content = result.content)

            displayTurns += AgentTurn.User(trimmed)
            if (facts.isNotEmpty()) {
                displayTurns += AgentTurn.SystemNote(
                    "facts (${facts.size}): " + facts.entries.take(3).joinToString { "${it.key}=${it.value}" },
                )
            }
            displayTurns += AgentTurn.Assistant(result.content)

            val stats = TurnStats(
                turnIndex = displayTurns.count { it is AgentTurn.User },
                promptTokens = result.promptTokens,
                completionTokens = result.completionTokens,
                contextTokensEstimated = contextTokens,
                turnCostUsd = result.estimatedCostUsd,
                extraPromptTokens = extractionTokens,
                latencyMs = result.latencyMs,
                strategyMeta = "фактов: ${facts.size}",
            )
            turnStats += stats

            AgentResult.Success(trimmed, result.content, stats)
        } catch (e: Exception) {
            AgentResult.Error(trimmed, e.message ?: e.toString())
        }
    }
}
