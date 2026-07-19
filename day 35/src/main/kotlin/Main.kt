/**
 * День 35. Реальная задача: AI-конвейер подготовки релиза.
 *
 * Конвейер готовит и публикует итоговый релиз ЭТОГО репозитория:
 * факты из git → преflight-проверки → DeepSeek пишет release notes и
 * предлагает версию → DeepSeek-gate решает GO/NO-GO → gh release create.
 *
 * Режимы: prepare (по умолчанию, без публикации) | publish.
 */
fun main(args: Array<String>) {
    Config.loadDotEnv()
    val mode = args.firstOrNull() ?: "prepare"
    val dryRun = mode != "publish"

    val apiKey = Config.deepSeekApiKey()
    if (apiKey == null) {
        println("Нет DEEPSEEK_API_KEY (окружение или .env). Конвейер без AI-части не работает.")
        return
    }

    println("=== День 35. AI-конвейер подготовки релиза ===")
    println("Режим: ${if (dryRun) "prepare (сухой прогон)" else "publish"}")
    println()

    // 1. Факты — только детерминированно, из настоящего git/gh.
    println("[1/4] Собираю факты репозитория (git, gh)...")
    val facts = FactCollector.collect()
    println("      ${facts.ghRepo ?: facts.remoteUrl} · ветка ${facts.branch} · HEAD ${facts.headSha}")
    println("      коммитов в релиз: ${facts.commits.size} · дней челленджа: ${facts.dayThemes.size} · последний тег: ${facts.lastTag ?: "нет"}")
    println()

    // 2. Преflight — жёсткие проверки, которые модель не может «переубедить».
    println("[2/4] Преflight-проверки:")
    val checks = Preflight.run(facts)
    checks.forEach {
        val mark = when (it.status) {
            CheckStatus.OK -> "✅"
            CheckStatus.WARN -> "⚠️"
            CheckStatus.FAIL -> "⛔"
        }
        println("      $mark ${it.name}: ${it.detail}")
    }
    println()

    // 3. AI: черновик релиза + release gate.
    val agent = ReleaseAgent(apiKey)
    println("[3/4] DeepSeek готовит черновик релиза (версия + release notes)...")
    val draft = agent.draft(facts)
    println("      версия: ${draft.version} — ${draft.versionReason}")
    println("      заголовок: «${draft.title}»")
    println("      notes: ${draft.notes.lines().size} строк → output/release-notes.md")
    println()

    println("[4/4] DeepSeek выносит вердикт release gate...")
    val verdict = agent.gate(facts, checks, draft)
    println("      ${if (verdict.ready) "✅ GO" else "⛔ NO-GO"} — ${verdict.summary}")
    verdict.blockers.forEach { println("      блокер: $it") }
    verdict.warnings.forEach { println("      предупреждение: $it") }
    println()

    // 4. Публикация — только в режиме publish и только без блокеров.
    var publish: PublishResult? = null
    if (!dryRun) {
        when {
            Preflight.hasBlockers(checks) ->
                println("Публикация остановлена: преflight с FAIL-ами.")
            !verdict.ready ->
                println("Публикация остановлена: release gate дал NO-GO.")
            Publisher.tagExists(draft.version) ->
                println("Публикация остановлена: тег ${draft.version} уже существует.")
            else -> {
                println("Публикую: тег ${draft.version} + GitHub Release...")
                publish = Publisher.publish(draft)
                println("Готово: ${publish.releaseUrl}")
            }
        }
        println()
    }

    Report.write(facts, checks, draft, verdict, publish, dryRun)
    println("Отчёт: output/report.md · Release notes: output/release-notes.md")
    if (dryRun) println("Публикация: ./run.sh publish")
}
