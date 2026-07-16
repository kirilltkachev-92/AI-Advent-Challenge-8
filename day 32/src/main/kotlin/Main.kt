import java.nio.file.Files

/**
 * День 32 — автоматизация ревью кода.
 *
 * Режимы:
 *   index                 — построить/обновить индекс документации и кода;
 *   review <base> [head]  — локальное ревью диффа (печать + output/report.md);
 *   ci                    — GitHub Actions: событие PR → diff → RAG → DeepSeek →
 *                           коммент в PR.
 */
fun main(args: Array<String>) {
    Config.loadDotEnv()
    val mode = args.firstOrNull() ?: "index"

    val embedder = OllamaEmbedder()
    val repoRoot = GitDiff.repoRoot()
    val index = Indexer.buildOrLoad(repoRoot, embedder)
    if (mode == "index") return

    val apiKey = Config.deepSeekApiKey()
        ?: error("Нет DEEPSEEK_API_KEY: локально — .env, в Actions — secrets.DEEPSEEK_API_KEY")
    val reviewer = Reviewer(index, embedder, DeepSeekClient(apiKey))

    when (mode) {
        "review" -> {
            val base = args.getOrNull(1) ?: error("Использование: ./run.sh review <base> [head]")
            val head = args.getOrNull(2) ?: "HEAD"
            val diff = GitDiff.between(repoRoot, base, head)
            check(diff.files.isNotEmpty()) { "Между $base и $head нет изменений" }
            println("→ Diff $base...$head: ${diff.files.size} файлов")
            val review = reviewer.review(diff)
            println()
            println(review.markdown)
            println()
            println("→ Источники RAG: ${review.retrievedSources.joinToString(", ")}")
            saveReport(base, head, diff, review)
        }

        "ci" -> {
            val eventPath = Config.githubEventPath() ?: error("Нет GITHUB_EVENT_PATH — режим ci только для Actions")
            val token = Config.githubToken() ?: error("Нет GITHUB_TOKEN")
            val repo = Config.githubRepo() ?: error("Нет GITHUB_REPOSITORY")
            val event = GitHubClient.readPrEvent(eventPath)
            println("→ PR #${event.number} «${event.title}»: ${event.baseSha.take(7)}...${event.headSha.take(7)}")

            val diff = GitDiff.between(repoRoot, event.baseSha, event.headSha)
            if (diff.files.isEmpty()) {
                println("→ Пустой diff — ревью не требуется")
                return
            }
            println("→ Изменённых файлов: ${diff.files.size}")
            val review = reviewer.review(diff)

            val comment = buildString {
                appendLine("## 🤖 AI-ревью (день 32)")
                appendLine()
                appendLine(review.markdown)
                appendLine()
                appendLine("---")
                appendLine(
                    "_Контекст RAG: ${review.retrievedSources.joinToString(", ") { "`$it`" }} · " +
                        "токены: ${review.promptTokens} промпт / ${review.answerTokens} ответ · " +
                        "модель ${Config.deepSeekModel()}_",
                )
            }
            GitHubClient(token, repo).postPrComment(event.number, comment)
        }

        else -> error("Неизвестный режим «$mode» (index | review | ci)")
    }
}

/** Локальный прогон сохраняется как отчёт дня — output/report.md. */
private fun saveReport(base: String, head: String, diff: PrDiff, review: ReviewResult) {
    val sb = StringBuilder()
    sb.appendLine("# День 32. Автоматизация ревью кода — отчёт")
    sb.appendLine()
    sb.appendLine("Локальный прогон того же пайплайна, что запускается в GitHub Actions")
    sb.appendLine("(`.github/workflows/ai-review.yml`, реактивно по `on: pull_request`).")
    sb.appendLine()
    sb.appendLine("## Вход: diff `$base...$head`")
    sb.appendLine()
    diff.files.forEach { sb.appendLine("- [${it.status}] `${it.path}`") }
    if (diff.truncated) sb.appendLine("- _(diff обрезан до ${Config.maxDiffChars()} символов)_")
    sb.appendLine()
    sb.appendLine("## Контекст RAG (документация + код)")
    sb.appendLine()
    review.retrievedSources.forEach { sb.appendLine("- `$it`") }
    sb.appendLine()
    sb.appendLine("## Ревью")
    sb.appendLine()
    sb.appendLine(review.markdown)
    sb.appendLine()
    sb.appendLine("---")
    sb.appendLine("_Токены: ${review.promptTokens} промпт / ${review.answerTokens} ответ · модель ${Config.deepSeekModel()}_")

    val path = Config.outputDir().resolve("report.md")
    Files.createDirectories(path.parent)
    Files.writeString(path, sb.toString())
    println("→ Отчёт: $path")
}
