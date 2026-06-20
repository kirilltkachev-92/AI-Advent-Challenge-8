/**
 * Консольный персонализированный ассистент: профиль пользователя поверх модели памяти.
 * Весь поток одной реплики живёт в [MemoryAgent]; здесь — REPL, интервью и вывод.
 */

private object Ansi {
    const val RESET = "[0m"
    const val DIM = "[2m"
    const val BOLD = "[1m"
    const val RED = "[31m"
    const val BLUE = "[34m"     // краткосрочная
    const val ORANGE = "[33m"   // рабочая
    const val GREEN = "[32m"    // долговременная
    const val MAGENTA = "[35m"  // профиль / персонализация
}

private fun layerColor(layer: MemoryLayer): String = when (layer) {
    MemoryLayer.SHORT_TERM -> Ansi.BLUE
    MemoryLayer.WORKING -> Ansi.ORANGE
    MemoryLayer.LONG_TERM -> Ansi.GREEN
}

private fun color(text: String, code: String): String = "$code$text${Ansi.RESET}"

/** Один шаг интервью: куда писать значение в профиль, заголовок и варианты (значение → подпись). */
private class OnboardingQuestion(
    val title: String,
    val options: List<Pair<String, String>>,
    val apply: (UserProfile, String) -> UserProfile,
)

/**
 * Интервью при первом запуске — собирает персонализацию: контекст (цель, уровень,
 * инвентарь, ограничение) и предпочтения (стиль, формат). Выбор цифрой, Enter — первый вариант.
 */
private val ONBOARDING_QUESTIONS = listOf(
    OnboardingQuestion(
        title = "Какая цель тренировок?",
        options = listOf(
            "похудение и общий тонус" to "похудение / тонус",
            "набор мышечной массы" to "набор массы",
            "выносливость" to "выносливость / кардио",
            "поддержание формы" to "просто поддерживать форму",
        ),
        apply = { p, v -> p.copy(context = p.context.copy(goal = v)) },
    ),
    OnboardingQuestion(
        title = "Уровень подготовки?",
        options = listOf(
            "новичок" to "новичок",
            "средний" to "средний",
            "продвинутый" to "продвинутый",
        ),
        apply = { p, v -> p.copy(context = p.context.copy(level = v)) },
    ),
    OnboardingQuestion(
        title = "Где и с чем тренируетесь?",
        options = listOf(
            "тренажёрный зал, полный инвентарь" to "зал",
            "дома с инвентарём (гантели/резинки)" to "дома с инвентарём",
            "дома, без инвентаря" to "только вес тела",
        ),
        apply = { p, v -> p.copy(context = p.context.copy(equipment = v)) },
    ),
    OnboardingQuestion(
        title = "Ограничение по здоровью? (попадёт в строгие ограничения)",
        options = listOf(
            "" to "нет ограничений",
            "беречь спину" to "беречь спину",
            "беречь колени" to "беречь колени",
            "беречь плечи" to "беречь плечи",
        ),
        apply = { p, v -> if (v.isBlank()) p else p.copy(constraints = p.constraints + v) },
    ),
    OnboardingQuestion(
        title = "Насколько подробные ответы вам удобны? (стиль)",
        options = listOf(
            "кратко" to "кратко, только суть",
            "сбалансированно" to "сбалансированно",
            "подробно" to "подробно, с пояснениями",
        ),
        apply = { p, v -> p.copy(style = p.style.copy(verbosity = v)) },
    ),
    OnboardingQuestion(
        title = "В каком тоне общаться? (стиль)",
        options = listOf(
            "дружелюбно и мотивирующе" to "дружелюбно, мотивирующе",
            "нейтрально" to "нейтрально",
            "строго по делу" to "строго по делу",
        ),
        apply = { p, v -> p.copy(style = p.style.copy(tone = v)) },
    ),
    OnboardingQuestion(
        title = "Как удобнее подавать ответ? (формат)",
        options = listOf(
            "по шагам" to "пошагово",
            "списком" to "списком",
            "сплошным текстом" to "сплошным текстом",
        ),
        apply = { p, v -> p.copy(format = p.format.copy(structure = v)) },
    ),
)

