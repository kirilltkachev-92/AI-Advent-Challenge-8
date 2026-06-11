/**
 * Оценка токенов без вызова API.
 * Для точных значений используйте usage из ответа DeepSeek.
 */
object TokenCounter {
    const val CHARS_PER_TOKEN = 4.0
    const val MESSAGE_OVERHEAD_TOKENS = 4

    fun estimateTextTokens(text: String): Int =
        if (text.isEmpty()) 0 else (text.length / CHARS_PER_TOKEN).toInt().coerceAtLeast(1)

    fun estimateMessageTokens(message: ChatMessage): Int =
        MESSAGE_OVERHEAD_TOKENS + estimateTextTokens(message.content)

    fun estimateMessagesTokens(messages: List<ChatMessage>): Int =
        messages.sumOf { estimateMessageTokens(it) }

    fun estimateUserMessageTokens(userText: String): Int =
        estimateMessageTokens(ChatMessage(role = "user", content = userText))

    fun contextUsagePercent(historyTokens: Int, limit: Int): Double =
        if (limit <= 0) 0.0 else (historyTokens.toDouble() / limit * 100.0).coerceAtLeast(0.0)
}
