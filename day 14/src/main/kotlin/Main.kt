/**
 * Консольный Android кодинг-агент с задачей-конечным автоматом и многоагентным пайплайном.
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
  Инварианты (жёсткие ограничения, хранятся отдельно от диалога):
  /inv                  — показать инварианты
  /inv add <правило>    — добавить инвариант (проверяется LLM-контролёром)
  /inv forbid <токен>   — запретить токен (детерминированная проверка)
  /inv rm <n>           — удалить инвариант №n
  /inv clear            — удалить все инварианты
  /check <текст>        — проверить произвольный текст на инварианты
  Профиль и сервис:
  /profile              — показать профиль разработчика
  /new-profile          — пройти интервью профиля заново
  /demo [speed]         — авто-прогон автомата задачи (для записи); speed напр. 1.5
  /reset                — стереть профиль, задачу и инварианты (как первый запуск)
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
        "/inv" -> invCommand(agent, parts.getOrNull(1))
        "/check" -> checkCommand(agent, parts.getOrNull(1))
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
        is StageOutcome.Refused -> {
            println(color("\n⛔ Отказ: запрос конфликтует с инвариантами проекта.", Ansi.RED))
            outcome.violations.forEach { v ->
                println(color("   • [${v.invariant.category}] ${v.invariant.rule}", Ansi.RED))
                println(color("     нарушение: ${v.evidence} · проверка: ${v.source}", Ansi.DIM))
            }
            println("\n" + color("Объяснение:", Ansi.BOLD))
            println(outcome.explanation)
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

    if (o.violations.isNotEmpty()) {
        println(color("\n⚠ Код всё ещё нарушает инварианты после попыток исправления:", Ansi.RED))
        o.violations.forEach { v ->
            println(color("   • [${v.invariant.category}] ${v.invariant.rule} (${v.evidence}, ${v.source})", Ansi.RED))
        }
        println(color("   Не принимайте этап как есть: дайте замечание или /back для переделки.", Ansi.DIM))
    }

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
    println(color("  активных инвариантов: ${agent.invariants().size} (/inv — показать)", Ansi.DIM))
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
    println("\n" + color("[Профиль Android-разработчика]", Ansi.CYAN))
    println("  • Язык: ${p.language}")
    println("  • Стек/фреймворки: ${p.stack.ifBlank { "—" }}")
    println("  • Уровень: ${p.experience}")
    println("  • Подробность: ${p.verbosity}")
    println("  • Ограничения: ${if (p.constraints.isEmpty()) "—" else ""}")
    p.constraints.forEach { println("     - $it") }
    println(color("  (подмешивается в каждого агента пайплайна)", Ansi.DIM))
}

// ---------- Инварианты ----------

private fun invCommand(agent: CodingAgent, arg: String?) {
    val parts = arg?.trim()?.split(Regex("\\s+"), limit = 2).orEmpty()
    when (parts.getOrNull(0)?.lowercase()) {
        null, "", "list" -> printInvariants(agent)
        "add" -> {
            val rule = parts.getOrNull(1)?.trim()
            if (rule.isNullOrBlank()) println(color("Использование: /inv add <правило инварианта>", Ansi.RED))
            else {
                agent.addInvariant("пользовательский", rule, emptyList())
                println(color("→ Инвариант добавлен (проверяется LLM-контролёром).", Ansi.GREEN))
                printInvariants(agent)
            }
        }
        "forbid" -> {
            val token = parts.getOrNull(1)?.trim()
            if (token.isNullOrBlank()) println(color("Использование: /inv forbid <запрещённый токен>", Ansi.RED))
            else {
                agent.addInvariant("стек", "Запрещено использовать «$token».", listOf(token))
                println(color("→ Запрет добавлен (детерминированная проверка по токену «$token»).", Ansi.GREEN))
                printInvariants(agent)
            }
        }
        "rm" -> {
            val n = parts.getOrNull(1)?.trim()?.toIntOrNull()
            if (n == null) println(color("Использование: /inv rm <номер из /inv>", Ansi.RED))
            else {
                val removed = agent.removeInvariant(n - 1)
                if (removed == null) println(color("Нет инварианта №$n.", Ansi.RED))
                else { println(color("→ Удалён: ${removed.rule}", Ansi.GREEN)); printInvariants(agent) }
            }
        }
        "clear" -> { agent.clearInvariants(); println(color("→ Все инварианты удалены.", Ansi.GREEN)) }
        else -> println(color("Подкоманды: /inv · /inv add <правило> · /inv forbid <токен> · /inv rm <n> · /inv clear", Ansi.RED))
    }
}

private fun printInvariants(agent: CodingAgent) {
    val invs = agent.invariants()
    println("\n" + color("[Инварианты — жёсткие ограничения, отдельно от диалога]", Ansi.MAGENTA))
    if (invs.isEmpty()) println(color("  (нет)", Ansi.DIM))
    invs.forEachIndexed { i, inv ->
        println("  ${i + 1}. ${color("[${inv.category}]", Ansi.DIM)} ${inv.rule}")
        if (inv.forbid.isNotEmpty()) println(color("       запрещено (код): ${inv.forbid.joinToString(", ")}", Ansi.DIM))
    }
    println(color("  хранилище: ${agent.invariantsPath()} · подмешиваются в каждого агента", Ansi.DIM))
    println(color("  /inv add <правило> · /inv forbid <токен> · /inv rm <n> · /inv clear · /check <текст>", Ansi.DIM))
}

private fun checkCommand(agent: CodingAgent, text: String?) {
    if (text.isNullOrBlank()) {
        println(color("Использование: /check <текст для проверки на инварианты>", Ansi.RED))
        return
    }
    val violations = agent.checkText(text)
    if (violations.isEmpty()) {
        println(color("✓ Нарушений инвариантов не найдено.", Ansi.GREEN))
    } else {
        println(color("⛔ Найдены нарушения инвариантов:", Ansi.RED))
        violations.forEach { v ->
            println(color("   • [${v.invariant.category}] ${v.invariant.rule}", Ansi.RED))
            println(color("     ${v.evidence} · проверка: ${v.source}", Ansi.DIM))
        }
    }
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
        "Язык (Android)?",
        "Kotlin или Java",
        "Kotlin",
        { p, v -> p.copy(language = v) },
    ),
    OnboardingQuestion(
        "Стек / UI-подход (Android)?",
        "например: Jetpack Compose, XML Views, Hilt, Coroutines/Flow, Room… (Enter — Compose)",
        "Jetpack Compose",
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
        "через запятую, например: только Compose, обязательны тесты, minSdk 24 (Enter — пропустить)",
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
            " Настроим профиль Android-разработчика — отвечайте своими словами (Enter — значение по умолчанию).",
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
 * Демонстрирует инварианты: проект только для Android, запрещает RxJava и MVP.
 * Не-Android запрос (бэкенд) → отказ (домен); Hello World на MVP + RxJava → отказ (стек+архитектура);
 * затем чистый Hello World → пайплайн идёт (planning → execution) и код соблюдает инварианты.
 * В конце восстанавливает прежнее состояние задачи. speed (напр. 1.5) растягивает паузы.
 */
