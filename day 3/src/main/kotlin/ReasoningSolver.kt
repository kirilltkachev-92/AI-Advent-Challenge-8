enum class ReasoningMode {
    /** Прямой ответ без дополнительных инструкций */
    Direct,
    /** Инструкция «решай пошагово» */
    StepByStep,
    /** Сначала промпт для решения, затем решение по нему */
    MetaPrompt,
    /** Панель из 3 экспертов с разными углами рассуждения */
    ExpertPanel,
}

data class ApiExchange(
    val label: String,
    val messages: List<ChatMessage>,
    val response: String,
)

data class ModeResult(
    val mode: ReasoningMode,
    val exchanges: List<ApiExchange>,
) {
    val content: String get() = exchanges.lastOrNull()?.response.orEmpty()
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

class ReasoningSolver(private val client: DeepSeekClient) {

    fun solve(task: String, mode: ReasoningMode): ModeResult = when (mode) {
        ReasoningMode.Direct -> solveDirect(task)
        ReasoningMode.StepByStep -> solveStepByStep(task)
        ReasoningMode.MetaPrompt -> solveMetaPrompt(task)
        ReasoningMode.ExpertPanel -> solveExpertPanel(task)
    }

    fun compareAll(task: String, results: List<ModeResult>): CompareResult {
        val blocks = results.joinToString("\n\n") { result ->
            val header = "=== ${modeLabel(result.mode)} ==="
            val body = result.exchanges.joinToString("\n\n") { exchange ->
                "--- ${exchange.label} ---\n${exchange.response}"
            }
            "$header\n$body"
        }

        val messages = comparisonMessages(task, blocks)
        val exchange = chatLabeled("Сравнение ответов", messages)
        return CompareResult(exchange)
    }

    private fun chatLabeled(label: String, messages: List<ChatMessage>): ApiExchange {
        val response = client.chat(messages)
        return ApiExchange(label = label, messages = messages, response = response)
    }

    private fun solveDirect(task: String): ModeResult {
        val messages = listOf(
            ChatMessage(role = "system", content = Config.SYSTEM_BASE),
            ChatMessage(role = "user", content = task),
        )
        return ModeResult(ReasoningMode.Direct, listOf(chatLabeled("Решение задачи", messages)))
    }

    private fun solveStepByStep(task: String): ModeResult {
        val messages = listOf(
            ChatMessage(role = "system", content = Config.SYSTEM_BASE),
            ChatMessage(
                role = "user",
                content = "$task\n\nРешай пошагово: нумеруй шаги рассуждения и в конце дай итоговый ответ.",
            ),
        )
        return ModeResult(ReasoningMode.StepByStep, listOf(chatLabeled("Решение пошагово", messages)))
    }

    private fun solveMetaPrompt(task: String): ModeResult {
        val promptGenerationMessages = listOf(
            ChatMessage(role = "system", content = Config.SYSTEM_BASE),
            ChatMessage(
                role = "user",
                content = "Составь один подробный промпт (инструкцию для ИИ), по которому можно " +
                    "качественно решить следующую задачу. Верни только текст промпта, без пояснений.\n\n" +
                    "Задача:\n$task",
            ),
        )
        val generated = chatLabeled("Генерация промпта", promptGenerationMessages)

        val solutionMessages = listOf(
            ChatMessage(role = "system", content = Config.SYSTEM_BASE),
            ChatMessage(
                role = "user",
                content = "Промпт для решения:\n${generated.response}\n\nЗадача:\n$task\n\n" +
                    "Реши задачу, следуя промпту.",
            ),
        )
        val solution = chatLabeled("Решение по сгенерированному промпту", solutionMessages)

        return ModeResult(ReasoningMode.MetaPrompt, listOf(generated, solution))
    }

    private fun solveExpertPanel(task: String): ModeResult {
        val messages = listOf(
            ChatMessage(
                role = "system",
                content = Config.SYSTEM_BASE +
                    " Ты модерируешь панель из трёх экспертов. Каждый отвечает со своей позиции, " +
                    "кратко и по делу (3–6 предложений). Формат ответа строго с заголовками:\n" +
                    "## Теоретик\n## Алгоритмист\n## Верификатор\n## Сводный итог",
            ),
            ChatMessage(
                role = "user",
                content = "Задача:\n$task\n\n" +
                    "Теоретик: формализуй задачу, ключевые определения и инварианты.\n" +
                    "Алгоритмист: дай пошаговое решение с операциями и промежуточными состояниями.\n" +
                    "Верификатор: проверь корректность шагов, граничные случаи и итог.\n" +
                    "В конце — сводный итог с финальным ответом.",
            ),
        )
        return ModeResult(ReasoningMode.ExpertPanel, listOf(chatLabeled("Панель экспертов", messages)))
    }

    private fun comparisonMessages(task: String, solutionsBlock: String): List<ChatMessage> = listOf(
        ChatMessage(
            role = "system",
            content = Config.SYSTEM_BASE +
                " Сравни несколько решений одной задачи. Ответ структурируй так:\n" +
                "## Отличаются ли ответы\n" +
                "## Наиболее точный способ\n" +
                "## Краткое сравнение по каждому способу",
        ),
        ChatMessage(
            role = "user",
            content = "Задача:\n$task\n\nРешения разными способами:\n\n$solutionsBlock\n\n" +
                "1) Отличаются ли ответы существенно?\n" +
                "2) Какой способ дал наиболее точный и обоснованный результат и почему?",
        ),
    )
}

fun modeLabel(mode: ReasoningMode): String = when (mode) {
    ReasoningMode.Direct -> "Прямой ответ"
    ReasoningMode.StepByStep -> "Пошагово"
    ReasoningMode.MetaPrompt -> "Мета-промпт"
    ReasoningMode.ExpertPanel -> "Панель экспертов"
}

fun modeDescription(mode: ReasoningMode): String = when (mode) {
    ReasoningMode.Direct -> "Ответ без дополнительных инструкций"
    ReasoningMode.StepByStep -> "В промпте: «решай пошагово»"
    ReasoningMode.MetaPrompt -> "Сначала промпт для решения, затем решение"
    ReasoningMode.ExpertPanel -> "Теоретик, алгоритмист, верификатор"
}
