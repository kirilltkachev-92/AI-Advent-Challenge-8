enum class ScenarioPhase(val label: String, val description: String) {
    SETUP(
        label = "Старт",
        description = "Название, бюджет, студия, гости — ключевые факты",
    ),
    DISCUSS(
        label = "Обсуждение",
        description = "Общие вопросы — вытесняют ранние факты из окна",
    ),
    BRANCH_A(
        label = "Промо",
        description = "Стратегия продвижения и коллаборации",
    ),
    BRANCH_B(
        label = "Контент",
        description = "Альтернативные форматы и темы выпусков",
    ),
    VERIFY(
        label = "Проверка",
        description = "Вопросы по фактам из начала и веточным решениям",
    ),
}

data class ScenarioMessage(
    val phase: ScenarioPhase,
    val text: String,
    val branchOnly: String? = null,
)

data class StrategyTurnResult(
    val strategy: ContextStrategy,
    val turnIndex: Int,
    val phase: ScenarioPhase,
    val userMessage: String,
    val reply: String,
    val promptTokens: Int,
    val completionTokens: Int,
    val contextTokensEstimated: Int,
    val extraTokens: Int = 0,
    val strategyMeta: String? = null,
    val branchName: String? = null,
)

data class StrategyRunResult(
    val strategy: ContextStrategy,
    val turns: List<StrategyTurnResult>,
    val totalPromptTokens: Int,
    val totalExtraTokens: Int,
    val totalCostUsd: Double,
    val facts: Map<String, String>? = null,
    val branchTurns: Map<String, List<StrategyTurnResult>>? = null,
) {
    val verifyTurns: List<StrategyTurnResult> =
        turns.filter { it.phase == ScenarioPhase.VERIFY }

    val setupTurns: List<StrategyTurnResult> =
        turns.filter { it.phase == ScenarioPhase.SETUP }
}

data class CompareAllResult(
    val sliding: StrategyRunResult,
    val facts: StrategyRunResult,
    val branching: StrategyRunResult,
) {
    val strategies: List<StrategyRunResult> = listOf(sliding, facts, branching)
}

object CompareScenario {
    const val SCENARIO_TITLE = "Запуск подкаста «Код и кофе»"

    private val setupMessages = listOf(
        "Запускаем подкаст «Код и кофе» — публикуем на Spotify и YouTube.",
        "Формат: интервью 45–60 минут, выход раз в две недели. Первая серия — 1 октября 2026.",
        "Ведущий — Дмитрий, соавтор — Анна. Аудитория — разработчики 20–35 лет.",
        "Стартовый бюджет — 180 000 ₽: микрофоны Shure MV7, монтаж, обложка.",
        "Студия записи — «ЗвукБокс» на Таганке, бронь по средам с 19:00.",
        "Первый гость подтверждён — lead-разработчик Ktor, тема «корутины в продакшене». " +
            "Правило: не обсуждаем политику и не даём инвестиционных советов.",
    )

    /** Общие вопросы между фактами и ветками — намеренно вытесняют ранний контекст из окна. */
    private val discussMessages = listOf(
        "Нужен ли отдельный Telegram-канал для анонсов выпусков?",
        "Предложи три варианта названия для первого эпизода.",
        "Какой длины делать intro-джингл — 10, 20 или 30 секунд?",
        "Какие пять хэштегов использовать при публикации в соцсетях?",
        "Стоит ли выкладывать полные текстовые транскрипты каждого выпуска?",
    )

    private val branchAMessages = listOf(
        "Фокус на Habr и Twitter/X — набросай план анонсов на первый месяц.",
        "Хотим коллаборацию с двумя техно-YouTube каналами — какие условия им предложить?",
        "Розыгрыш наушников среди первых 100 подписчиков — опиши механику.",
    )

    private val branchBMessages = listOf(
        "Альтернатива: сольный выпуск Дмитрия про AI-инструменты для разработчиков.",
        "Формат «вопрос недели» от слушателей — как встроить в структуру эпизода?",
        "Пригласить UX-дизайнера обсудить тренды мобильных интерфейсов в 2026.",
    )

