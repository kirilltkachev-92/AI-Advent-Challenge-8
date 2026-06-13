enum class ScenarioPhase(val label: String, val description: String) {
    FACTS(
        label = "Факты",
        description = "Точные данные в начале — уйдут в summary при сжатии",
    ),
    VOLUME(
        label = "Объём",
        description = "Общие вопросы — наращивают историю и запускают сжатие",
    ),
    VERIFY(
        label = "Проверка",
        description = "Вопросы по фактам из начала — видно, сохранилось ли качество",
    ),
}

data class ScenarioMessage(
    val phase: ScenarioPhase,
    val text: String,
)

data class TurnComparison(
    val turnIndex: Int,
    val phase: ScenarioPhase,
    val userMessage: String,
    val plainReply: String,
    val compressedReply: String,
    val plainPromptTokens: Int,
    val compressedPromptTokens: Int,
    val tokensSaved: Int,
    val plainCostUsd: Double,
    val compressedCostUsd: Double,
    val compressionCountAfter: Int,
    val compressionJustHappened: Boolean,
)

data class CompareResult(
    val turns: List<TurnComparison>,
    val plainTotalPromptTokens: Int,
    val compressedTotalPromptTokens: Int,
    val plainTotalCostUsd: Double,
    val compressedTotalCostUsd: Double,
    val compressionEvents: Int,
    val finalSummary: String?,
) {
    val totalTokensSaved: Int = plainTotalPromptTokens - compressedTotalPromptTokens
    val savingsPercent: Double =
        if (plainTotalPromptTokens <= 0) 0.0
        else totalTokensSaved.toDouble() / plainTotalPromptTokens * 100.0

    val verifyTurns: List<TurnComparison> =
        turns.filter { it.phase == ScenarioPhase.VERIFY }
}

object CompareScenario {
    private val factMessages = listOf(
        "Запомни точно: меня зовут Алексей, я работаю в компании «Небула».",
        "Запомни: мы делаем мобильное приложение для учёта расходов, кодовое имя проекта — Orion.",
        "Запомни: бюджет проекта — 2,4 миллиона рублей, дедлайн — 15 сентября 2026 года.",
        "Запомни стек: Kotlin, Compose Multiplatform, Ktor, PostgreSQL.",
        "Запомни: тимлид — Мария, она требует покрытие тестами не менее 80%.",
        "Запомни: главный риск проекта — интеграция с банковским API Сбербанка.",
    )

    private val volumeMessages = listOf(
        "Кратко объясни, чем корутины Kotlin отличаются от потоков Java.",
        "Что такое REST API? Ответь в 2–3 предложениях.",
        "Чем Docker-контейнер отличается от виртуальной машины?",
        "Зачем нужны индексы в PostgreSQL?",
        "Опиши типичный CI/CD-пайплайн в четырёх шагах.",
        "Как работает garbage collector в JVM — очень кратко.",
        "Что такое паттерн Observer и где его применяют?",
        "Объясни, как работает HTTPS, без технических деталей.",
        "Что такое SOLID? Кратко по каждой букве.",
        "В чём разница между val и var в Kotlin?",
        "Что такое дженерики и зачем они нужны?",
        "Зачем в проектах используют Redis?",
        "Что такое микросервисная архитектура — в двух предложениях.",
        "Объясни CAP-теорему простыми словами.",
        "Что такое JWT и зачем он нужен в API?",
        "Чем TCP отличается от UDP?",
        "Что делает load balancer?",
        "Как работает DNS — очень кратко?",
        "Что такое ORM?",
        "Зачем разработчики используют git rebase?",
        "Что такое WebSocket и когда его применяют?",
        "В чём разница между монолитом и микросервисами?",
        "Что такое rate limiting?",
        "Как работает балансировка round-robin?",
        "Что значит идемпотентность HTTP-запроса?",
        "Зачем нужны миграции базы данных?",
        "Что такое circuit breaker в распределённых системах?",
        "Чем отличается синхронный I/O от асинхронного?",
        "Что такое CDN?",
        "Как работает OAuth 2.0 на высоком уровне?",
        "Что такое горизонтальное и вертикальное масштабирование?",
        "Зачем используют Apache Kafka?",
        "Что такое blue-green deployment?",
        "Чем unit-тесты отличаются от integration-тестов?",
    )

