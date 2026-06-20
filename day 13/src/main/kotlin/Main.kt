/**
 * Консольный кодинг-агент с задачей-конечным автоматом и многоагентным пайплайном.
 * На каждом этапе работают свои агенты (часть — роем параллельных инстансов), переходы
 * детерминированы и проверяются в коде, состояние переживает перезапуск (пауза/возобновление).
 * Здесь — REPL, интервью профиля и вывод; вся логика в [CodingAgent] и [CodingPipeline].
 */

private object Ansi {
    //  — символ ESC; так последовательности не зависят от того, сохранил ли редактор байт ESC.
    const val RESET = "[0m"
    const val DIM = "[2m"
    const val BOLD = "[1m"
    const val RED = "[31m"
    const val GREEN = "[32m"
    const val YELLOW = "[33m"
    const val BLUE = "[34m"
    const val MAGENTA = "[35m"
    const val CYAN = "[36m"
}

private fun color(text: String, code: String): String = "$code$text${Ansi.RESET}"

private fun stageColor(stage: TaskStage): String = when (stage) {
    TaskStage.PLANNING -> Ansi.BLUE
    TaskStage.EXECUTION -> Ansi.YELLOW
    TaskStage.VALIDATION -> Ansi.MAGENTA
    TaskStage.DONE -> Ansi.GREEN
}

private const val HELP = """
Команды:
  /help                 — справка
  Задача (конечный автомат: planning → execution → validation → done):
  /task <запрос>        — начать задачу (или просто напишите запрос, когда задачи нет)
  /state                — текущий этап, ожидаемое действие, какие артефакты готовы
  /show <этап>          — показать артефакт этапа (planning|execution|validation|done)
  /next                 — принять этап и перейти к следующему (запускает его агентов)
  /back                 — вернуться на предыдущий этап и перегенерировать его
  /stage <id>           — перейти на этап planning|execution|validation|done (валидируется)
  /clear                — бросить текущую задачу
  Профиль и сервис:
  /profile              — показать профиль разработчика
  /new-profile          — пройти интервью профиля заново
  /demo [speed]         — авто-прогон автомата задачи (для записи); speed напр. 1.5
  /reset                — стереть профиль и задачу (как первый запуск)
  /exit | /quit         — выход
Когда задача активна и на паузе, обычный текст = замечания: текущий этап перегенерируется.
"""

fun main() {
    Config.loadDotEnv()
    val client = runCatching { DeepSeekClient(apiKey = Config.apiKey()) }
        .getOrElse {
            System.err.println(color("Ошибка конфигурации: ${it.message}", Ansi.RED))
            return
        }
    val agent = CodingAgent(client)

    printBanner(agent)
    runOnboardingIfNew(agent)

    while (true) {
        print(color("\nвы › ", Ansi.BOLD))
        System.out.flush()
        val line = readlnOrNull()?.trim() ?: break
        if (line.isEmpty()) continue

        if (line.startsWith("/")) {
            if (handleCommand(line, agent)) continue else break
        }
        handlePlainInput(agent, line)
    }
    println("\nПока!")
}

/** Текст без слэша: нет задачи → стартуем новую; задача активна → это замечания к этапу. */
private fun handlePlainInput(agent: CodingAgent, text: String) {
    if (agent.task.isEmpty() || agent.task.stage == TaskStage.DONE) {
        println(color("▶ Новая задача. Запускаю этап планирования…", Ansi.CYAN))
        printOutcome(agent.startTask(text))
    } else {
        println(color("✎ Замечания приняты. Перегенерирую этап «${agent.task.stage.label}»…", Ansi.CYAN))
        printOutcome(agent.refineCurrentStage(text))
    }
}

/** @return true — продолжать REPL, false — выйти. */
private fun handleCommand(line: String, agent: CodingAgent): Boolean {
    val parts = line.split(Regex("\\s+"), limit = 2)
    when (parts[0].lowercase()) {
        "/help" -> println(HELP.trim())
        "/task" -> {
            val req = parts.getOrNull(1)
            if (req.isNullOrBlank()) println(color("Использование: /task <что нужно сделать>", Ansi.RED))
            else { println(color("▶ Запускаю этап планирования…", Ansi.CYAN)); printOutcome(agent.startTask(req)) }
        }
        "/state" -> printState(agent)
        "/show" -> showArtifact(agent, parts.getOrNull(1))
        "/next" -> printOutcome(agent.advance())
        "/back" -> printOutcome(agent.back())
        "/stage" -> {
            val id = parts.getOrNull(1)
            if (id.isNullOrBlank()) println(color("Использование: /stage planning|execution|validation|done", Ansi.RED))
            else printOutcome(agent.gotoStage(id.trim()))
        }
        "/clear" -> { agent.clearTask(); println(color("→ Задача сброшена. Профиль сохранён.", Ansi.GREEN)) }
        "/profile" -> printProfile(agent.profile)
        "/new-profile" -> runOnboarding(agent)
        "/demo" -> runDemo(agent, parts.getOrNull(1))
        "/reset" -> resetState(agent)
        "/exit", "/quit" -> return false
        else -> println(color("Неизвестная команда. /help — список команд.", Ansi.RED))
    }
    return true
}