    private val verifyMessages = listOf(
        "Как называется подкаст и на каких площадках публикуем? Назови точно.",
        "Дата первой серии, длительность эпизода и частота выпусков?",
        "Какой бюджет, какая студия и когда запись? Укажи цифры и адрес.",
        "Кто ведущие, кто первый гость и какие темы/ограничения контента?",
        "Какие решения мы приняли в дополнительных сообщениях этой ветки?",
        "Итог: воспроизведи все ключевые факты из начала диалога списком без пропусков.",
    )

    /** Линейный сценарий: старт → обсуждение → промо → проверка. */
    val linearMessages: List<ScenarioMessage> = buildList {
        setupMessages.forEach { add(ScenarioMessage(ScenarioPhase.SETUP, it)) }
        discussMessages.forEach { add(ScenarioMessage(ScenarioPhase.DISCUSS, it)) }
        branchAMessages.forEach { add(ScenarioMessage(ScenarioPhase.BRANCH_A, it)) }
        verifyMessages.forEach { add(ScenarioMessage(ScenarioPhase.VERIFY, it)) }
    }

    val setupCount: Int = setupMessages.size
    val discussCount: Int = discussMessages.size
    val checkpointAfterTurn: Int = setupMessages.size + discussMessages.size

    suspend fun runAll(
        client: ChatClient,
        windowSize: Int = Config.windowSize(),
        onProgress: suspend (ContextStrategy, Int, Int) -> Unit = { _, _, _ -> },
        onTurn: suspend (StrategyTurnResult) -> Unit = { },
    ): CompareAllResult {
        val sliding = runSliding(client, windowSize, onProgress, onTurn)
        val facts = runFacts(client, windowSize, onProgress, onTurn)
        val branching = runBranching(client, windowSize, onProgress, onTurn)
        return CompareAllResult(sliding, facts, branching)
    }

    suspend fun runSliding(
        client: ChatClient,
        windowSize: Int = Config.windowSize(),
        onProgress: suspend (ContextStrategy, Int, Int) -> Unit = { _, _, _ -> },
        onTurn: suspend (StrategyTurnResult) -> Unit = { },
    ): StrategyRunResult = runLinear(
        agent = SlidingWindowAgent(client, windowSize = windowSize),
        messages = linearMessages,
        onProgress = onProgress,
        onTurn = onTurn,
    )

    suspend fun runFacts(
        client: ChatClient,
        windowSize: Int = Config.windowSize(),
        onProgress: suspend (ContextStrategy, Int, Int) -> Unit = { _, _, _ -> },
        onTurn: suspend (StrategyTurnResult) -> Unit = { },
    ): StrategyRunResult {
        val agent = FactsAgent(client, windowSize = windowSize)
        val result = runLinear(agent, linearMessages, onProgress, onTurn)
        return result.copy(facts = agent.factsSnapshot)
    }

