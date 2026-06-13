import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

private class FakeChatClient(
    private val reply: String = "Принято, зафиксировал.",
) : ChatClient {
    var callCount = 0
        private set

    override fun chat(messages: List<ChatMessage>): ChatResult {
        callCount++
        val isFactsExtraction = messages.any {
            it.role == "system" && it.content.contains("извлекаешь ключевые факты")
        }
        val content = when {
            isFactsExtraction -> """{"проект": "Orion", "бюджет": "2.4 млн"}"""
            messages.any { it.content.contains("checkpoint") } -> "Checkpoint noted."
            else -> reply
        }
        return ChatResult(
            content = content,
            latencyMs = 3,
            promptTokens = TokenCounter.estimateMessagesTokens(messages),
            completionTokens = TokenCounter.estimateTextTokens(content),
            totalTokens = 40,
            tokensFromApi = false,
            estimatedCostUsd = 0.001,
        )
    }
}

class SlidingWindowAgentTest {
    @Test
    fun `keeps only window messages in context`() {
        val agent = SlidingWindowAgent(FakeChatClient(), windowSize = 4)
        repeat(6) { i ->
            agent.processUserMessage("Сообщение $i про Orion бюджет 2.4 млн.")
        }
        assertEquals(6, agent.turns.count { it is AgentTurn.User })
        assertTrue(agent.droppedMessageCount > 0)
    }

    @Test
    fun `processes first message`() {
        val agent = SlidingWindowAgent(FakeChatClient())
        val result = agent.processUserMessage("Привет")
        assertInstanceOf(AgentResult.Success::class.java, result)
        assertEquals(1, agent.stats.size)
    }
}

class FactsAgentTest {
    @Test
    fun `updates facts after user message`() {
        val agent = FactsAgent(FakeChatClient(), windowSize = 4)
        agent.processUserMessage("Проект Orion, бюджет 2.4 млн рублей.")
        assertTrue(agent.factsSnapshot.isNotEmpty())
        assertTrue(agent.factsExtractionCalls >= 1)
    }

    @Test
    fun `facts survive beyond window`() {
        val agent = FactsAgent(FakeChatClient(), windowSize = 2)
        agent.processUserMessage("Проект Orion, бюджет 2.4 млн.")
        repeat(5) { i ->
            agent.processUserMessage("Общий вопрос $i про REST API.")
        }
        assertTrue(agent.factsSnapshot.isNotEmpty())
    }
}

class BranchingAgentTest {
    @Test
    fun `creates branches from checkpoint`() {
        val agent = BranchingAgent(FakeChatClient())
        agent.processUserMessage("Базовое ТЗ Orion.")
        agent.processUserMessage("Бюджет 2.4 млн.")
        agent.createCheckpoint()
        val branchA = agent.createBranch("ветка A")
        agent.processUserMessage("Push-уведомления.")
        agent.createBranch("ветка B")
        agent.switchBranch(branchA.id)
        agent.processUserMessage("Биометрия.")

        assertEquals(2, agent.branchList.size - 1) // minus main
        assertTrue(agent.hasCheckpoint)
        assertEquals(2, branchA.turnCount)
    }

    @Test
    fun `shared prefix preserved across branches`() {
        val agent = BranchingAgent(FakeChatClient())
        agent.processUserMessage("Шаг 1")
        agent.processUserMessage("Шаг 2")
        agent.createCheckpoint()
        agent.createBranch("alt")
        val history = agent.activeHistory()
        assertEquals(4, history.size) // 2 user + 2 assistant in shared prefix
    }
}

class CompareScenarioTest {
    @Test
    fun `linear scenario has expected length`() {
        assertEquals(20, CompareScenario.linearMessages.size)
    }
}