    private val verifyMessages = listOf(
        "Как меня зовут и в какой компании я работаю? Назови точно.",
        "Какое кодовое имя проекта и какой стек технологий? Перечисли всё.",
        "Какой бюджет проекта и какой дедлайн? Укажи точные числа и дату.",
        "Кто тимлид и какой минимальный процент покрытия тестами требуется?",
        "Какой главный риск проекта?",
        "Для чего приложение Orion и что оно делает?",
        "Назови бюджет, дедлайн и главный риск в одном ответе.",
        "Перечисли имя, компанию, тимлида и стек технологий.",
        "Сколько процентов покрытия тестами требует Мария?",
        "Итоговая проверка: воспроизведи все 6 фактов из начала диалога без пропусков.",
    )

    /**
     * Сценарий из 50 ходов: 6 фактов + 34 объём + 10 проверок.
     * При настройках по умолчанию (окно 6, батч 10) сжатие срабатывает ~4 раза.
     */
    val messages: List<ScenarioMessage> = buildList {
        factMessages.forEach { add(ScenarioMessage(ScenarioPhase.FACTS, it)) }
        volumeMessages.forEach { add(ScenarioMessage(ScenarioPhase.VOLUME, it)) }
        verifyMessages.forEach { add(ScenarioMessage(ScenarioPhase.VERIFY, it)) }
    }

    suspend fun run(
        client: ChatClient,
        scenario: List<ScenarioMessage> = messages,
        recentMessageCount: Int = Config.recentMessageCount(),
        compressBatchSize: Int = Config.compressBatchSize(),
        onProgress: suspend (Int, Int, ScenarioPhase) -> Unit = { _, _, _ -> },
        onTurn: suspend (TurnComparison, CompressingChatAgent) -> Unit = { _, _ -> },
    ): CompareResult {
        val plain = PlainChatAgent(client)
        val compressed = CompressingChatAgent(
            client = client,
            recentMessageCount = recentMessageCount,
            compressBatchSize = compressBatchSize,
        )

        val comparisons = mutableListOf<TurnComparison>()

        scenario.forEachIndexed { index, step ->
            onProgress(index + 1, scenario.size, step.phase)

            val compressionsBefore = compressed.compressionCount

            val plainResult = plain.processUserMessage(step.text)
            val compressedResult = compressed.processUserMessage(step.text)

            val plainSuccess = plainResult as? AgentResult.Success
                ?: error("Plain agent error: ${(plainResult as AgentResult.Error).message}")
            val compressedSuccess = compressedResult as? AgentResult.Success
                ?: error("Compressed agent error: ${(compressedResult as AgentResult.Error).message}")

            val compressionsAfter = compressed.compressionCount

            val turn = TurnComparison(
                turnIndex = index + 1,
                phase = step.phase,
                userMessage = step.text,
                plainReply = plainSuccess.assistantMessage,
                compressedReply = compressedSuccess.assistantMessage,
                plainPromptTokens = plainSuccess.stats.promptTokens,
                compressedPromptTokens = compressedSuccess.stats.promptTokens,
                tokensSaved = plainSuccess.stats.promptTokens - compressedSuccess.stats.promptTokens,
                plainCostUsd = plainSuccess.stats.turnCostUsd,
                compressedCostUsd = compressedSuccess.stats.turnCostUsd,
                compressionCountAfter = compressionsAfter,
                compressionJustHappened = compressionsAfter > compressionsBefore,
            )
            comparisons += turn
            onTurn(turn, compressed)
        }

        return CompareResult(
            turns = comparisons,
            plainTotalPromptTokens = plain.totalPromptTokens,
            compressedTotalPromptTokens = compressed.totalPromptTokens,
            plainTotalCostUsd = plain.totalCostUsd,
            compressedTotalCostUsd = compressed.totalCostUsd,
            compressionEvents = compressed.compressionCount,
            finalSummary = compressed.summary,
        )
    }
}
