import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * Хранит состояние задачи в одном читаемом Markdown-файле (`state/task.md`): запрос, текущий
 * этап, артефакты этапов и историю переходов. Благодаря этому работу можно прервать на любом
 * этапе и продолжить позже — артефакты не пересчитываются.
 *
 * Секции размечены HTML-комментариями `<!-- … -->`: это валидный Markdown, который надёжно
 * переживает многострочные артефакты (код, планы).
 */
class TaskStore(rootDir: Path = Path.of(Config.STATE_DIR)) {
    val file: Path = rootDir.resolve("task.md")

    init {
        runCatching { rootDir.createDirectories() }
    }

    fun save(s: TaskSnapshot) = runCatching { file.writeText(serialize(s)) }

    fun load(): TaskSnapshot? =
        if (file.exists()) runCatching { parse(file.readText()) }.getOrNull() else null

    fun delete() = runCatching { Files.deleteIfExists(file) }

    private fun serialize(s: TaskSnapshot): String = buildString {
        appendLine("# Состояние задачи (конечный автомат)")
        appendLine("<!-- stage:${s.stage} -->")
        appendLine()
        appendLine("<!-- request -->")
        appendLine(s.request ?: "")
        s.artifacts.forEach { (stageId, content) ->
            appendLine()
            appendLine("<!-- artifact:$stageId -->")
            appendLine(content)
        }
        appendLine()
        appendLine("<!-- log -->")
        s.log.forEach { appendLine("- $it") }
    }

    private fun parse(text: String): TaskSnapshot {
        val stage = Regex("<!-- stage:(\\w+) -->").find(text)?.groupValues?.get(1)
            ?: TaskStage.INITIAL.id
        val markers = Regex("<!-- (request|artifact:\\w+|log) -->").findAll(text).toList()

        var request: String? = null
        val artifacts = linkedMapOf<String, String>()
        val log = mutableListOf<String>()

        markers.forEachIndexed { i, m ->
            val start = m.range.last + 1
            val end = if (i + 1 < markers.size) markers[i + 1].range.first else text.length
            val body = text.substring(start, end).trim()
            val tag = m.groupValues[1]
            when {
                tag == "request" -> request = body.ifBlank { null }
                tag.startsWith("artifact:") -> artifacts[tag.removePrefix("artifact:")] = body
                tag == "log" -> body.lineSequence()
                    .map { it.trim() }
                    .filter { it.startsWith("- ") }
                    .forEach { log += it.removePrefix("- ").trim() }
            }
        }
        return TaskSnapshot(request = request, stage = stage, artifacts = artifacts, log = log)
    }
}