private const val HELP = """
Команды:
  /help              — показать эту справку
  /profiles          — список профилей (активный помечен ●)
  /use <id>          — переключиться на профиль (память тоже переключится)
  /profile           — показать активный профиль целиком
  /persona <on|off>  — подмешивать профиль в промпт или нет (демо «с профилем / без»)
  /demo [speed]      — проиграть тест-сценарий автоматически (для записи); speed напр. 1.5 — медленнее
  /new-profile       — пройти интервью и создать свой профиль заново
  /mem               — показать все три слоя памяти текущего профиля
  /prompt            — показать промпт последнего запроса (что реально ушло в API)
  /layers            — какие слои памяти сейчас подмешиваются в промпт
  /on  <s|w|l|all>   — включить слой памяти в промпт (short/working/long)
  /off <s|w|l>       — выключить слой памяти из промпта
  /new               — новая сессия: чистит краткосрочную и рабочую память профиля
  /forget            — забыть память текущего профиля (профиль и предпочтения остаются)
  /reset             — стереть ВСЁ (все профили и их память) и начать как при первом запуске
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
        "/profiles" -> printProfiles(agent)
        "/use" -> switchProfile(agent, parts.getOrNull(1))
        "/profile" -> printProfile(agent.profile)
        "/persona" -> togglePersona(agent, parts.getOrNull(1))
        "/demo" -> { runDemo(agent, parts.getOrNull(1)); lastPreview = null }
        "/new-profile" -> {
            runOnboarding(agent)
            lastPreview = null
        }
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
            println(color("→ Память профиля «${agent.profile.name}» очищена. Профиль и предпочтения сохранены.", Ansi.GREEN))
        }
        "/reset" -> resetState(agent)
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
    val persona = if (o.personalizationOn) "${o.profileName} (${o.profileLabel})" else "ВЫКЛ"
    val meta = "профиль: $persona · память: [$used] · ${o.mainTokens} tok ответ + ${o.routerTokens} tok роутер · ${o.latencyMs} ms"
    lastPreview = o.promptPreview
    println(color(meta, Ansi.DIM))
}

private fun printProfiles(agent: MemoryAgent) {
    println(color("\nПрофили:", Ansi.BOLD))
    val activeId = agent.profile.id
    agent.listProfiles().forEach { p ->
        val active = p.id == activeId
        val dot = if (active) "●" else "○"
        val head = "$dot ${p.name}  ${color("(/use ${p.id})", Ansi.DIM)}"
        val line = "  $head — ${p.context.goal.ifBlank { "—" }}, ${p.context.level.ifBlank { "—" }}; ${p.shortLabel()}"
        println(if (active) color(line, Ansi.MAGENTA) else line)
    }
    println(color("Переключиться: /use <id>. Один и тот же вопрос на разных профилях даёт разные ответы.", Ansi.DIM))
}

private fun switchProfile(agent: MemoryAgent, id: String?) {
    if (id == null) {
        println(color("Укажите id профиля. /profiles — список.", Ansi.RED))
        return
    }
    if (agent.switchProfile(id.trim())) {
        lastPreview = null
        println(color("→ Активен профиль «${agent.profile.name}». Подключена его память.", Ansi.MAGENTA))
        printProfile(agent.profile)
    } else {
        println(color("Профиль '$id' не найден. /profiles — список.", Ansi.RED))
    }
}

private fun togglePersona(agent: MemoryAgent, arg: String?) {
    when (arg?.lowercase()) {
        "on" -> { agent.personalizationOn = true; println(color("→ Персонализация ВКЛ: профиль подмешивается в каждый запрос.", Ansi.MAGENTA)) }
        "off" -> { agent.personalizationOn = false; println(color("→ Персонализация ВЫКЛ: профиль в промпт не попадает (ответы как у обычного ассистента).", Ansi.DIM)) }
        else -> println(color("Использование: /persona on  |  /persona off", Ansi.RED))
    }
}

private fun printProfile(p: UserProfile) {
    println(color("\n[Профиль «${p.name}»]  id=${p.id}", Ansi.MAGENTA))
    println(color("Контекст:", Ansi.BOLD))
    println("  • Кто: ${p.context.who.ifBlank { "—" }}")
    println("  • Цель: ${p.context.goal.ifBlank { "—" }}")
    println("  • Уровень: ${p.context.level.ifBlank { "—" }}")
    println("  • Инвентарь/окружение: ${p.context.equipment.ifBlank { "—" }}")
    println(color("Стиль:", Ansi.BOLD))
    println("  • Подробность: ${p.style.verbosity}")
    println("  • Тон: ${p.style.tone}")
    println("  • Терминология: ${p.style.jargon}")
    println("  • Эмодзи: ${if (p.style.emoji) "уместны" else "не использовать"}")
    println(color("Формат:", Ansi.BOLD))
    println("  • Структура: ${p.format.structure}")
    println("  • Длина: ${p.format.length}")
    println("  • Конкретные цифры: ${if (p.format.numbers) "да" else "нет"}")
    println(color("Ограничения (строго):", Ansi.BOLD))
    if (p.constraints.isEmpty()) println(color("  (нет)", Ansi.DIM))
    p.constraints.forEach { println("  • $it") }
}

// ---------- Авто-демо для записи (команда /demo) ----------

private const val DEMO_QUESTION = "составь короткую тренировку на сегодня"

/**
 * Проигрывает тест-сценарий персонализации сам, с паузами и подписями — удобно записывать.
 * Сценарий: один вопрос на разных профилях → тот же вопрос с персонализацией ВЫКЛ/ВКЛ → /profiles.
 * В конце восстанавливает исходный профиль и режим, чтобы не сбить сессию пользователя.
 * Аргумент speed (напр. 1.5) растягивает паузы для записи.
 */
private fun runDemo(agent: MemoryAgent, speedArg: String?) {
    val speed = speedArg?.replace(',', '.')?.toDoubleOrNull()?.coerceIn(0.2, 5.0) ?: 1.0
    val savedProfile = agent.profile.id
    val savedPersona = agent.personalizationOn
    agent.personalizationOn = true

    val personas = listOf("igor", "anna", "maria").filter { agent.hasProfile(it) }

    demoCaption("Шаг 1. Один и тот же вопрос — РАЗНЫЕ профили дают разные ответы", speed)
    for (id in personas) {
        demoType("/use $id", speed)
        agent.switchProfile(id)
        println(color("→ Активен профиль «${agent.profile.name}» (${agent.profile.shortLabel()})", Ansi.MAGENTA))
        pause(1.4, speed)
        demoAsk(agent, speed)
        pause(2.2, speed)
    }

    demoCaption("Шаг 2. Тот же вопрос с персонализацией ВЫКЛ vs ВКЛ (чистим диалог)", speed)
    demoType("/new", speed)
    agent.newSession()
    println(color("→ Диалог очищен.", Ansi.GREEN))
    pause(1.0, speed)
    demoType("/persona off", speed)
    agent.personalizationOn = false
    println(color("→ Персонализация ВЫКЛ: профиль в промпт не попадает.", Ansi.DIM))
    pause(1.0, speed)
    demoAsk(agent, speed)
    pause(2.2, speed)

    demoType("/new", speed)
    agent.newSession()
    println(color("→ Диалог очищен.", Ansi.GREEN))
    pause(1.0, speed)
    demoType("/persona on", speed)
    agent.personalizationOn = true
    println(color("→ Персонализация ВКЛ: профиль снова подмешивается.", Ansi.MAGENTA))
    pause(1.0, speed)
    demoAsk(agent, speed)
    pause(2.2, speed)

    demoCaption("Шаг 3. Список профилей", speed)
    demoType("/profiles", speed)
    printProfiles(agent)

    // Возвращаем сессию в исходное состояние.
    agent.switchProfile(savedProfile)
    agent.personalizationOn = savedPersona
    demoCaption("Демо завершено", speed)
    println(color("Восстановлен профиль «${agent.profile.name}», персонализация ${if (savedPersona) "ВКЛ" else "ВЫКЛ"}.", Ansi.DIM))
}

/** Задаёт демо-вопрос текущему профилю и печатает ответ как обычный ход. */
private fun demoAsk(agent: MemoryAgent, speed: Double) {
    demoType(DEMO_QUESTION, speed)
    when (val outcome = agent.processUserMessage(DEMO_QUESTION)) {
        is TurnOutcome.Ok -> printTurn(outcome)
        is TurnOutcome.Fail -> println(color("⨯ ${outcome.message}", Ansi.RED))
    }
}

/** Имитирует «напечатанную» строку ввода: тот же вид, что и реальный ввод пользователя. */
private fun demoType(text: String, speed: Double) {
    pause(0.5, speed)
    println(color("\nвы › ", Ansi.BOLD) + text)
    pause(0.4, speed)
}

private fun demoCaption(text: String, speed: Double) {
    pause(0.4, speed)
    println(color("\n─── $text ───", Ansi.MAGENTA))
    pause(1.1, speed)
}

private fun pause(seconds: Double, speed: Double) {
    runCatching { Thread.sleep((seconds * speed * 1000).toLong()) }
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
    println("Слои памяти в промпте: $rendered")
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

    println(color("\nПамять профиля «${agent.profile.name}»", Ansi.MAGENTA))

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

    println(color("\n[Долговременная] выучено в диалоге", layerColor(MemoryLayer.LONG_TERM)))
    if (long.profile.isEmpty() && long.constraints.isEmpty() && long.decisions.isEmpty() && long.knowledge.isEmpty()) {
        println(color("  (пусто)", Ansi.DIM))
    }
    if (long.profile.isNotEmpty()) {
        println("  Факты о пользователе:")
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
    println(color("\nфайлы памяти профиля: $s · $w · $l", Ansi.DIM))
    println(color("профиль (предпочтения): ${Config.PROFILES_DIR}/${agent.profile.id}.json", Ansi.DIM))
}

/** Полностью стирает состояние приложения (с подтверждением) и запускает интервью заново. */
private fun resetState(agent: MemoryAgent) {
    print(color("Стереть ВСЁ — все профили и их память — и начать с чистого листа? [y/N] ", Ansi.RED))
    System.out.flush()
    val ans = readlnOrNull()?.trim()?.lowercase()
    if (ans !in setOf("y", "yes", "д", "да")) {
        println(color("Отменено.", Ansi.DIM))
        return
    }
    agent.resetAll()
    lastPreview = null
    println(color("→ Состояние полностью очищено. Демо-профили пересозданы — как при первом запуске.", Ansi.GREEN))
    runOnboardingIfNew(agent)
}

/** Интервью запускаем, только если у пользователя ещё нет личного профиля «you». */
private fun runOnboardingIfNew(agent: MemoryAgent) {
    if (agent.hasProfile("you")) return
    runOnboarding(agent)
}

/**
 * Интервью: собираем персонализацию (контекст + стиль + формат) в личный профиль «you»
 * и делаем его активным. Ответы выбираются цифрой, Enter — первый вариант.
 */
private fun runOnboarding(agent: MemoryAgent) {
    println(
        color("\nкоуч ›", Ansi.BOLD) +
            " Настроим персонализацию — выбирайте цифрой (Enter — первый вариант). " +
            color("Готовые демо-профили доступны через /use anna|igor|maria.", Ansi.DIM),
    )
    var profile = UserProfile(id = "you", name = "Вы")
    for (q in ONBOARDING_QUESTIONS) {
        println("\n" + color(q.title, Ansi.BOLD))
        q.options.forEachIndexed { i, (_, label) -> println("  ${i + 1}) $label") }
        print(color("› ", Ansi.BOLD))
        System.out.flush()
        val line = readlnOrNull() ?: return // EOF — прерываем интервью
        val idx = line.trim().toIntOrNull()?.minus(1)?.takeIf { it in q.options.indices } ?: 0
        profile = q.apply(profile, q.options[idx].first)
    }
    profile = profile.copy(context = profile.context.copy(who = "личный профиль пользователя"))
    agent.createProfile(profile)
    println(color("\nкоуч ›", Ansi.BOLD) + " Профиль сохранён и подключён к каждому запросу:")
    printProfile(profile)
    println(
        color("\nкоуч ›", Ansi.BOLD) +
            " Готово! Спросите что-нибудь — отвечу под ваш профиль. " +
            "Попробуйте тот же вопрос на /use igor, чтобы увидеть разницу.",
    )
}

private fun printBanner(agent: MemoryAgent) {
    println(color("День 12 — персонализированный ассистент (профиль поверх модели памяти)", Ansi.BOLD))
    println(
        color(
            "${Config.model()} · окно ${Config.windowSize()} реплик · профиль подмешивается в каждый запрос",
            Ansi.DIM,
        ),
    )
    println(
        "Профиль: " + color("стиль · формат · ограничения · контекст", Ansi.MAGENTA) +
            "  поверх памяти: " +
            color("краткосрочная", layerColor(MemoryLayer.SHORT_TERM)) + " · " +
            color("рабочая", layerColor(MemoryLayer.WORKING)) + " · " +
            color("долговременная", layerColor(MemoryLayer.LONG_TERM)),
    )
    println(color("/help — команды · /profiles — профили · /persona off — сравнить без профиля", Ansi.DIM))
}