    suspend fun runBranching(
        client: ChatClient,
        windowSize: Int = Config.windowSize(),
        onProgress: suspend (ContextStrategy, Int, Int) -> Unit = { _, _, _ -> },
        onTurn: suspend (StrategyTurnResult) -> Unit = { },
    ): StrategyRunResult {
        val agent = BranchingAgent(client, windowSize = windowSize)
        val allTurns = mutableListOf<StrategyTurnResult>()
        val branchResults = linkedMapOf<String, List<StrategyTurnResult>>()
        val sharedMessages = setupMessages + discussMessages

        sharedMessages.forEachIndexed { index, text ->
            val phase = if (index < setupMessages.size) ScenarioPhase.SETUP else ScenarioPhase.DISCUSS
            onProgress(ContextStrategy.BRANCHING, index + 1, totalBranchingSteps())
            val turn = processAgentTurn(agent, phase, text, index + 1)
            allTurns += turn
            onTurn(turn)
        }

        agent.createCheckpoint("после старта и обсуждения")
        agent.createBranch("Промо")
        agent.createBranch("Контент")

        val branchA = agent.branchList.first { it.name == "Промо" }
        val branchB = agent.branchList.first { it.name == "Контент" }

        val turnsA = mutableListOf<StrategyTurnResult>()
        branchAMessages.forEachIndexed { index, text ->
            val step = sharedMessages.size + index + 1
            onProgress(ContextStrategy.BRANCHING, step, totalBranchingSteps())
            agent.switchBranch(branchA.id)
            val turn = processAgentTurn(agent, ScenarioPhase.BRANCH_A, text, step, branchA.name)
            turnsA += turn
            allTurns += turn
            onTurn(turn)
        }

        val turnsB = mutableListOf<StrategyTurnResult>()
        branchBMessages.forEachIndexed { index, text ->
            val step = sharedMessages.size + branchAMessages.size + index + 1
            onProgress(ContextStrategy.BRANCHING, step, totalBranchingSteps())
            agent.switchBranch(branchB.id)
            val turn = processAgentTurn(agent, ScenarioPhase.BRANCH_B, text, step, branchB.name)
            turnsB += turn
            allTurns += turn
            onTurn(turn)
        }

        branchResults[branchA.name] = turnsA
        branchResults[branchB.name] = turnsB

        verifyMessages.forEachIndexed { index, text ->
            val step = sharedMessages.size + branchAMessages.size + branchBMessages.size + index + 1
            onProgress(ContextStrategy.BRANCHING, step, totalBranchingSteps())

            agent.switchBranch(branchA.id)
            val turnA = processAgentTurn(
                agent,
                ScenarioPhase.VERIFY,
                text,
                step,
                branchA.name,
            )
            allTurns += turnA
            onTurn(turnA)

            agent.switchBranch(branchB.id)
            val turnB = processAgentTurn(
                agent,
                ScenarioPhase.VERIFY,
                text,
                step,
                branchB.name,
            )
            allTurns += turnB
            onTurn(turnB)
        }

        return StrategyRunResult(
            strategy = ContextStrategy.BRANCHING,
            turns = allTurns,
            totalPromptTokens = agent.totalPromptTokens,
            totalExtraTokens = 0,
            totalCostUsd = agent.totalCostUsd,
            branchTurns = branchResults,
        )
    }

    private fun totalBranchingSteps(): Int =
        setupMessages.size + discussMessages.size +
            branchAMessages.size + branchBMessages.size + verifyMessages.size

    private suspend fun runLinear(
        agent: ContextAgent,
        messages: List<ScenarioMessage>,
        onProgress: suspend (ContextStrategy, Int, Int) -> Unit,
        onTurn: suspend (StrategyTurnResult) -> Unit,
    ): StrategyRunResult {
        val turns = mutableListOf<StrategyTurnResult>()
        messages.forEachIndexed { index, step ->
            onProgress(agent.strategy, index + 1, messages.size)
            val turn = processAgentTurn(agent, step.phase, step.text, index + 1)
            turns += turn
            onTurn(turn)
        }
        val extra = if (agent is FactsAgent) {
            agent.stats.sumOf { it.extraPromptTokens }
        } else {
            0
        }
        return StrategyRunResult(
            strategy = agent.strategy,
            turns = turns,
            totalPromptTokens = agent.totalPromptTokens,
            totalExtraTokens = extra,
            totalCostUsd = agent.totalCostUsd,
            facts = (agent as? FactsAgent)?.factsSnapshot,
        )
    }

    private fun processAgentTurn(
        agent: ContextAgent,
        phase: ScenarioPhase,
        text: String,
        turnIndex: Int,
        branchName: String? = null,
    ): StrategyTurnResult {
        val result = agent.processUserMessage(text)
        val success = result as? AgentResult.Success
            ?: error("${agent.strategy} error: ${(result as AgentResult.Error).message}")
        return StrategyTurnResult(
            strategy = agent.strategy,
            turnIndex = turnIndex,
            phase = phase,
            userMessage = text,
            reply = success.assistantMessage,
            promptTokens = success.stats.promptTokens,
            completionTokens = success.stats.completionTokens,
            contextTokensEstimated = success.stats.contextTokensEstimated,
            extraTokens = success.stats.extraPromptTokens,
            strategyMeta = success.stats.strategyMeta,
            branchName = branchName,
        )
    }
}
