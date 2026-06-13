import java.util.UUID

data class DialogBranch(
    val id: String,
    val name: String,
    val messages: MutableList<ChatMessage> = mutableListOf(),
) {
    val turnCount: Int get() = messages.count { it.role == "user" }
}

class BranchingAgent(
    private val client: ChatClient,
    private val systemPrompt: String = Config.SYSTEM_PROMPT,
    private val windowSize: Int = Config.windowSize(),
) : ContextAgent {
    override val strategy = ContextStrategy.BRANCHING

    private val sharedPrefix = mutableListOf<ChatMessage>()
    private val branches = linkedMapOf<String, DialogBranch>()
    private var activeBranchId: String? = null
    private var checkpointTurnIndex: Int? = null
    private val displayTurns = mutableListOf<AgentTurn>()
    private val turnStats = mutableListOf<TurnStats>()

    override val turns: List<AgentTurn> get() = displayTurns.toList()
    override val stats: List<TurnStats> get() = turnStats.toList()
    override val totalPromptTokens: Int get() = turnStats.sumOf { it.promptTokens }
    override val totalCostUsd: Double get() = turnStats.sumOf { it.turnCostUsd }

    val branchList: List<DialogBranch> get() = branches.values.toList()
    val activeBranch: DialogBranch?
        get() = activeBranchId?.let { branches[it] }
    val hasCheckpoint: Boolean get() = checkpointTurnIndex != null
    val checkpointIndex: Int? get() = checkpointTurnIndex

    override fun reset() {
        sharedPrefix.clear()
        branches.clear()
        activeBranchId = null
        checkpointTurnIndex = null
        displayTurns.clear()
        turnStats.clear()
        ensureMainBranch()
    }

    init {
        ensureMainBranch()
    }

    private fun ensureMainBranch() {
        if (branches.isEmpty()) {
            val main = DialogBranch(id = "main", name = "main")
            branches[main.id] = main
            activeBranchId = main.id
        }
    }

    fun createCheckpoint(label: String = "checkpoint"): Boolean {
        if (hasCheckpoint) return false
        checkpointTurnIndex = sharedPrefix.count { it.role == "user" } +
            (activeBranch?.turnCount ?: 0)
        displayTurns += AgentTurn.Checkpoint(label, checkpointTurnIndex ?: 0)
        return true
    }

    fun createBranch(name: String): DialogBranch {
        require(hasCheckpoint) { "Сначала создайте checkpoint" }
        val branch = DialogBranch(
            id = UUID.randomUUID().toString().take(8),
            name = name,
        )
        branches[branch.id] = branch
        activeBranchId = branch.id
        displayTurns += AgentTurn.SystemNote("ветка «$name» создана от checkpoint")
        return branch
    }

    fun switchBranch(branchId: String): Boolean {
        if (branchId !in branches) return false
        activeBranchId = branchId
        displayTurns += AgentTurn.SystemNote("переключено на ветку «${branches[branchId]!!.name}»")
        return true
    }

    fun activeHistory(): List<ChatMessage> {
        val branch = activeBranch ?: error("Нет активной ветки")
        return sharedPrefix + branch.messages
    }

    fun buildApiMessages(userText: String): List<ChatMessage> {
        val history = activeHistory()
        val window = takeRecentWindow(history, windowSize)
        return buildList {
            add(ChatMessage(role = "system", content = systemPrompt))
            val branch = activeBranch
            if (branch != null && branch.name != "main") {
                add(
                    ChatMessage(
                        role = "system",
                        content = "[Активная ветка: ${branch.name}]",
                    ),
                )
            }
            addAll(window)
            add(ChatMessage(role = "user", content = userText))
        }
    }

    override fun processUserMessage(userText: String): AgentResult {
        val trimmed = userText.trim()
        require(trimmed.isNotEmpty()) { "Сообщение не может быть пустым" }

        val branch = activeBranch ?: error("Нет активной ветки")
        val apiMessages = buildApiMessages(trimmed)
        val contextTokens = TokenCounter.estimateMessagesTokens(apiMessages)

        return try {
            val result = client.chat(apiMessages)

            if (!hasCheckpoint) {
                sharedPrefix += ChatMessage(role = "user", content = trimmed)
                sharedPrefix += ChatMessage(role = "assistant", content = result.content)
            } else {
                branch.messages += ChatMessage(role = "user", content = trimmed)
                branch.messages += ChatMessage(role = "assistant", content = result.content)
            }

            displayTurns += AgentTurn.User(trimmed)
            displayTurns += AgentTurn.Assistant(result.content)

            val stats = TurnStats(
                turnIndex = displayTurns.count { it is AgentTurn.User },
                promptTokens = result.promptTokens,
                completionTokens = result.completionTokens,
                contextTokensEstimated = contextTokens,
                turnCostUsd = result.estimatedCostUsd,
                latencyMs = result.latencyMs,
                strategyMeta = "ветка: ${branch.name}",
            )
            turnStats += stats

            AgentResult.Success(trimmed, result.content, stats)
        } catch (e: Exception) {
            AgentResult.Error(trimmed, e.message ?: e.toString())
        }
    }

    /** Прогон сообщения в конкретной ветке без смены активной (для сравнения). */
    fun processInBranch(branchId: String, userText: String): AgentResult {
        val previous = activeBranchId
        switchBranch(branchId)
        val result = processUserMessage(userText)
        previous?.let { switchBranch(it) }
        return result
    }
}
