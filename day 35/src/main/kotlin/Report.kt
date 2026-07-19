import java.nio.file.Files
import kotlin.io.path.writeText

/**
 * Отчёт конвейера — output/report.md: факты, проверки, решение модели,
 * итог публикации. Release notes уходят отдельным файлом (их читает gh).
 */
object Report {

    fun write(
        facts: RepoFacts,
        checks: List<Check>,
        draft: ReleaseDraft,
        verdict: GateVerdict,
        publish: PublishResult?,
        dryRun: Boolean,
    ) {
        Files.createDirectories(Config.outputDir())
        Config.releaseNotesFile().writeText(draft.notes + "\n")

        val md = buildString {
            appendLine("# День 35. Отчёт конвейера подготовки релиза")
            appendLine()
            appendLine("Режим: ${if (dryRun) "prepare (без публикации)" else "publish"}")
            appendLine()
            appendLine("## Факты репозитория")
            appendLine()
            appendLine("- Репозиторий: ${facts.ghRepo ?: facts.remoteUrl}")
            appendLine("- Ветка: ${facts.branch}, HEAD: `${facts.headSha}` «${facts.headSubject}»")
            appendLine("- Последний тег: ${facts.lastTag ?: "нет — первый релиз"}")
            appendLine("- Коммитов в релиз: ${facts.commits.size}, дней челленджа: ${facts.dayThemes.size}")
            appendLine()
            appendLine("## Преflight-проверки")
            appendLine()
            appendLine("| Проверка | Статус | Детали |")
            appendLine("|---|---|---|")
            checks.forEach { appendLine("| ${it.name} | ${it.status} | ${it.detail} |") }
            appendLine()
            appendLine("## Черновик релиза (DeepSeek)")
            appendLine()
            appendLine("- Версия: **${draft.version}** — ${draft.versionReason}")
            appendLine("- Заголовок: «${draft.title}»")
            appendLine("- Release notes: [`release-notes.md`](release-notes.md)")
            appendLine()
            appendLine("## Release gate (DeepSeek)")
            appendLine()
            appendLine("- Готовность: ${if (verdict.ready) "✅ GO" else "⛔ NO-GO"}")
            verdict.blockers.forEach { appendLine("- Блокер: $it") }
            verdict.warnings.forEach { appendLine("- Предупреждение: $it") }
            appendLine("- Резюме: ${verdict.summary}")
            appendLine()
            appendLine("## Итог")
            appendLine()
            if (publish != null) {
                appendLine("Релиз опубликован: тег `${publish.tag}`, ${publish.releaseUrl}")
            } else if (dryRun) {
                appendLine("Сухой прогон: тег не создавался, релиз не публиковался.")
                appendLine("Публикация: `./run.sh publish`.")
            } else {
                appendLine("Публикация не выполнена: есть блокеры (см. выше).")
            }
        }
        Config.reportFile().writeText(md)
    }
}
