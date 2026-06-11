import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TokenCounterTest {
    @Test
    fun `empty text has zero tokens`() {
        assertEquals(0, TokenCounter.estimateTextTokens(""))
    }

    @Test
    fun `message includes overhead`() {
        val tokens = TokenCounter.estimateMessageTokens(ChatMessage("user", "hi"))
        assertTrue(tokens > TokenCounter.estimateTextTokens("hi"))
    }

    @Test
    fun `history tokens grow with more messages`() {
        val one = listOf(ChatMessage("system", "You are helpful"))
        val two = one + ChatMessage("user", "Hello there, how are you?")
        assertTrue(TokenCounter.estimateMessagesTokens(two) > TokenCounter.estimateMessagesTokens(one))
    }
}