// ---------- Вывод результатов этапа ----------

private fun printOutcome(outcome: StageOutcome) {
    when (outcome) {
        is StageOutcome.NoActiveTask ->
            println(color("Нет активной задачи. /task <запрос> — начать.", Ansi.DIM))
        is StageOutcome.Blocked -> {
            println(color("⨯ Переход отклонён: ${outcome.rejected.reason}", Ansi.RED))
            val allowed = if (outcome.rejected.allowed.isEmpty()) "— (терминальный этап)"
            else outcome.rejected.allowed.joinToString(", ") { "${it.label} (/stage ${it.id})" }
            println(color("  Разрешено сейчас: $allowed", Ansi.DIM))
        }
        is StageOutcome.Ran -> printStageRun(outcome)
    }
}

private fun printStageRun(o: StageOutcome.Ran) {
    o.transition?.let { println(color("→ Переход: ${it.from.label} → ${it.to.label}", stageColor(it.to))) }
    println("\n" + color("Этап: ${stageBar(o.stage)}", Ansi.BOLD))

    // Какие агенты отработали этап. На planning/validation последний агент — синтезатор
    // (он сводит результаты роя), на execution оба агента — равноправные параллельные инстансы.
    val runs = o.result.runs
    val hasSynth = (o.stage == TaskStage.PLANNING || o.stage == TaskStage.VALIDATION) && runs.size >= 2
    val swarm = if (hasSynth) runs.dropLast(1) else runs
    println(color("🤖 Агенты этапа (${runs.size}):", Ansi.CYAN))
    if (swarm.size > 1) println(color("   параллельно:", Ansi.DIM))
    swarm.forEach { r ->
        val mark = if (r.ok) "•" else "⨯"
        println("     $mark ${r.name} — ${r.role} · ${r.tokens} tok")
    }
    if (hasSynth) {
        val lead = runs.last()
        println("   → ${lead.name} — ${lead.role} · ${lead.tokens} tok")
    }

    println(color("\n── Артефакт: ${o.stage.produces} ──", stageColor(o.stage)))
    println(o.result.artifact)

    val maxLatency = o.result.runs.maxOfOrNull { it.latencyMs } ?: 0
    val parallelNote = if (o.result.runs.size > 1) " (параллельно)" else ""
    println(
        color(
            "\n⏸ Пауза на этапе «${o.stage.label}» · ${o.result.runs.size} агентов · " +
                "${o.result.tokens} tok · ~${maxLatency} ms$parallelNote",
            Ansi.DIM,
        ),
    )
    println(color("Дальше: ${agentExpected(o.stage)}", Ansi.DIM))
}

private fun agentExpected(stage: TaskStage): String = when (stage) {
    TaskStage.PLANNING -> "/next — принять план и перейти к коду · текстом — замечания к плану"
    TaskStage.EXECUTION -> "/next — на ревью · /back — переделать план · текстом — замечания к коду"
    TaskStage.VALIDATION -> "/next — принять (done) · /back — на доработку · текстом — замечания"
    TaskStage.DONE -> "Задача завершена. /task <запрос> — начать новую."
}

private fun stageBar(current: TaskStage): String =
    TaskStage.entries.joinToString(color(" → ", Ansi.DIM)) { s ->
        if (s == current) color("[${s.label}]", stageColor(s)) else color(s.label, Ansi.DIM)
    }

