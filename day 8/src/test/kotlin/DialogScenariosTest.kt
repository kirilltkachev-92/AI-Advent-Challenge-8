import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DialogScenariosTest {
    @Test
    fun `overflow history exceeds model limit with probe`() {
        val limit = 2_000
        val history = DialogScenarios.buildOverflowHistoryCompact(contextLimit = limit)

        assertTrue(
            DialogScenarios.wouldOverflowAfterProbe(
                history = history,
                contextLimit = limit,
            ),
        )
    }

    @Test
    fun `full overflow history reaches safety target`() = runBlocking {
        val limit = 5_000
        val history = DialogScenarios.buildOverflowHistory(contextLimit = limit)
        val tokens = TokenCounter.estimateMessagesTokens(history)
        val probe = TokenCounter.estimateUserMessageTokens(DialogScenarios.OVERFLOW_PROBE)
        val target = (limit * Config.OVERFLOW_API_SAFETY_MARGIN).toInt() + probe

        assertTrue(tokens >= target - 500, "tokens=$tokens target=$target")
    }
}