private fun runDemo(agent: CodingAgent, speedArg: String?) {
    val speed = speedArg?.replace(',', '.')?.toDoubleOrNull()?.coerceIn(0.2, 5.0) ?: 1.0
    val saved = agent.snapshotTask()

    caption("Инварианты проекта (хранятся отдельно): только Android, без RxJava и MVP", speed)
    demoType("/inv", speed)
    printInvariants(agent)
    pause(2.5, speed)

    caption("Шаг 1. Не-Android запрос: бэкенд → агент ОТКАЗЫВАЕТ (домен)", speed)
    val backend = "Сделай REST API бэкенд на Node.js с базой Postgres"
    demoType("/task $backend", speed)
    printOutcome(agent.startTask(backend))
    pause(2.5, speed)

    caption("Шаг 2. Конфликтный запрос: Hello World на MVP + RxJava → агент ОТКАЗЫВАЕТ (стек + архитектура)", speed)
    val bad = "Сделай Android-приложение Hello World на архитектуре MVP с использованием RxJava"
    demoType("/task $bad", speed)
    printOutcome(agent.startTask(bad))
    pause(2.5, speed)

    caption("Шаг 3. Тот же продукт без запрещённого — допустимый запрос принимается (planning)", speed)
    val good = "Создай Android-приложение, которое показывает «Hello World» в главной Activity"
    demoType("/task $good", speed)
    printOutcome(agent.startTask(good))
    pause(2.5, speed)

    caption("Шаг 4. /next → execution: код Hello World, проверяется на инварианты (без RxJava/MVP)", speed)
    demoType("/next", speed)
    printOutcome(agent.advance())
    pause(1.5, speed)

    caption("Демо завершено · агент не вышел за рамки инвариантов", speed)
    agent.restoreTask(saved)
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
    println(color("День 14 — Android кодинг-агент с инвариантами (жёсткими ограничениями состояния)", Ansi.BOLD))
    println(
        color(
            "${Config.model()} · конечный автомат задачи · ${agent.invariants().size} инвариантов соблюдаются неукоснительно",
            Ansi.DIM,
        ),
    )
    println(
        "Пайплайн: " + color("planning", stageColor(TaskStage.PLANNING)) + " → " +
            color("execution", stageColor(TaskStage.EXECUTION)) + " → " +
            color("validation", stageColor(TaskStage.VALIDATION)) + " → " +
            color("done", stageColor(TaskStage.DONE)) +
            color("   (инварианты подмешиваются в каждого агента и проверяются код+LLM)", Ansi.DIM),
    )
    println(color("/help — команды · /task <запрос> — начать · /inv — инварианты · /demo — авто-прогон", Ansi.DIM))
}
