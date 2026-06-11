import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class OpenSourceCodeLoaderTest {
    @Test
    fun `overflow history is built from oss java sources`() = runBlocking {
        val limit = 8_000
        val history = OpenSourceCodeLoader.buildOverflowHistory(
            contextLimit = limit,
            systemPrompt = "system",
            overflowProbe = DialogScenarios.OVERFLOW_PROBE,
            safetyMargin = 1.05,
        )

        val body = history.joinToString("\n") { it.content }
        assertTrue(body.contains("StringUtil.java") || body.contains("FileUtil.java"))
        assertTrue(body.contains("```java"))
        assertTrue(TokenCounter.estimateMessagesTokens(history) >= (limit * 1.05).toInt())
    }

    @Test
    fun `compact overflow uses oss code`() {
        val history = OpenSourceCodeLoader.buildOverflowHistoryCompact(
            contextLimit = 500,
            overflowProbe = DialogScenarios.OVERFLOW_PROBE,
            systemPrompt = "system",
        )

        assertTrue(history.any { "StringUtil.java" in it.content || "FileUtil.java" in it.content })
        assertTrue(
            DialogScenarios.wouldOverflowAfterProbe(
                history = history,
                contextLimit = 500,
            ),
        )
    }
}
