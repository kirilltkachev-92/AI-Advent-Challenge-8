/** Результат прохождения одного этапа: артефакт + список запусков агентов (для показа). */
data class StageResult(
    val stage: TaskStage,
    val artifact: String,
    val runs: List<AgentRun>,
) {
    val tokens: Int get() = runs.sumOf { it.tokens }
}

/**
 * Кодинг-пайплайн: на каждом этапе работают СВОИ агенты, а некоторые этапы поднимают РОЙ
 * параллельных инстансов (исследователи, ревьюеры). Результат этапа — артефакт, который
 * детерминированно передаётся следующему этапу.
 *
 *   clarify    : analyst                                   ⇒ уточнённые требования
 *   planning   : совет proposer[…] ∥ → дебаты debater[…] ∥ → orchestrator (сводит мнения, возражает) ⇒ план/ТЗ
 *   execution  : coder → test-writer                        ⇒ код + тесты
 *   validation : review[correctness|security] ∥  →  validator ⇒ вердикт PASS/FAIL
 *   done       : orchestrator                                ⇒ итоговая сборка
 */
class CodingPipeline(private val client: ChatClient) {

    private fun agent(name: String, role: String, system: String) = Agent(name, role, system, client)

    // ---------- clarify ----------

    fun runClarify(request: String, profileBlock: String, feedback: String): StageResult {
        val analyst = agent("analyst", "уточнение требований", ANALYST)
        val out = analyst.run(brief("Задача пользователя", request, feedback), profileBlock)
        return StageResult(TaskStage.CLARIFY, out.output, listOf(out))
    }

    // ---------- planning ----------

    fun runPlanning(request: String, requirements: String, profileBlock: String, feedback: String): StageResult {
        val base = brief("Задача пользователя", request, feedback)
        val brief = if (requirements.isBlank()) base else "$base\n\nУточнённые требования:\n$requirements"
        // Совет агентов: каждый смотрит со своей стороны.
        val angles = listOf(
            Triple("architecture", "архитектура", RESEARCH_ARCH),
            Triple("risks", "риски и крайние случаи", RESEARCH_RISKS),
            Triple("libraries", "библиотеки и Android API", RESEARCH_LIBS),
            Triple("ui-structure", "экраны и навигация", RESEARCH_STRUCTURE),
        )

        // Раунд 1 — независимые предложения (параллельно).
        val proposals = Swarm.run(
            angles.map { (key, role, sys) -> { agent("proposer:$key", role, sys).run(brief, profileBlock) } },
        )

        // Раунд 2 — ДЕБАТЫ: каждый видит мнения коллег и возражает/корректирует своё (параллельно).
        val debates = Swarm.run(
            angles.map { (key, role, sys) ->
                {
                    val others = proposals.filter { it.name != "proposer:$key" }
                        .joinToString("\n\n") { "### ${it.role}\n${it.output}" }
                    val input = "$brief\n\nМнения коллег по совету:\n$others\n\n$DEBATE_INSTRUCTION"
                    agent("debater:$key", role, sys).run(input, profileBlock)
                }
            },
        )

        // Оркестратор знает запрос пользователя, сводит предложения и дебаты в единый план и ВОЗРАЖАЕТ.
        val proposalsDigest = proposals.joinToString("\n\n") { "### ${it.role} (${it.name})\n${it.output}" }
        val debateDigest = debates.joinToString("\n\n") { "### ${it.role} (${it.name})\n${it.output}" }
        val orchestrator = agent("orchestrator:plan", "оркестратор плана (сводит мнения, возражает)", PLAN_ORCHESTRATOR)
        val plan = orchestrator.run(
            "$brief\n\nПервичные предложения совета:\n$proposalsDigest\n\nДебаты совета:\n$debateDigest",
            profileBlock,
        )
        return StageResult(TaskStage.PLANNING, plan.output, proposals + debates + plan)
    }

    // ---------- execution ----------

    fun runExecution(request: String, plan: String, profileBlock: String, feedback: String): StageResult {
        val input = brief("Задача", request, feedback) + "\n\nПлан (ТЗ) с этапа планирования:\n$plan"
        // Последовательно: сначала реализация, затем тесты ПОД эту реализацию (реальная
        // зависимость — так тесты используют настоящие имена типов/функций и не расходятся с кодом).
        val coderRun = agent("coder", "реализация", CODER).run(input, profileBlock)
        val code = coderRun.output
        val testInput = "$input\n\nГотовая реализация (пиши тесты строго под неё):\n$code"
        val testRun = agent("test-writer", "тесты", TEST_WRITER).run(testInput, profileBlock)
        val artifact = "## Реализация\n$code\n\n## Тесты\n${testRun.output}"
        return StageResult(TaskStage.EXECUTION, artifact, listOf(coderRun, testRun))
    }