private fun printState(agent: CodingAgent) {
    val t = agent.task
    if (t.isEmpty()) {
        println(color("Активной задачи нет. /task <запрос> — начать.", Ansi.DIM))
        return
    }
    println("\n" + color("[Состояние задачи — конечный автомат]", Ansi.BOLD))
    println("  Запрос: ${t.request ?: "—"}")
    println("  Этап: ${stageBar(t.stage)}")
    println("  Ожидаемое действие: ${t.expectedAction}")
    val allowed = if (t.allowedNext.isEmpty()) "— (этап завершения)"
    else t.allowedNext.joinToString(", ") { "${it.label} (/stage ${it.id})" }
    println(color("  Разрешённые переходы: $allowed", Ansi.DIM))
    println("  Готовые артефакты:")
    TaskStage.entries.filter { it != TaskStage.DONE || t.artifact(it) != null }.forEach { s ->
        val has = t.artifact(s) != null
        val mark = if (has) color("✓", Ansi.GREEN) else color("·", Ansi.DIM)
        val hint = if (has) color("(/show ${s.id})", Ansi.DIM) else ""
        println("    $mark ${s.label} $hint")
    }
    if (t.log().isNotEmpty()) {
        println(color("  История переходов:", Ansi.DIM))
        t.log().forEach { println(color("    • $it", Ansi.DIM)) }
    }
    println(color("  состояние на диске: ${agent.statePath()}", Ansi.DIM))
}

private fun showArtifact(agent: CodingAgent, stageId: String?) {
    if (stageId.isNullOrBlank()) {
        println(color("Использование: /show planning|execution|validation|done", Ansi.RED))
        return
    }
    val stage = TaskStage.fromId(stageId.trim())
    if (stage == null) {
        println(color("Неизвестный этап '$stageId'.", Ansi.RED))
        return
    }
    val artifact = agent.task.artifact(stage)
    if (artifact == null) {
        println(color("Артефакт этапа «${stage.label}» ещё не готов.", Ansi.DIM))
        return
    }
    println(color("── Артефакт: ${stage.label} (${stage.produces}) ──", stageColor(stage)))
    println(artifact)
}

private fun printProfile(p: DevProfile) {
    println("\n" + color("[Профиль разработчика]", Ansi.CYAN))
    println("  • Язык: ${p.language}")
    println("  • Стек/фреймворки: ${p.stack.ifBlank { "—" }}")
    println("  • Уровень: ${p.experience}")
    println("  • Подробность: ${p.verbosity}")
    println("  • Ограничения: ${if (p.constraints.isEmpty()) "—" else ""}")
    p.constraints.forEach { println("     - $it") }
    println(color("  (подмешивается в каждого агента пайплайна)", Ansi.DIM))
}

// ---------- Интервью профиля ----------

/** Вопрос интервью со свободным ответом: подсказка с примерами и значение по умолчанию (Enter). */
private class OnboardingQuestion(
    val title: String,
    val hint: String,
    val default: String,
    val apply: (DevProfile, String) -> DevProfile,
)

private val ONBOARDING = listOf(
    OnboardingQuestion(
        "Основной язык программирования?",
        "например: Kotlin, Python, TypeScript, Go, Rust…",
        "Kotlin",
        { p, v -> p.copy(language = v) },
    ),
    OnboardingQuestion(
        "Стек / фреймворки?",
        "например: Ktor + Coroutines, Spring Boot, Android + Compose, FastAPI… (Enter — пропустить)",
        "",
        { p, v -> p.copy(stack = v) },
    ),
    OnboardingQuestion(
        "Уровень?",
        "junior / middle / senior (или своими словами)",
        "middle",
        { p, v -> p.copy(experience = v) },
    ),
    OnboardingQuestion(
        "Подробность ответов?",
        "кратко / сбалансированно / подробно",
        "сбалансированно",
        { p, v -> p.copy(verbosity = v) },
    ),
    OnboardingQuestion(
        "Жёсткие ограничения проекта?",
        "через запятую, например: только stdlib, обязательны тесты, без сети (Enter — пропустить)",
        "",
        { p, v ->
            val items = v.split(',', ';').map { it.trim() }.filter { it.isNotEmpty() }
            if (items.isEmpty()) p else p.copy(constraints = p.constraints + items)
        },
    ),
)

private fun runOnboardingIfNew(agent: CodingAgent) {
    if (agent.hasProfile) return
    runOnboarding(agent)
}

