object TokenCounter {
    private const val CHARS_PER_TOKEN = 4.0
    private const val MESSAGE_OVERHEAD_TOKENS = 4

    fun estimateTextTokens(text: String): Int =
        if (text.isEmpty()) 0 else (text.length / CHARS_PER_TOKEN).toInt().coerceAtLeast(1)

    fun estimateMessageTokens(message: ChatMessage): Int =
        MESSAGE_OVERHEAD_TOKENS + estimateTextTokens(message.content)

    fun estimateMessagesTokens(messages: List<ChatMessage>): Int =
        messages.sumOf { estimateMessageTokens(it) }
}
