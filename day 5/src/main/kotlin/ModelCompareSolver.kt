data class TierResult(
    val tier: ModelTier,
    val profile: ModelProfile,
    val result: ChatResult,
)

fun formatPrompt(messages: List<ChatMessage>): String =
    messages.joinToString(separator = "\n\n") { message ->
        val role = message.role.uppercase()
        "[$role]\n${message.content}"
    }

fun tierLabel(tier: ModelTier): String = when (tier) {
    ModelTier.Weak -> "Слабая модель"
    ModelTier.Medium -> "Средняя модель"
    ModelTier.Strong -> "Сильная модель"
}

fun providerLabel(provider: ModelProvider): String = when (provider) {
    ModelProvider.HuggingFace -> "HuggingFace"
    ModelProvider.DeepSeek -> "DeepSeek"
}

fun formatLatency(ms: Long): String = when {
    ms < 1000 -> "${ms} мс"
    else -> "%.1f с".format(ms / 1000.0)
}

fun formatCost(usd: Double): String = when {
    usd < 0.0001 -> "~$0"
    usd < 0.01 -> "~$%.4f".format(usd)
    else -> "~$%.3f".format(usd)
}

fun formatMetrics(result: ChatResult, profile: ModelProfile): String {
    val tokensNote = if (result.tokensEstimated) " (оценка)" else ""
    return buildString {
        appendLine("Модель: ${result.modelId}")
        appendLine("Провайдер: ${providerLabel(profile.provider)}")
        appendLine("Время ответа: ${formatLatency(result.latencyMs)}")
        appendLine(
            "Токены: prompt=${result.promptTokens}, completion=${result.completionTokens}, " +
                "total=${result.totalTokens}$tokensNote",
        )
        append("Стоимость: ${formatCost(result.estimatedCostUsd)} (оценка)")
    }
}

fun formatMetricsTable(results: List<TierResult>): String {
    val header = "| Модель | Время | Токены | Стоимость |"
    val separator = "| --- | --- | --- | --- |"
    val rows = results.map { tierResult ->
        val r = tierResult.result
        val tokens = if (r.tokensEstimated) "${r.totalTokens}*" else r.totalTokens.toString()
        "| ${tierLabel(tierResult.tier)} | ${formatLatency(r.latencyMs)} | $tokens | ${formatCost(r.estimatedCostUsd)} |"
    }
    return (listOf(header, separator) + rows).joinToString("\n") +
        "\n\n* — токены оценены, API не вернул usage"
}

class ModelCompareSolver(
    private val huggingFace: HuggingFaceClient,
    private val deepSeek: DeepSeekClient,
) {
    fun ask(prompt: String, tier: ModelTier): TierResult {
        val profile = Config.profile(tier).let { base ->
            base.copy(modelId = Config.modelId(tier))
        }
        val messages = listOf(
            ChatMessage(role = "system", content = Config.SYSTEM_BASE),
            ChatMessage(role = "user", content = prompt),
        )

        val result = when (profile.provider) {
            ModelProvider.HuggingFace -> huggingFace.chat(
                model = profile.modelId,
                messages = messages,
                tier = tier,
            )
            ModelProvider.DeepSeek -> deepSeek.chat(
                messages = messages,
                temperature = 0.3,
                modelOverride = profile.modelId,
                tier = tier,
            )
        }

        return TierResult(tier, profile, result)
    }

    fun compareAll(prompt: String, results: List<TierResult>): ChatResult {
        val answerBlocks = results.joinToString("\n\n") { tierResult ->
            val header = "=== ${tierLabel(tierResult.tier)} (${tierResult.profile.modelId}) ==="
            "$header\n${tierResult.result.content}"
        }

        val links = Config.MODEL_PROFILES.joinToString("\n") { profile ->
            "- ${profile.label}: ${profile.url}"
        }

        val comparisonPrompt = """
            |Сравни ответы трёх моделей на один и тот же запрос.
            |
            |Исходный запрос:
            |$prompt
            |
            |Таблица метрик:
            |${formatMetricsTable(results)}
            |
            |Ответы моделей:
            |$answerBlocks
            |
            |Ссылки на модели:
            |$links
            |
            |Сформируй краткий структурированный вывод на русском:
            |1. **Качество ответов** — точность, полнота, логика (особенно для задачи-ловушки со станками).
            |2. **Скорость** — по времени ответа из таблицы.
            |3. **Ресурсоёмкость** — по токенам и оценочной стоимости.
            |4. **Короткий вывод** — какая модель лучше для каких задач.
            |5. **Ссылки** — перечисли ссылки на модели.
        """.trimMargin()

        val messages = listOf(
            ChatMessage(
                role = "system",
                content = "Ты аналитик LLM. Отвечай на русском, структурированно и кратко.",
            ),
            ChatMessage(role = "user", content = comparisonPrompt),
        )

        return deepSeek.chat(messages, temperature = 0.3)
    }
}
