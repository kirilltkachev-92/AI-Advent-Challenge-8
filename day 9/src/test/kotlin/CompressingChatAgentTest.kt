import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

private class FakeChatClient(
    private val reply: String = "Понял.",
) : ChatClient {
    var callCount = 0
        private set

    override fun chat(messages: List<ChatMessage>): ChatResult {
        callCount++
        val isSummary = messages.any {
            it.role == "system" && it.content.contains("сжимаешь")
        }
        val content = if (isSummary) {
            "Резюме диалога: факты сохранены."
        } else {
            reply
        }
        return ChatResult(
            content = content,
            latencyMs = 5,
            promptTokens = TokenCounter.estimateMessagesTokens(messages),
            completionTokens = TokenCounter.estimateTextTokens(content),
            totalTokens = 50,
            tokensFromApi = false,
            estimatedCostUsd = 0.001,
        )
    }
}

class CompressingChatAgentTest {
    @Test
    fun `processes message and tracks stats`() {
        val agent = CompressingChatAgent(FakeChatClient(), recentMessageCount = 6, compressBatchSize = 100)
        val result = agent.processUserMessage("Привет")
        assertInstanceOf(AgentResult.Success::class.java, result)
        assertEquals(1, agent.stats.size)
        assertEquals(2, agent.turns.size)
    }

    @Test
    fun `compression reduces prompt growth on long dialog`() {
        val plain = PlainChatAgent(FakeChatClient())
        val compressed = CompressingChatAgent(
            client = FakeChatClient(),
            recentMessageCount = 4,
            compressBatchSize = 6,
        )

        repeat(12) { i ->
            plain.processUserMessage("Сообщение $i с контекстом проект Kotlin Compose агент.")
            compressed.processUserMessage("Сообщение $i с контекстом проект Kotlin Compose агент.")
        }

        assertTrue(compressed.totalPromptTokens < plain.totalPromptTokens)
        assertTrue(compressed.compressionCount >= 1)
    }
}
