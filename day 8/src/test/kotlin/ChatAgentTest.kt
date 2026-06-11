import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

private class FakeChatClient(
    private val reply: String = "Ок",
    private val promptTokens: Int = 100,
    private val completionTokens: Int = 20,
) : ChatClient {
    var callCount: Int = 0
        private set

    override fun chat(messages: List<ChatMessage>): ChatResult {
        callCount++
        return ChatResult(
            content = reply,
            latencyMs = 50,
            promptTokens = promptTokens,
            completionTokens = completionTokens,
            totalTokens = promptTokens + completionTokens,
            tokensFromApi = true,
            estimatedCostUsd = 0.001,
        )
    }
}

private class OverflowFakeClient : ChatClient {
    var callCount: Int = 0
        private set

    override fun chat(messages: List<ChatMessage>): ChatResult {
        callCount++
        error("API error 400: This model's maximum context length is 65536 tokens")
    }
}

class ChatAgentTest {
    @Test
    fun `short dialog accumulates token stats`() {
        val client = FakeChatClient()
        val agent = ChatAgent(client = client, defaultContextLimit = 10_000)

        val result = agent.processUserMessage("Привет")
        assertInstanceOf(AgentResult.Success::class.java, result)
        assertEquals(1, client.callCount)
        assertEquals(1, agent.tokenHistory.size)
        assertTrue(agent.totalSessionCostUsd > 0)
    }

    @Test
    fun `local overflow blocks API call`() {
        val client = FakeChatClient()
        val demoLimit = 500
        val agent = ChatAgent(client = client, defaultContextLimit = 64_000)
        agent.setContextLimit(demoLimit)
        agent.seedHistory(DialogScenarios.buildOverflowHistoryCompact(contextLimit = demoLimit))

        val result = agent.processUserMessage(DialogScenarios.OVERFLOW_PROBE)
        assertInstanceOf(AgentResult.Overflow::class.java, result)
        val overflow = result as AgentResult.Overflow
        assertEquals(OverflowSource.LocalGuard, overflow.source)
        assertEquals(0, client.callCount)
    }

    @Test
    fun `api overflow bypasses local guard and surfaces API error`() {
        val client = OverflowFakeClient()
        val agent = ChatAgent(client = client, defaultContextLimit = 64_000)
        agent.seedHistory(DialogScenarios.buildOverflowHistoryCompact(contextLimit = 500))

        val result = agent.processUserMessage(
            DialogScenarios.OVERFLOW_PROBE,
            bypassLocalOverflowGuard = true,
        )
        assertInstanceOf(AgentResult.Overflow::class.java, result)
        val overflow = result as AgentResult.Overflow
        assertEquals(OverflowSource.ApiRejection, overflow.source)
        assertEquals(1, client.callCount)
    }

    @Test
    fun `long dialog increases cumulative cost`() {
        val client = FakeChatClient(promptTokens = 500, completionTokens = 100)
        val agent = ChatAgent(client = client, defaultContextLimit = 100_000)

        repeat(3) { index ->
            agent.processUserMessage("Сообщение ${index + 1}")
        }

        assertEquals(3, agent.tokenHistory.size)
        val costs = agent.tokenHistory.map { it.cumulativeCostUsd }
        assertTrue(costs[2] > costs[1])
        assertTrue(costs[1] > costs[0])
    }
}
