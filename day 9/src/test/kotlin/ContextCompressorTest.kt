import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

private class RecordingClient : ChatClient {
    val calls = mutableListOf<List<ChatMessage>>()
    var summaryReply: String = "Резюме: Кирилл, Kotlin-разработчик, проект Compose."
    var chatReply: String = "Ок"

    override fun chat(messages: List<ChatMessage>): ChatResult {
        calls += messages
        val isSummary = messages.any { it.content.contains("Сожми") || it.content.contains("Добавь в резюме") }
        val content = if (isSummary) summaryReply else chatReply
        val promptTokens = TokenCounter.estimateMessagesTokens(messages)
        val completionTokens = TokenCounter.estimateTextTokens(content)
        return ChatResult(
            content = content,
            latencyMs = 10,
            promptTokens = promptTokens,
            completionTokens = completionTokens,
            totalTokens = promptTokens + completionTokens,
            tokensFromApi = false,
            estimatedCostUsd = 0.0,
        )
    }
}

class ContextCompressorTest {
    @Test
    fun `keeps recent messages in api context`() {
        val client = RecordingClient()
        val compressor = ContextCompressor(client, recentMessageCount = 4, compressBatchSize = 100)

        repeat(3) { i ->
            compressor.appendTurn("user-$i", "assistant-$i")
        }

        val api = compressor.buildApiMessages("new")
        val roles = api.map { it.role }
        assertTrue(roles.contains("system"))
        assertEquals("user-2", api.filter { it.role == "user" }.dropLast(1).last().content)
        assertEquals("new", api.last().content)
    }

    @Test
    fun `compresses batch when threshold exceeded`() {
        val client = RecordingClient()
        val compressor = ContextCompressor(
            client = client,
            recentMessageCount = 4,
            compressBatchSize = 6,
        )

        repeat(8) { i ->
            compressor.appendTurn("u$i", "a$i")
        }

        assertEquals(1, compressor.compressionCount)
        assertNotNull(compressor.summaryText)
        assertEquals(10, compressor.pendingCount)
    }

    @Test
    fun `api context uses summary instead of old messages`() {
        val client = RecordingClient()
        val compressor = ContextCompressor(
            client = client,
            recentMessageCount = 2,
            compressBatchSize = 4,
        )

        repeat(5) { i ->
            compressor.appendTurn("old-user-$i", "old-assistant-$i")
        }

        val api = compressor.buildApiMessages()
        assertTrue(api.any { it.content.contains("Краткое содержание") })
        assertTrue(api.none { it.content == "old-user-0" })
    }

    @Test
    fun `compressed context has fewer estimated tokens than full history`() {
        val client = RecordingClient()
        val compressor = ContextCompressor(
            client = client,
            recentMessageCount = 4,
            compressBatchSize = 6,
        )

        repeat(10) { i ->
            compressor.appendTurn(
                "Сообщение номер $i с дополнительным текстом для увеличения объёма.",
                "Ответ номер $i с подробным описанием контекста диалога.",
            )
        }

        val compressedTokens = compressor.estimateContextTokens()
        val fullTokens = compressor.estimateFullHistoryTokens()
        assertTrue(compressedTokens < fullTokens)
    }
}
