import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * Каждый слой памяти лежит в ОТДЕЛЬНОМ Markdown-файле — физическое разделение хранилищ,
 * причём содержимое можно открыть и прочитать глазами.
 *   memory/short-term.md  — текущий диалог (эфемерный, чистится новой сессией)
 *   memory/working.md     — данные текущей задачи
 *   memory/long-term.md   — профиль, ограничения, решения, знания
 *
 * Диалог в short-term.md разделён машинными маркерами `<!-- msg: role -->`: это валидный
 * Markdown (HTML-комментарий), который не ломает чтение и надёжно переживает многострочные
 * ответы ассистента.
 */
class MemoryStore(rootDir: Path = Path.of(Config.MEMORY_DIR)) {

    val shortTermFile: Path = rootDir.resolve("short-term.md")
    val workingFile: Path = rootDir.resolve("working.md")
    val longTermFile: Path = rootDir.resolve("long-term.md")

    init {
        runCatching { rootDir.createDirectories() }
    }

    fun saveShortTerm(snapshot: ShortTermSnapshot) = write(shortTermFile, serializeShortTerm(snapshot))
    fun saveWorking(snapshot: WorkingSnapshot) = write(workingFile, serializeWorking(snapshot))
    fun saveLongTerm(snapshot: LongTermSnapshot) = write(longTermFile, serializeLongTerm(snapshot))

    fun loadShortTerm(): ShortTermSnapshot? = read(shortTermFile)?.let {
        runCatching { parseShortTerm(it) }.getOrNull()
    }

    fun loadWorking(): WorkingSnapshot? = read(workingFile)?.let {
        runCatching { parseWorking(it) }.getOrNull()
    }

    fun loadLongTerm(): LongTermSnapshot? = read(longTermFile)?.let {
        runCatching { parseLongTerm(it) }.getOrNull()
    }

    fun deleteShortTerm() = runCatching { Files.deleteIfExists(shortTermFile) }
    fun deleteWorking() = runCatching { Files.deleteIfExists(workingFile) }

    // ---------- Краткосрочная память ----------

    private fun serializeShortTerm(s: ShortTermSnapshot): String = buildString {
        appendLine("# Краткосрочная память")
        appendLine("<!-- window: ${s.windowSize} -->")
        s.messages.forEach { m ->
            appendLine()
            appendLine("<!-- msg: ${m.role} -->")
            appendLine(m.content)
        }
    }

    private fun parseShortTerm(text: String): ShortTermSnapshot {
        val window = Regex("<!-- window: (\\d+) -->").find(text)
            ?.groupValues?.get(1)?.toIntOrNull() ?: Config.windowSize()
        val markers = Regex("<!-- msg: (\\w+) -->").findAll(text).toList()
        val messages = markers.mapIndexed { i, m ->
            val start = m.range.last + 1
            val end = if (i + 1 < markers.size) markers[i + 1].range.first else text.length
            ChatMessage(role = m.groupValues[1], content = text.substring(start, end).trim())
        }
        return ShortTermSnapshot(windowSize = window, messages = messages)
    }

    // ---------- Рабочая память ----------

    private fun serializeWorking(s: WorkingSnapshot): String = buildString {
        appendLine("# Рабочая память")
        appendLine()
        if (s.taskTitle != null) appendLine("- Задача: ${s.taskTitle}")
        appendLine("- Стадия: ${s.stage}")
        appendLine()
        appendLine("## Требования")
        s.requirements.forEach { appendLine("- $it") }
        appendLine()
        appendLine("## Заметки")
        s.notes.forEach { appendLine("- $it") }
    }

    private fun parseWorking(text: String): WorkingSnapshot {
        var taskTitle: String? = null
        var stage = TaskStage.CLARIFY.id
        val requirements = mutableListOf<String>()
        val notes = mutableListOf<String>()
        var section = ""
        text.lineSequence().forEach { raw ->
            val line = raw.trim()
            when {
                line.startsWith("## ") -> section = line.removePrefix("## ").trim()
                line.startsWith("- ") -> {
                    val item = line.removePrefix("- ").trim()
                    when (section) {
                        "" -> when {
                            item.startsWith("Задача:") ->
                                taskTitle = item.removePrefix("Задача:").trim().ifBlank { null }
                            item.startsWith("Стадия:") ->
                                stage = item.removePrefix("Стадия:").trim().ifBlank { stage }
                        }
                        "Требования" -> if (item.isNotEmpty()) requirements += item
                        "Заметки" -> if (item.isNotEmpty()) notes += item
                    }
                }
            }
        }
        return WorkingSnapshot(taskTitle = taskTitle, stage = stage, requirements = requirements, notes = notes)
    }

    // ---------- Долговременная память ----------

    private fun serializeLongTerm(s: LongTermSnapshot): String = buildString {
        appendLine("# Долговременная память")
        appendLine()
        appendLine("## Профиль")
        s.profile.forEach { (k, v) -> appendLine("- $k: $v") }
        appendLine()
        appendLine("## Ограничения")
        s.constraints.forEach { (k, v) -> appendLine("- $k: $v") }
        appendLine()
        appendLine("## Решения")
        s.decisions.forEach { appendLine("- $it") }
        appendLine()
        appendLine("## Знания")
        s.knowledge.forEach { appendLine("- $it") }
    }

    private fun parseLongTerm(text: String): LongTermSnapshot {
        val profile = linkedMapOf<String, String>()
        val constraints = linkedMapOf<String, String>()
        val decisions = mutableListOf<String>()
        val knowledge = mutableListOf<String>()
        var section = ""
        text.lineSequence().forEach { raw ->
            val line = raw.trim()
            when {
                line.startsWith("## ") -> section = line.removePrefix("## ").trim()
                line.startsWith("- ") -> {
                    val item = line.removePrefix("- ").trim()
                    when (section) {
                        "Профиль" -> splitKv(item)?.let { (k, v) -> profile[k] = v }
                        "Ограничения" -> splitKv(item)?.let { (k, v) -> constraints[k] = v }
                        "Решения" -> if (item.isNotEmpty()) decisions += item
                        "Знания" -> if (item.isNotEmpty()) knowledge += item
                    }
                }
            }
        }
        return LongTermSnapshot(
            profile = profile,
            constraints = constraints,
            knowledge = knowledge,
            decisions = decisions,
        )
    }

    /** Разбивает строку вида `ключ: значение` по первому `": "`. */
    private fun splitKv(item: String): Pair<String, String>? {
        val idx = item.indexOf(": ")
        if (idx <= 0) return null
        return item.substring(0, idx).trim() to item.substring(idx + 2).trim()
    }

    private fun write(path: Path, content: String) {
        runCatching { path.writeText(content) }
    }

    private fun read(path: Path): String? =
        if (path.exists()) runCatching { path.readText() }.getOrNull() else null
}