    // ---------- validation ----------

    fun runValidation(request: String, plan: String, code: String, profileBlock: String, feedback: String): StageResult {
        val input = brief("Задача", request, feedback) +
            "\n\nПлан (ТЗ):\n$plan\n\nРеализация и тесты:\n$code"
        val reviewers = listOf(
            agent("review:correctness", "корректность и соответствие плану", REVIEW_CORRECTNESS),
            agent("review:security", "безопасность и стиль", REVIEW_SECURITY),
        )
        // Рой ревьюеров параллельно — каждый со своей оптикой.
        val reviews = Swarm.run(reviewers.map { a -> { a.run(input, profileBlock) } })
        val reviewDigest = reviews.joinToString("\n\n") { "### ${it.role} (${it.name})\n${it.output}" }
        val validator = agent("validator", "сведение вердикта", VALIDATOR)
        val verdict = validator.run("$input\n\nРевью:\n$reviewDigest", profileBlock)
        return StageResult(TaskStage.VALIDATION, verdict.output, reviews + verdict)
    }

    // ---------- done ----------

    fun runDone(request: String, artifacts: Map<TaskStage, String>, profileBlock: String): StageResult {
        val input = buildString {
            append("Задача: ").append(request).append("\n\n")
            append("План:\n").append(artifacts[TaskStage.PLANNING] ?: "—").append("\n\n")
            append("Код:\n").append(artifacts[TaskStage.EXECUTION] ?: "—").append("\n\n")
            append("Вердикт ревью:\n").append(artifacts[TaskStage.VALIDATION] ?: "—")
        }
        val orchestrator = agent("orchestrator", "итоговая сборка и контроль", ORCHESTRATOR)
        val summary = orchestrator.run(input, profileBlock)
        return StageResult(TaskStage.DONE, summary.output, listOf(summary))
    }

    private fun brief(label: String, request: String, feedback: String): String =
        if (feedback.isBlank()) "$label: $request"
        else "$label: $request\n\nЗамечания пользователя (учесть обязательно):\n$feedback"

