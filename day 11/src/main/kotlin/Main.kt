/**
 * Консольный stateful-ассистент с явной моделью памяти из трёх слоёв.
 * Весь поток одной реплики живёт в [MemoryAgent]; здесь — только REPL и вывод.
 */

private object Ansi {
    const val RESET = "[0m"
    const val DIM = "[2m"
    const val BOLD = "[1m"
    const val RED = "[31m"
    const val BLUE = "[34m"     // краткосрочная
    const val ORANGE = "[33m"   // рабочая
    const val GREEN = "[32m"    // долговременная
}

private fun layerColor(layer: MemoryLayer): String = when (layer) {
    MemoryLayer.SHORT_TERM -> Ansi.BLUE
    MemoryLayer.WORKING -> Ansi.ORANGE
    MemoryLayer.LONG_TERM -> Ansi.GREEN
}

private fun color(text: String, code: String): String = "$code$text${Ansi.RESET}"

/** Один шаг стартового интервью: ключ профиля, заголовок и варианты (значение → подпись). */
private class OnboardingQuestion(
    val key: String,
    val title: String,
    val options: List<Pair<String, String>>,
)

/** Интервью при первом запуске — анкета фитнес-коуча, выбор цифрой, без ввода текста. */
private val ONBOARDING_QUESTIONS = listOf(
    OnboardingQuestion(
        key = "цель",
        title = "Какая цель тренировок?",
        options = listOf(
            "похудение" to "снижение веса",
            "набор массы" to "набор мышечной массы",
            "выносливость" to "выносливость / кардио",
            "общий тонус" to "общий тонус и здоровье",
        ),
    ),
    OnboardingQuestion(
        key = "уровень",
        title = "Уровень подготовки?",
        options = listOf(
            "новичок" to "новичок",
            "средний" to "средний",
            "продвинутый" to "продвинутый",
        ),
    ),
    OnboardingQuestion(
        key = "инвентарь",
        title = "Где и с чем тренируетесь?",
        options = listOf(
            "зал" to "тренажёрный зал",
            "дом с инвентарём" to "дома с инвентарём (гантели/резинки)",
            "только своё тело" to "только вес тела",
        ),
    ),
    OnboardingQuestion(
        key = "ограничения по здоровью",
        title = "Ограничения по здоровью?",
        options = listOf(
            "нет" to "нет ограничений",
            "спина" to "беречь спину",
            "колени" to "беречь колени",
            "плечи" to "беречь плечи",
        ),
    ),
)

private const val HELP = """
Команды:
  /help              — показать эту справку
  /mem               — показать все три слоя памяти
  /prompt            — показать промпт последнего запроса (что реально ушло в API)
  /layers            — какие слои сейчас подмешиваются в промпт
  /on  <s|w|l|all>   — включить слой в промпт (short/working/long)
  /off <s|w|l>       — выключить слой из промпта
  /new               — новая сессия: чистит краткосрочную и рабочую, профиль сохраняет
  /forget            — забыть всё, включая долговременную память
  /exit | /quit      — выход
Любой другой текст — сообщение ассистенту.
"""

fun main() {
    Config.loadDotEnv()
    val client = runCatching { DeepSeekClient(apiKey = Config.apiKey()) }
        .getOrElse {
            System.err.println(color("Ошибка конфигурации: ${it.message}", Ansi.RED))
            return
        }
    val agent = MemoryAgent(client)

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

        when (val outcome = agent.processUserMessage(line)) {
            is TurnOutcome.Ok -> printTurn(outcome)
            is TurnOutcome.Fail -> println(color("⨯ ${outcome.message}", Ansi.RED))
        }
    }
    println("\nПока!")
}

