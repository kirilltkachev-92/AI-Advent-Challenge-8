/**
 * Управление контекстом: последние N сообщений «как есть», остальное — summary.
 */
class ContextCompressor(
    private val client: ChatClient,
    private val recentMessageCount: Int = Config.recentMessageCount(),
    private val compressBatchSize: Int = Config.compressBatchSize(),
    private val systemPrompt: String = Config.SYSTEM_PROMPT,
) {
    private val pendingMessages = mutableListOf<ChatMessage>()
    private var summary: String? = null
    private var compressionEvents = 0
    private var summaryTokensEstimated = 0

    val summaryText: String? get() = summary
    val compressionCount: Int get() = compressionEvents
    val pendingCount: Int get() = pendingMessages.size

    fun reset() {
        pendingMessages.clear()
        summary = null
        compressionEvents = 0
        summaryTokensEstimated = 0
    }

    fun seed(messages: List<ChatMessage>) {
        reset()
        pendingMessages.addAll(messages.filter { it.role == "user" || it.role == "assistant" })
        while (shouldCompress()) {
            compressNextBatch()
        }
    }

    fun appendTurn(userMessage: String, assistantMessage: String) {
        pendingMessages += ChatMessage(role = "user", content = userMessage)
        pendingMessages += ChatMessage(role = "assistant", content = assistantMessage)
        while (shouldCompress()) {
            compressNextBatch()
        }
    }

    fun buildApiMessages(newUserText: String? = null): List<ChatMessage> {
        val result = mutableListOf(ChatMessage(role = "system", content = systemPrompt))
        summary?.let { text ->
            result += ChatMessage(
                role = "user",
                content = "Краткое содержание предыдущего диалога:\n$text",
            )
            result += ChatMessage(
                role = "assistant",
                content = "Понял, учту этот контекст при ответах.",
            )
        }
        result += pendingMessages.takeLast(recentMessageCount)
        newUserText?.let { result += ChatMessage(role = "user", content = it) }
        return result
    }

    fun estimateContextTokens(newUserText: String? = null): Int =
        TokenCounter.estimateMessagesTokens(buildApiMessages(newUserText))

    fun estimateFullHistoryTokens(): Int =
        TokenCounter.estimateMessagesTokens(
            listOf(ChatMessage(role = "system", content = systemPrompt)) + pendingMessages,
        )

    private fun shouldCompress(): Boolean =
        pendingMessages.size > recentMessageCount + compressBatchSize

    private fun compressNextBatch() {
        val compressibleCount = pendingMessages.size - recentMessageCount
        if (compressibleCount < compressBatchSize) return

        val batch = pendingMessages.take(compressBatchSize)
        val newSummary = requestSummary(batch)
        summary = mergeSummaries(summary, newSummary)
        summaryTokensEstimated = TokenCounter.estimateTextTokens(summary.orEmpty())
        repeat(compressBatchSize) { pendingMessages.removeAt(0) }
        compressionEvents++
    }

    private fun requestSummary(batch: List<ChatMessage>): String {
        val dialog = batch.joinToString("\n") { "${it.role}: ${it.content}" }
        val userPrompt = buildString {
            if (summary != null) {
                append("Текущее резюме:\n$summary\n\n")
                append("Добавь в резюме новый фрагмент диалога:\n")
            } else {
                append("Сожми следующий фрагмент диалога:\n")
            }
            append(dialog)
            append("\n\nВерни только обновлённое резюме, без пояснений.")
        }

        val result = client.chat(
            listOf(
                ChatMessage(role = "system", content = Config.SUMMARY_SYSTEM_PROMPT),
                ChatMessage(role = "user", content = userPrompt),
            ),
        )
        return result.content.trim()
    }

    private fun mergeSummaries(existing: String?, fresh: String): String =
        if (existing.isNullOrBlank()) fresh else fresh
}
