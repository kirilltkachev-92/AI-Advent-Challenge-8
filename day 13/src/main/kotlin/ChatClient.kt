interface ChatClient {
    fun chat(messages: List<ChatMessage>): ChatResult
}