/** @return true — продолжать REPL, false — выйти. */
private fun handleCommand(line: String, agent: MemoryAgent): Boolean {
    val parts = line.split(Regex("\\s+"), limit = 2)
    when (parts[0].lowercase()) {
        "/help" -> println(HELP.trim())
        "/mem" -> printMemory(agent)
        "/prompt" -> printPrompt()
        "/layers" -> printLayers(agent)
        "/on" -> toggleLayer(agent, parts.getOrNull(1), enable = true)
        "/off" -> toggleLayer(agent, parts.getOrNull(1), enable = false)
        "/new" -> {
            agent.newSession()
            lastPreview = null
            println(color("→ Новая сессия. Краткосрочная и рабочая память очищены, профиль сохранён.", Ansi.GREEN))
        }
        "/forget" -> {
            agent.forgetEverything()
            lastPreview = null
            println(color("→ Память полностью очищена (включая профиль).", Ansi.GREEN))
            runOnboardingIfNew(agent)
        }
        "/exit", "/quit" -> return false
        else -> println(color("Неизвестная команда. /help — список команд.", Ansi.RED))
    }
    return true
}

private var lastPreview: String? = null

private fun printTurn(o: TurnOutcome.Ok) {
    // Что и в какой слой ушло — подсвечиваем цветом слоя.
    if (o.applied.isNotEmpty()) {
        o.applied.forEach { w ->
            val mark = if (w.accepted) "→" else "⨯"
            val keyPart = if (w.key.isBlank()) "" else "${w.key}: "
            val text = "  $mark [${w.layer.title}/${w.kind}] $keyPart${w.value}"
            println(if (w.accepted) color(text, layerColor(w.layer)) else color(text, Ansi.RED))
        }
    }

    println(color("\nкоуч ›", Ansi.BOLD) + " " + o.assistant)

    val used = MemoryLayer.entries.filter { it in o.enabledLayers }.joinToString(", ") { it.title }
    val meta = "промпт из: [$used] · ${o.mainTokens} tok ответ + ${o.routerTokens} tok роутер · ${o.latencyMs} ms"
    lastPreview = o.promptPreview
    println(color(meta, Ansi.DIM))
}

private fun toggleLayer(agent: MemoryAgent, arg: String?, enable: Boolean) {
    if (arg == null) {
        println(color("Укажите слой: s (краткосрочная), w (рабочая), l (долговременная)" + if (enable) ", all" else "", Ansi.RED))
        return
    }
    if (enable && arg.lowercase() == "all") {
        agent.enabledLayers.addAll(MemoryLayer.entries)
        printLayers(agent)
        return
    }
    val layer = when (arg.lowercase()) {
        "s", "short", "short_term" -> MemoryLayer.SHORT_TERM
        "w", "working" -> MemoryLayer.WORKING
        "l", "long", "long_term" -> MemoryLayer.LONG_TERM
        else -> null
    }
    if (layer == null) {
        println(color("Неизвестный слой '$arg'. Используйте s / w / l.", Ansi.RED))
        return
    }
    if (enable) agent.enabledLayers.add(layer) else agent.enabledLayers.remove(layer)
    printLayers(agent)
}

private fun printLayers(agent: MemoryAgent) {
    val rendered = MemoryLayer.entries.joinToString("  ") { layer ->
        val on = layer in agent.enabledLayers
        val dot = if (on) "●" else "○"
        val text = "$dot ${layer.title}"
        if (on) color(text, layerColor(layer)) else color(text, Ansi.DIM)
    }
    println("Слои в промпте: $rendered")
}

private fun printPrompt() {
    val preview = lastPreview
    if (preview == null) {
        println(color("Промпта ещё нет — отправьте сообщение.", Ansi.DIM))
        return
    }
    println(color("── Промпт последнего запроса (что реально ушло в API) ──", Ansi.BOLD))
    println(preview)
}

