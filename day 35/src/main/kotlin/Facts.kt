import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.exists
import kotlin.io.path.name
import kotlin.io.path.readLines

/** Результат запуска внешней команды (git, gh). */
data class ProcResult(val exitCode: Int, val stdout: String, val stderr: String) {
    val ok: Boolean get() = exitCode == 0
}

/** Запускает команду в каталоге репозитория и собирает вывод. */
fun exec(vararg cmd: String, dir: Path = Config.repoRoot(), timeoutSec: Long = 60): ProcResult {
    val process = ProcessBuilder(*cmd)
        .directory(dir.toFile())
        .redirectErrorStream(false)
        .start()
    val out = process.inputStream.bufferedReader().readText()
    val err = process.errorStream.bufferedReader().readText()
    if (!process.waitFor(timeoutSec, TimeUnit.SECONDS)) {
        process.destroyForcibly()
        return ProcResult(-1, out, "timeout after ${timeoutSec}s")
    }
    return ProcResult(process.exitValue(), out.trim(), err.trim())
}

/** Один коммит истории: hash, дата, заголовок. */
data class Commit(val sha: String, val date: String, val subject: String)

/** Тема одного дня челленджа — первая строка README. */
data class DayTheme(val day: Int, val title: String)

/**
 * Все факты о репозитории, собранные детерминированно перед релизом.
 * Именно они уходят и в преflight-проверки, и в контекст LLM.
 */
data class RepoFacts(
    val remoteUrl: String,
    val branch: String,
    val headSha: String,
    val headSubject: String,
    val lastTag: String?,
    val commits: List<Commit>,
    val dirtyFiles: List<String>,
    val aheadOfRemote: Int,
    val behindRemote: Int,
    val dayThemes: List<DayTheme>,
    val ghAuthenticated: Boolean,
    val ghRepo: String?,
)

/** Собирает факты о репозитории: git-история, состояние дерева, дни, gh. */
object FactCollector {

    fun collect(): RepoFacts {
        val root = Config.repoRoot()

        val remoteUrl = exec("git", "remote", "get-url", "origin").stdout
        val branch = exec("git", "branch", "--show-current").stdout
        val headSha = exec("git", "rev-parse", "--short", "HEAD").stdout
        val headSubject = exec("git", "log", "-1", "--pretty=%s").stdout
        val lastTag = exec("git", "describe", "--tags", "--abbrev=0")
            .takeIf { it.ok }?.stdout?.takeIf { it.isNotBlank() }

        val range = lastTag?.let { "$it..HEAD" }
        val logArgs = buildList {
            addAll(listOf("git", "log", "--pretty=%h%x09%ad%x09%s", "--date=short"))
            range?.let { add(it) }
        }
        val commits = exec(*logArgs.toTypedArray()).stdout.lines()
            .filter { it.isNotBlank() }
            .map { line ->
                val parts = line.split('\t', limit = 3)
                Commit(parts[0], parts.getOrElse(1) { "" }, parts.getOrElse(2) { "" })
            }

        val dirtyFiles = exec("git", "status", "--porcelain").stdout.lines()
            .filter { it.isNotBlank() }

        // ahead/behind относительно origin/<branch> без fetch — по локальному состоянию.
        val counts = exec("git", "rev-list", "--left-right", "--count", "origin/$branch...HEAD")
        val (behind, ahead) = if (counts.ok) {
            val parts = counts.stdout.split(Regex("\\s+"))
            (parts.getOrNull(0)?.toIntOrNull() ?: 0) to (parts.getOrNull(1)?.toIntOrNull() ?: 0)
        } else 0 to 0

        val ghAuth = exec("gh", "auth", "status").ok
        val ghRepo = exec("gh", "repo", "view", "--json", "nameWithOwner", "-q", ".nameWithOwner")
            .takeIf { it.ok }?.stdout?.takeIf { it.isNotBlank() }

        return RepoFacts(
            remoteUrl = remoteUrl,
            branch = branch,
            headSha = headSha,
            headSubject = headSubject,
            lastTag = lastTag,
            commits = commits,
            dirtyFiles = dirtyFiles,
            aheadOfRemote = ahead,
            behindRemote = behind,
            dayThemes = collectDayThemes(root),
            ghAuthenticated = ghAuth,
            ghRepo = ghRepo,
        )
    }

    /** Первая строка README каждого дня — темы всех 35 дней для контекста LLM. */
    private fun collectDayThemes(root: Path): List<DayTheme> =
        Files.list(root).use { stream ->
            stream.filter { it.name.matches(Regex("day \\d+")) }
                .map { dir ->
                    val day = dir.name.removePrefix("day ").trim().toInt()
                    val readme = dir.resolve("README.md")
                    val title = if (readme.exists()) {
                        readme.readLines().firstOrNull { it.startsWith("#") }
                            ?.trimStart('#', ' ')?.trim() ?: "(без README)"
                    } else "(без README)"
                    DayTheme(day, title)
                }
                .sorted(compareBy { it.day })
                .toList()
        }
}
