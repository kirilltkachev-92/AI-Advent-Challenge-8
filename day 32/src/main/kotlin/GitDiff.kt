import java.nio.file.Path
import java.util.concurrent.TimeUnit

/** Изменённый файл PR: статус (A/M/D/R…) и путь. */
data class ChangedFile(val status: String, val path: String)

/** Diff PR целиком: изменённые файлы и текст патча (обрезанный под бюджет). */
data class PrDiff(val files: List<ChangedFile>, val patch: String, val truncated: Boolean)

/**
 * Получение диффа и списка изменённых файлов — то, что ассистент «получает
 * на вход» по заданию. Всё через настоящий git: в Actions репозиторий уже
 * выкачан (fetch-depth: 0), локально работает на любом паре коммитов/веток.
 */
object GitDiff {

    fun repoRoot(): Path = Path.of(git(Path.of("."), "rev-parse", "--show-toplevel"))

    /** three-dot diff: изменения ветки PR относительно общего предка с base. */
    fun between(repoRoot: Path, base: String, head: String): PrDiff {
        val nameStatus = git(repoRoot, "diff", "--name-status", "$base...$head")
        val files = nameStatus.lines().filter { it.isNotBlank() }.map { line ->
            val parts = line.split(Regex("\\s+"), limit = 2)
            ChangedFile(status = parts[0].take(1), path = parts.getOrElse(1) { "" })
        }
        val patch = git(repoRoot, "diff", "$base...$head")
        val limit = Config.maxDiffChars()
        val truncated = patch.length > limit
        return PrDiff(
            files = files,
            patch = if (truncated) patch.take(limit) + "\n… (diff обрезан)" else patch,
            truncated = truncated,
        )
    }

    fun git(workDir: Path, vararg args: String): String {
        val process = ProcessBuilder("git", *args)
            .directory(workDir.toFile())
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.readBytes().toString(Charsets.UTF_8).trim()
        check(process.waitFor(30, TimeUnit.SECONDS)) { "git ${args.joinToString(" ")} — таймаут" }
        check(process.exitValue() == 0) { "git ${args.joinToString(" ")} → ${output.take(300)}" }
        return output
    }
}
