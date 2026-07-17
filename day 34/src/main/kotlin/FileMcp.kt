import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.readText
import kotlin.io.path.relativeTo
import kotlin.io.path.writeText
import kotlin.streams.toList

/**
 * MCP-инструменты над файлами проекта: список, чтение, поиск по многим
 * файлам и запись. Это «руки» ассистента — сам он видит только результаты
 * tools/call, никакого прямого доступа к диску у модели нет.
 *
 * Песочница: все пути — относительные, разрешаются строго внутри рабочей
 * копии проекта (output/project); попытка выйти выше корня — ошибка.
 * write_file возвращает unified diff изменения — так «изменения сохраняются
 * И выводятся как diff» из задания выполняются одним инструментом.
 */
class FileMcp(private val root: Path) {

    /** Журнал изменений за сессию: путь → diff. Показывается командой /diff и в отчёте. */
    val changes = mutableListOf<Pair<String, String>>()

    fun server(): McpServer = McpServer()
        .register(
            McpToolDef(
                name = "list_files",
                description = "Список всех файлов проекта (относительные пути и размер в строках). " +
                    "С этого стоит начинать, чтобы понять структуру.",
                inputSchema = schema(required = emptyList()),
            ) { _ ->
                walk().joinToString("\n") { p ->
                    val rel = p.relativeTo(root)
                    "$rel (${Files.readAllLines(p).size} строк)"
                } .ifEmpty { "Проект пуст" }
            },
        )
        .register(
            McpToolDef(
                name = "read_file",
                description = "Прочитать файл целиком; строки пронумерованы, чтобы на них можно было ссылаться.",
                inputSchema = schema(
                    "path" to prop("string", "Относительный путь, например «src/Main.kt»"),
                    required = listOf("path"),
                ),
            ) { args ->
                val p = resolve(args.str("path"))
                check(p.isRegularFile()) { "Файл «${args.str("path")}» не найден" }
                p.readText().lines()
                    .mapIndexed { i, line -> "${i + 1}: $line" }
                    .joinToString("\n")
            },
        )
        .register(
            McpToolDef(
                name = "search_files",
                description = "Поиск подстроки по всем файлам проекта (без учёта регистра). " +
                    "Возвращает совпадения в формате «файл:строка: текст».",
                inputSchema = schema(
                    "query" to prop("string", "Что искать, например «HttpFetcher»"),
                    required = listOf("query"),
                ),
            ) { args ->
                val query = args.str("query").lowercase()
                val hits = walk().flatMap { p ->
                    p.readText().lines().mapIndexedNotNull { i, line ->
                        if (line.lowercase().contains(query)) "${p.relativeTo(root)}:${i + 1}: ${line.trim()}"
                        else null
                    }
                }
                if (hits.isEmpty()) "Совпадений по «${args.str("query")}» нет"
                else hits.joinToString("\n")
            },
        )
        .register(
            McpToolDef(
                name = "write_file",
                description = "Создать или полностью перезаписать файл новым содержимым. " +
                    "Возвращает unified diff изменения. Передавать нужно ПОЛНЫЙ новый текст файла.",
                inputSchema = schema(
                    "path" to prop("string", "Относительный путь, например «docs/README.md»"),
                    "content" to prop("string", "Полное новое содержимое файла"),
                    required = listOf("path", "content"),
                ),
            ) { args ->
                val relPath = args.str("path")
                val p = resolve(relPath)
                val oldText = if (p.isRegularFile()) p.readText() else ""
                val newText = args.str("content").let { if (it.endsWith("\n")) it else it + "\n" }
                Files.createDirectories(p.parent)
                p.writeText(newText)
                val diff = if (oldText.isEmpty()) {
                    "новый файл $relPath (${newText.lines().size - 1} строк)"
                } else {
                    DiffUtil.unified(oldText, newText, relPath).ifEmpty { "без изменений" }
                }
                changes += relPath to diff
                "Записано: $relPath\n$diff"
            },
        )

    // --- песочница и обход -----------------------------------------------------

    private fun resolve(rel: String): Path {
        val p = root.resolve(rel).normalize()
        check(p.startsWith(root.normalize())) { "Путь «$rel» выходит за пределы проекта" }
        return p
    }

    private fun walk(): List<Path> = Files.walk(root).use { stream ->
        stream.filter { it.isRegularFile() && !it.isDirectory() }
            .toList()
            .sortedBy { it.relativeTo(root).toString() }
    }

    // --- JSON Schema помощники (как в Дне 33) ----------------------------------

    private fun prop(type: String, description: String): JsonObject = buildJsonObject {
        put("type", type)
        put("description", description)
    }

    private fun schema(vararg props: Pair<String, JsonObject>, required: List<String>): JsonObject =
        buildJsonObject {
            put("type", "object")
            putJsonObject("properties") { props.forEach { (name, p) -> put(name, p) } }
            putJsonArray("required") { required.forEach { add(JsonPrimitive(it)) } }
        }

    private fun JsonObject.str(key: String): String =
        this[key]?.jsonPrimitive?.content
            ?: error("Не передан обязательный аргумент «$key»")

    companion object {
        /** Восстанавливает рабочую копию проекта из шаблона (reset). */
        fun resetWorkDir(template: Path, workDir: Path) {
            if (Files.exists(workDir)) {
                Files.walk(workDir).use { s ->
                    s.sorted(Comparator.reverseOrder()).forEach(Files::delete)
                }
            }
            Files.walk(template).use { s ->
                s.forEach { src ->
                    val dst = workDir.resolve(template.relativize(src))
                    if (Files.isDirectory(src)) Files.createDirectories(dst)
                    else Files.copy(src, dst)
                }
            }
        }
    }
}
