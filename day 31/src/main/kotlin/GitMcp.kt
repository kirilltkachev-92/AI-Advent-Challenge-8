import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/**
 * Инструменты MCP-сервера — обёртки над настоящими командами git в корне
 * репозитория (описание API — docs/mcp-tools.md). Минимум по заданию —
 * git_branch; список файлов и diff — «по желанию», здесь они тоже есть.
 */
object GitMcp {

    /** Корень репозитория: спрашиваем сам git, cwd дня 31 — подкаталог. */
    fun repoRoot(): Path = Path.of(git(Path.of("."), "rev-parse", "--show-toplevel"))

    fun server(repoRoot: Path): McpServer {
        val server = McpServer()

        server.register(
            McpToolDef(
                name = "git_branch",
                description = "Возвращает текущую git-ветку репозитория.",
                inputSchema = noParams(),
            ) { _ ->
                "Текущая ветка: ${git(repoRoot, "rev-parse", "--abbrev-ref", "HEAD")}"
            },
        )

        server.register(
            McpToolDef(
                name = "git_status",
                description = "Краткое состояние рабочего дерева: ветка, изменённые и новые файлы.",
                inputSchema = noParams(),
            ) { _ ->
                git(repoRoot, "status", "--porcelain=v1", "--branch").ifBlank { "Рабочее дерево чистое" }
            },
        )

        server.register(
            McpToolDef(
                name = "git_files",
                description = "Список файлов, отслеживаемых git. Можно ограничить подкаталогом.",
                inputSchema = buildJsonObject {
                    put("type", "object")
                    putJsonObject("properties") {
                        putJsonObject("dir") {
                            put("type", "string")
                            put("description", "Подкаталог репозитория, например «day 31». Пусто — весь репозиторий.")
                        }
                    }
                },
            ) { args ->
                val dir = args["dir"]?.jsonPrimitive?.content?.trim().orEmpty()
                // --others --exclude-standard: ещё не закоммиченные файлы тоже видны
                val lsArgs = arrayOf("ls-files", "--cached", "--others", "--exclude-standard")
                val out = if (dir.isEmpty()) git(repoRoot, *lsArgs)
                else git(repoRoot, *lsArgs, "--", dir)
                val files = out.lines().filter { it.isNotBlank() }
                val shown = files.take(200)
                buildString {
                    appendLine("Файлов: ${files.size}${if (dir.isNotEmpty()) " (в «$dir»)" else ""}")
                    shown.forEach { appendLine(it) }
                    if (files.size > shown.size) append("… и ещё ${files.size - shown.size}")
                }.trim()
            },
        )

        server.register(
            McpToolDef(
                name = "git_diff",
                description = "Diff незакоммиченных изменений: сводка по файлам и патч. Можно ограничить путём.",
                inputSchema = buildJsonObject {
                    put("type", "object")
                    putJsonObject("properties") {
                        putJsonObject("path") {
                            put("type", "string")
                            put("description", "Файл или каталог. Пусто — все изменения.")
                        }
                    }
                },
            ) { args ->
                val path = args["path"]?.jsonPrimitive?.content?.trim().orEmpty()
                val statArgs = if (path.isEmpty()) arrayOf("diff", "--stat") else arrayOf("diff", "--stat", "--", path)
                val diffArgs = if (path.isEmpty()) arrayOf("diff") else arrayOf("diff", "--", path)
                val stat = git(repoRoot, *statArgs)
                if (stat.isBlank()) "Незакоммиченных изменений нет"
                else {
                    val diff = git(repoRoot, *diffArgs)
                    val cut = if (diff.length > 4000) diff.take(4000) + "\n… (обрезано)" else diff
                    "$stat\n\n$cut"
                }
            },
        )

        server.register(
            McpToolDef(
                name = "git_log",
                description = "Последние коммиты репозитория (git log --oneline).",
                inputSchema = buildJsonObject {
                    put("type", "object")
                    putJsonObject("properties") {
                        putJsonObject("count") {
                            put("type", "integer")
                            put("description", "Сколько коммитов показать (по умолчанию 10, максимум 50).")
                        }
                    }
                },
            ) { args ->
                val count = (args["count"]?.jsonPrimitive?.int ?: 10).coerceIn(1, 50)
                git(repoRoot, "log", "--oneline", "-n", count.toString())
            },
        )

        return server
    }

    private fun noParams(): JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {}
        putJsonArray("required") {}
    }

    private fun git(workDir: Path, vararg args: String): String {
        val process = ProcessBuilder("git", *args)
            .directory(workDir.toFile())
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.readBytes().toString(Charsets.UTF_8).trim()
        check(process.waitFor(15, TimeUnit.SECONDS)) { "git ${args.joinToString(" ")} — таймаут" }
        check(process.exitValue() == 0) { "git ${args.joinToString(" ")} → ${output.take(200)}" }
        return output
    }
}