private fun printMemory(agent: MemoryAgent) {
    val short = agent.shortTerm.snapshot()
    val work = agent.working.snapshot()
    val long = agent.longTerm.snapshot()

    println(color("\n[Краткосрочная] ${short.messages.size} реплик · окно ${short.windowSize}", layerColor(MemoryLayer.SHORT_TERM)))
    val inWindow = short.messages.takeLast(short.windowSize).toSet()
    if (short.messages.isEmpty()) println(color("  (диалога пока нет)", Ansi.DIM))
    short.messages.takeLast(8).forEach { m ->
        val role = if (m.role == "user") "Вы" else "Коуч"
        val body = m.content.take(80) + if (m.content.length > 80) "…" else ""
        val text = "  $role: $body"
        println(if (m in inWindow) text else color("$text  (вне окна — в API не уходит)", Ansi.DIM))
    }

    println(color("\n[Рабочая] текущая задача", layerColor(MemoryLayer.WORKING)))
    println("  Задача: ${work.taskTitle ?: "(не задана)"}")
    println("  Стадия: ${TaskStage.fromId(work.stage)?.label ?: work.stage}")
    if (work.requirements.isNotEmpty()) {
        println("  Требования:")
        work.requirements.forEach { println("    • $it") }
    }
    if (work.notes.isNotEmpty()) {
        println("  Заметки:")
        work.notes.forEach { println("    • $it") }
    }

    println(color("\n[Долговременная] профиль и знания", layerColor(MemoryLayer.LONG_TERM)))
    if (long.profile.isEmpty() && long.constraints.isEmpty() && long.decisions.isEmpty() && long.knowledge.isEmpty()) {
        println(color("  (профиль пока пуст)", Ansi.DIM))
    }
    if (long.profile.isNotEmpty()) {
        println("  Профиль:")
        long.profile.forEach { (k, v) -> println("    • $k: $v") }
    }
    if (long.constraints.isNotEmpty()) {
        println("  Ограничения:")
        long.constraints.forEach { (k, v) -> println("    • $k: $v") }
    }
    if (long.decisions.isNotEmpty()) {
        println("  Решения:")
        long.decisions.forEach { println("    • $it") }
    }
    if (long.knowledge.isNotEmpty()) {
        println("  Знания:")
        long.knowledge.forEach { println("    • $it") }
    }

    val (s, w, l) = agent.storePaths()
    println(color("\nфайлы слоёв: $s · $w · $l", Ansi.DIM))
}

/**
 * Стартовое интервью: только для нового пользователя (долговременная память пуста).
 * Ответы выбираются цифрой и пишутся прямо в профиль (долговременный слой).
 */
private fun runOnboardingIfNew(agent: MemoryAgent) {
    if (!agent.longTerm.isEmpty()) return
    println(
        color("\nкоуч ›", Ansi.BOLD) +
            " Я ваш фитнес-коуч. Заполним короткую анкету — выбирайте цифрой (Enter — первый вариант).",
    )
    val chosen = linkedMapOf<String, String>()
    for (q in ONBOARDING_QUESTIONS) {
        println("\n" + color(q.title + ":", Ansi.BOLD))
        q.options.forEachIndexed { i, (_, label) -> println("  ${i + 1}) $label") }
        print(color("› ", Ansi.BOLD))
        System.out.flush()
        val line = readlnOrNull() ?: return // EOF — прерываем интервью
        val idx = line.trim().toIntOrNull()?.minus(1)?.takeIf { it in q.options.indices } ?: 0
        chosen[q.key] = q.options[idx].first
    }
    agent.rememberProfile(chosen)
    val summary = chosen.entries.joinToString(", ") { "${it.key}: ${it.value}" }
    println(
        color("\nкоуч ›", Ansi.BOLD) +
            " Записал в профиль (долговременная память): $summary. С чего начнём — программа, упражнение или вопрос?",
    )
}

private fun printBanner(agent: MemoryAgent) {
    println(color("День 11 — фитнес-коуч с моделью памяти (memory layers)", Ansi.BOLD))
    println(
        color(
            "${Config.model()} · окно ${Config.windowSize()} реплик · роутер сам решает, что и куда сохранить",
            Ansi.DIM,
        ),
    )
    println(
        "Слои: " +
            color("краткосрочная", layerColor(MemoryLayer.SHORT_TERM)) + " · " +
            color("рабочая", layerColor(MemoryLayer.WORKING)) + " · " +
            color("долговременная", layerColor(MemoryLayer.LONG_TERM)),
    )
    printLayers(agent)
    println(color("/help — команды. /exit — выход.", Ansi.DIM))
}
