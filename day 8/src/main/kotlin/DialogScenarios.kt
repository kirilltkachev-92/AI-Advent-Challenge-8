enum class DialogScenario(val label: String, val description: String) {
    SHORT(
        label = "Короткий диалог",
        description = "2 коротких сообщения — мало токенов, низкая стоимость.",
    ),
    LONG(
        label = "Длинный диалог",
        description = "12 разных вопросов подряд — токены и стоимость растут с каждым ходом.",
    ),
    OVERFLOW(
        label = "Переполнение",
        description = "История из OSS-кода IntelliJ (~1M токенов) → вызов deepseek-v4-flash.",
    ),
}

object DialogScenarios {
    const val OVERFLOW_PROBE =
        "Проверка переполнения: это сообщение отправляется в DeepSeek при переполненном контексте."

    val shortMessages: List<String> = listOf(
        "Привет! Как дела?",
        "Сколько будет 2 + 2? Ответь одним предложением.",
    )

    val longMessages: List<String> = listOf(
        "Расскажи кратко, чем корутины в Kotlin отличаются от потоков в Java.",
        "Объясни принцип работы garbage collector в JVM простыми словами.",
        "Что такое REST API и какие HTTP-методы для чего используют?",
        "В чём разница между SQL и NoSQL базами данных? Приведи примеры.",
        "Как работает HTTPS и зачем нужны SSL-сертификаты?",
        "Что такое Docker и чем контейнер отличается от виртуальной машины?",
        "Объясни паттерн Observer и где его применяют в реальных проектах.",
        "Как устроена нейросеть на высоком уровне — без формул, в 4 предложениях.",
        "Какие есть способы оптимизации SQL-запросов? Назови три.",
        "Что такое CI/CD и как выглядит типичный пайплайн?",
        "Объясни разницу между синхронным и асинхронным программированием на примере.",
        "Какие метрики важно мониторить у backend-сервиса в продакшене?",
    )

    data class OverflowBuildProgress(
        val pairsAdded: Int,
        val estimatedTokens: Int,
        val targetTokens: Int,
    )

    suspend fun buildOverflowHistory(
        contextLimit: Int,
        systemPrompt: String = Config.SYSTEM_PROMPT,
        overflowProbe: String = OVERFLOW_PROBE,
        safetyMargin: Double = Config.OVERFLOW_API_SAFETY_MARGIN,
        onProgress: (suspend (OverflowBuildProgress) -> Unit)? = null,
    ): List<ChatMessage> =
        OpenSourceCodeLoader.buildOverflowHistory(
            contextLimit = contextLimit,
            systemPrompt = systemPrompt,
            overflowProbe = overflowProbe,
            safetyMargin = safetyMargin,
            onProgress = onProgress,
        )

    fun buildOverflowHistoryCompact(
        contextLimit: Int,
        overflowProbe: String = OVERFLOW_PROBE,
        systemPrompt: String = Config.SYSTEM_PROMPT,
    ): List<ChatMessage> =
        OpenSourceCodeLoader.buildOverflowHistoryCompact(
            contextLimit = contextLimit,
            overflowProbe = overflowProbe,
            systemPrompt = systemPrompt,
        )

    fun wouldOverflowAfterProbe(
        history: List<ChatMessage>,
        contextLimit: Int,
        overflowProbe: String = OVERFLOW_PROBE,
    ): Boolean =
        TokenCounter.estimateMessagesTokens(history + ChatMessage(role = "user", content = overflowProbe)) >
            contextLimit
}