    private companion object {
        const val ANALYST =
            "Ты бизнес-аналитик Android-проекта. По запросу пользователя сформулируй УТОЧНЁННЫЕ " +
                "ТРЕБОВАНИЯ: цель, функциональные требования, минимальные нефункциональные, явные " +
                "допущения, открытые вопросы (если есть) и критерии приёмки. Кратко и структурировано, " +
                "без кода. Это вход для этапа планирования."
        const val RESEARCH_ARCH =
            "Ты research-агент. Направление: АРХИТЕКТУРА. По задаче предложи архитектурный подход: " +
                "модули, границы ответственности, структуру данных, поток управления. Кратко, тезисно, " +
                "без кода. Только то, что относится к архитектуре."
        const val RESEARCH_RISKS =
            "Ты research-агент. Направление: РИСКИ И КРАЙНИЕ СЛУЧАИ. Перечисли подводные камни, " +
                "крайние случаи, ошибки, которые легко допустить, и как их избежать. Тезисно, без кода."
        const val RESEARCH_LIBS =
            "Ты research-агент. Направление: БИБЛИОТЕКИ И ANDROID API. Предложи подходящие Android-" +
                "библиотеки (Jetpack: Compose, ViewModel, Room, Coroutines/Flow и т.п.) и средства SDK, " +
                "их плюсы/минусы и что выбрать. Тезисно, без кода."
        const val RESEARCH_STRUCTURE =
            "Ты research-агент. Направление: ЭКРАНЫ И НАВИГАЦИЯ. Предложи состав экранов/Activity/" +
                "composable, навигацию между ними и структуру UI-состояния для Android-приложения. " +
                "Тезисно, без кода."
        const val DEBATE_INSTRUCTION =
            "Это РАУНД ДЕБАТОВ. Из своей роли отреагируй на мнения коллег: с чем согласен, с чем НЕ " +
                "согласен и ПОЧЕМУ, какие конфликты/противоречия видишь между предложениями, и при " +
                "необходимости скорректируй/уточни своё предложение. Тезисно, без кода."
        const val PLAN_ORCHESTRATOR =
            "Ты агент-оркестратор планирования Android-приложений. Тебе даны запрос пользователя, " +
                "ПЕРВИЧНЫЕ предложения совета (с разных сторон) и их ДЕБАТЫ (где агенты возражали друг " +
                "другу). Учти исход дебатов, разреши конфликты и своди всё в ЕДИНЫЙ план-ТЗ, принимая " +
                "сводное решение и зная исходный запрос. ВОЗРАЖАЙ: явно отметь, с чем не согласен и " +
                "почему, какие предложения отклонены и чем закончились споры. План — в объёме MVP: НЕ " +
                "раздувай скоуп и не добавляй нефункциональные требования, которых пользователь не " +
                "просил. Формат: сначала блок «Возражения и решения совета», затем нумерованный план " +
                "(шаги, ключевые решения, файлы/функции, реалистичные критерии готовности). Без кода."
        const val CODER =
            "Ты coder-агент Android (Kotlin/Jetpack). Реализуй задачу строго по плану-ТЗ и профилю. ВАЖНО: соблюдай ключевые " +
                "технические решения и критерии готовности из плана — если план требует потоковую/ленивую " +
                "обработку, НЕ загружай данные целиком в память (используй ленивые последовательности); " +
                "не оставляй мёртвый код и не игнорируй описанные в плане модули. Пиши ТОЛЬКО код " +
                "реализации (production), БЕЗ тестов — их пишет отдельный агент. Каждый файл — отдельный " +
                "markdown-блок; путь укажи отдельной строкой-заголовком над блоком, а в коде используй " +
                "КОРРЕКТНЫЙ package (без префикса 'src'/'main'). Указывай все импорты. Чистый, " +
                "компилируемый код без заглушек и длинных объяснений."
        const val TEST_WRITER =
            "Ты агент тестов. Напиши ТОЛЬКО автотесты под ПЕРЕДАННУЮ реализацию: используй её настоящие " +
                "имена типов, функций и сигнатуры, ничего не выдумывая и не переопределяя. Тестируй " +
                "чистые функции, а НЕ точку входа main(); не вызывай код, завершающий процесс " +
                "(exitProcess). Тестовые файлы в отдельных путях (src/test/...), один класс на файл, " +
                "с нужными импортами. Только код тестов в markdown-блоках."
        const val REVIEW_CORRECTNESS =
            "Ты ревьюер. Оптика: КОРРЕКТНОСТЬ. По умолчанию код считается приемлемым. БЛОКЕР — только " +
                "то, в чём ты УВЕРЕН: код не компилируется, падает или не решает задачу. Всё остальное " +
                "(улучшения, крайние случаи, нехватка тестов, гипотетика) — НЕЗНАЧАЩИЕ замечания, НЕ " +
                "блокеры. Не помечай ВАЛИДНЫЙ синтаксис языка как ошибку компиляции; если не уверен, что " +
                "код реально не скомпилируется — это НЕ блокер. Если сомневаешься — это НЕ блокер. " +
                "Последней строкой выведи 'ИТОГ: PASS', если доказанных блокеров нет, иначе 'ИТОГ: FAIL'."
        const val REVIEW_SECURITY =
            "Ты ревьюер. Оптика: БЕЗОПАСНОСТЬ. По умолчанию код приемлем. БЛОКЕР — только реальная, " +
                "конкретная уязвимость или нарушение жёсткого ограничения профиля. Стиль, читаемость, " +
                "именование, возможные улучшения — НЕ блокеры. Если сомневаешься — НЕ блокер. " +
                "Последней строкой выведи 'ИТОГ: PASS', если доказанных блокеров нет, иначе 'ИТОГ: FAIL'."
        const val VALIDATOR =
            "Ты валидатор и финальный арбитр. ПО УМОЛЧАНИЮ итог — PASS. Поставь 'ВЕРДИКТ: FAIL' ТОЛЬКО " +
                "если в ревью есть хотя бы один КОНКРЕТНЫЙ доказанный блокер: код не компилируется, " +
                "падает, не решает задачу или содержит реальную уязвимость. «Блокеры», которые на деле " +
                "лишь улучшения, гипотетика, стиль или нехватка тестов — понижай до замечаний и НЕ " +
                "считай основанием для FAIL. Отклонение от ПРЕДЛОЖЕННОЙ в плане библиотеки или подхода " +
                "при работающем коде — тоже НЕ блокер. Если сомневаешься — PASS. Начни ответ строкой " +
                "'ВЕРДИКТ: PASS' или 'ВЕРДИКТ: FAIL', затем 1–3 самых важных пункта."
        const val ORCHESTRATOR =
            "Ты агент-оркестратор (надзор). Сведи план, код и вердикт в короткое итоговое резюме: что " +
                "сделано, как собрать/запустить, что осталось. Отметь расхождения между планом и кодом, если есть."
    }
}
