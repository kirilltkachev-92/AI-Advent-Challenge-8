data class ApiExchange(
    val label: String,
    val messages: List<ChatMessage>,
    val response: String,
    val temperature: Double,
)

data class TemperatureResult(
    val temperature: Double,
    val exchange: ApiExchange,
) {
    val content: String get() = exchange.response
}

data class CompareResult(
    val exchange: ApiExchange,
) {
    val content: String get() = exchange.response
}

fun formatPrompt(messages: List<ChatMessage>): String =
    messages.joinToString(separator = "\n\n") { message ->
        val role = message.role.uppercase()
        "[$role]\n${message.content}"
    }

class TemperatureSolver(private val client: DeepSeekClient) {

    fun ask(prompt: String, temperature: Double): TemperatureResult {
        val messages = listOf(
            ChatMessage(role = "system", content = Config.SYSTEM_BASE),
            ChatMessage(role = "user", content = prompt),
        )
        val response = client.chat(messages, temperature = temperature)
        val exchange = ApiExchange(
            label = "Ответ (${temperatureLabel(temperature)})",
            messages = messages,
            response = response,
            temperature = temperature,
        )
        return TemperatureResult(temperature, exchange)
    }

    fun compareAll(prompt: String, results: List<TemperatureResult>): CompareResult {
        val blocks = results.joinToString("\n\n") { result ->
            val header = "=== ${temperatureLabel(result.temperature)} ==="
            "$header\n${result.content}"
        }

        val rangeHint = Config.TEMPERATURE_STEPS.joinToString { formatTemperature(it) }
        val messages = comparisonMessages(prompt, blocks, rangeHint)
        val response = client.chat(messages, temperature = 0.3)
        val exchange = ApiExchange(
            label = "Сравнение ответов",
            messages = messages,
            response = response,
            temperature = 0.3,
        )
        return CompareResult(exchange)
    }

    private fun comparisonMessages(
        prompt: String,
        answersBlock: String,
        rangeHint: String,
    ): List<ChatMessage> = listOf(
        ChatMessage(
            role = "system",
            content = Config.SYSTEM_BASE +
                " Сравни ответы на один запрос при разных значениях temperature " +
                "(диапазон: $rangeHint). Ответ структурируй строго с заголовками:\n" +
                "## Точность\n" +
                "## Креативность\n" +
                "## Разнообразие\n" +
                "## Динамика по шкале temperature\n" +
                "## Рекомендации по применению\n" +
                "(в последних разделах — как меняются ответы от низкой к высокой temperature " +
                "и для каких типов задач лучше низкие, средние и высокие значения)",
        ),
        ChatMessage(
            role = "user",
            content = "Исходный запрос:\n$prompt\n\n" +
                "Ответы при разных temperature:\n\n$answersBlock\n\n" +
                "Сравни ответы по точности, креативности и разнообразию. " +
                "Опиши тренд по шкале temperature и сформулируй рекомендации по выбору значения.",
        ),
    )
}

fun formatTemperature(value: Double): String {
    val rounded = kotlin.math.round(value * 10.0) / 10.0
    return if (rounded == kotlin.math.floor(rounded)) {
        rounded.toInt().toString()
    } else {
        "%.1f".format(rounded)
    }
}

fun temperatureLabel(temperature: Double): String =
    "temperature = ${formatTemperature(temperature)}"

fun temperatureDescription(temperature: Double): String = when {
    temperature <= 0.0 -> "минимальная случайность, максимальная точность"
    temperature < 0.7 -> "низкая вариативность"
    temperature < 1.3 -> "баланс точности и вариативности"
    temperature < 1.7 -> "повышенная креативность"
    else -> "высокая вариативность и креативность"
}