private fun runOnboarding(agent: CodingAgent) {
    println(
        color("\nагент ›", Ansi.BOLD) +
            " Настроим профиль разработчика — отвечайте своими словами (Enter — значение по умолчанию).",
    )
    var p = DevProfile()
    for (q in ONBOARDING) {
        println("\n" + color(q.title, Ansi.BOLD))
        println(color("  ${q.hint}", Ansi.DIM))
        val defHint = if (q.default.isBlank()) "" else color(" [${q.default}]", Ansi.DIM)
        print(color("› ", Ansi.BOLD) + defHint + " ")
        System.out.flush()
        val line = readlnOrNull() ?: return
        val answer = line.trim().ifBlank { q.default }
        p = q.apply(p, answer)
    }
    agent.setProfile(p)
    println(color("\nагент ›", Ansi.BOLD) + " Профиль сохранён и будет учитываться всеми агентами:")
    printProfile(p)
    println(
        color("\nагент ›", Ansi.BOLD) +
            " Готово! Напишите задачу (или /task <запрос>) — поедет пайплайн planning→execution→validation→done.",
    )
}

// ---------- /reset ----------

private fun resetState(agent: CodingAgent) {
    print(color("Стереть профиль и задачу — начать с чистого листа? [y/N] ", Ansi.RED))
    System.out.flush()
    val ans = readlnOrNull()?.trim()?.lowercase()
    if (ans !in setOf("y", "yes", "д", "да")) {
        println(color("Отменено.", Ansi.DIM))
        return
    }
    agent.reset()
    println(color("→ Состояние полностью очищено.", Ansi.GREEN))
    runOnboardingIfNew(agent)
}

// ---------- Авто-демо для записи ----------

/**
 * Сам проигрывает прохождение автомата по этапам с многоагентными запусками и паузами:
 * новая задача → planning → /next execution → недопустимый переход отклоняется → validation → done.
 * В конце восстанавливает прежнее состояние задачи, чтобы не сбить сессию пользователя.
 * Аргумент speed (напр. 1.5) растягивает паузы для записи.
 */
private fun runDemo(agent: CodingAgent, speedArg: String?) {
    val speed = speedArg?.replace(',', '.')?.toDoubleOrNull()?.coerceIn(0.2, 5.0) ?: 1.0
    val saved = agent.snapshotTask()
    val request = "CLI: посчитать частоту слов в текстовом файле и вывести топ-N"

    caption("Шаг 1. Новая задача → этап planning (исследователи ∥ + планировщик)", speed)
    demoType("/task $request", speed)
    printOutcome(agent.startTask(request))
    pause(2.5, speed)

    caption("Шаг 2. /next → execution (coder → test-writer)", speed)
    demoType("/next", speed)
    printOutcome(agent.advance())
    pause(2.5, speed)

    caption("Шаг 3. Недопустимый переход отклоняется в КОДЕ (детерминизм)", speed)
    demoType("/stage done", speed)
    printOutcome(agent.gotoStage("done"))
    pause(2.5, speed)

    caption("Шаг 4. /next → validation (ревьюеры ∥ + валидатор)", speed)
    demoType("/next", speed)
    printOutcome(agent.advance())
    pause(2.5, speed)

    caption("Шаг 5. /next → done (агент-оркестратор сводит результат)", speed)
    demoType("/next", speed)
    printOutcome(agent.advance())
    pause(1.5, speed)

    caption("Пауза/возобновление: артефакты сохранены в ${agent.statePath()} — переживут перезапуск", speed)
    agent.restoreTask(saved)
    caption("Демо завершено · прежняя задача восстановлена", speed)
}

private fun demoType(text: String, speed: Double) {
    pause(0.5, speed)
    println(color("\nвы › ", Ansi.BOLD) + text)
    pause(0.4, speed)
}

private fun caption(text: String, speed: Double) {
    pause(0.4, speed)
    println(color("\n─── $text ───", Ansi.CYAN))
    pause(1.0, speed)
}

private fun pause(seconds: Double, speed: Double) {
    runCatching { Thread.sleep((seconds * speed * 1000).toLong()) }
}

private fun printBanner(agent: CodingAgent) {
    println(color("День 13 — кодинг-агент с задачей-конечным автоматом и агентами по этапам", Ansi.BOLD))
    println(
        color(
            "${Config.model()} · этап · текущий шаг · ожидаемое действие · артефакты переживают перезапуск",
            Ansi.DIM,
        ),
    )
    println(
        "Пайплайн: " + color("planning", stageColor(TaskStage.PLANNING)) + " → " +
            color("execution", stageColor(TaskStage.EXECUTION)) + " → " +
            color("validation", stageColor(TaskStage.VALIDATION)) + " → " +
            color("done", stageColor(TaskStage.DONE)) +
            color("   (на каждом этапе свои агенты, часть — параллельно)", Ansi.DIM),
    )
    println(color("/help — команды · /task <запрос> — начать задачу · /demo — авто-прогон", Ansi.DIM))
}
