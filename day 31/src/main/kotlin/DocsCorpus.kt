import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.io.path.isDirectory
import kotlin.io.path.name
import kotlin.io.path.readText

/** Один документ корпуса: путь от корня репозитория и содержимое. */
data class Doc(val relPath: String, val text: String)

/** Чанк до эмбеддинга: раздел markdown с метаданными происхождения. */
data class DocChunk(
    val chunkId: String,
    val source: String,
    val title: String,
    val section: String,
    val charStart: Int,
    val charEnd: Int,
    val text: String,
)

/**
 * Корпус документации проекта: все README.md репозитория (у каждого дня свой)
 * плюс папка docs дня 31 со схемами данных и API-описанием MCP.
 * Гранулярность — раздел markdown (см. docs/index-format.md): вопрос обычно
 * бьёт в конкретный раздел («Как устроено», «Запуск»), а не в файл целиком.
 */
object DocsCorpus {
    const val STRATEGY = "markdown-section"

    /** Собирает документы: README каждого дня + markdown из day 31/docs. */
    fun collect(repoRoot: Path): List<Doc> {
        val docs = mutableListOf<Doc>()
        Files.list(repoRoot).use { entries ->
            entries.filter { it.isDirectory() && it.name.startsWith("day ") }
                .sorted(compareBy { it.name.removePrefix("day ").trim().toIntOrNull() ?: 0 })
                .forEach { dayDir ->
                    val readme = dayDir.resolve("README.md")
                    if (Files.exists(readme)) {
                        docs += Doc("${dayDir.name}/README.md", readme.readText())
                    }
                }
        }
        val docsDir = repoRoot.resolve("day 31/docs")
        if (Files.exists(docsDir)) {
            Files.list(docsDir).use { entries ->
                entries.filter { it.name.endsWith(".md") }.sorted()
                    .forEach { docs += Doc("day 31/docs/${it.name}", it.readText()) }
            }
        }
        return docs
    }

    /**
     * Режет документ по заголовкам markdown; разделы длиннее maxChars дорезаются
     * по абзацам. Заголовки документа и раздела добавляются в текст чанка —
     * вектор должен знать, к какому дню и разделу относится текст.
     */
    fun chunk(doc: Doc, maxChars: Int): List<DocChunk> {
        val lines = doc.text.lines()
        val title = lines.firstOrNull { it.startsWith("# ") }?.removePrefix("# ")?.trim()
            ?: doc.relPath

        data class Section(val heading: String, val start: Int, val body: StringBuilder)

        val sections = mutableListOf(Section("", 0, StringBuilder()))
        var offset = 0
        lines.forEach { line ->
            if (Regex("^#{1,3} ").containsMatchIn(line)) {
                sections += Section(line.trimStart('#').trim(), offset, StringBuilder())
            }
            sections.last().body.appendLine(line)
            offset += line.length + 1
        }

        val chunks = mutableListOf<DocChunk>()
        sections.filter { it.body.toString().isNotBlank() }.forEach { section ->
            val pieces = splitByParagraphs(section.body.toString().trim(), maxChars)
            var pieceStart = section.start
            pieces.forEach { piece ->
                val header = if (section.heading.isEmpty() || section.heading == title) title
                else "$title — ${section.heading}"
                chunks += DocChunk(
                    chunkId = "${doc.relPath}#${chunks.size}",
                    source = doc.relPath,
                    title = title,
                    section = section.heading.ifEmpty { title },
                    charStart = pieceStart,
                    charEnd = pieceStart + piece.length,
                    text = "$header\n$piece",
                )
                pieceStart += piece.length
            }
        }
        return chunks
    }

    private fun splitByParagraphs(text: String, maxChars: Int): List<String> {
        if (text.length <= maxChars) return listOf(text)
        val result = mutableListOf<String>()
        val current = StringBuilder()
        text.split("\n\n").forEach { para ->
            if (current.isNotEmpty() && current.length + para.length + 2 > maxChars) {
                result += current.toString().trim()
                current.clear()
            }
            current.append(para).append("\n\n")
        }
        if (current.isNotBlank()) result += current.toString().trim()
        return result
    }

    /** SHA-256 всего корпуса — индекс пересчитывается только при смене документации. */
    fun corpusSha(docs: List<Doc>): String {
        val digest = MessageDigest.getInstance("SHA-256")
        docs.forEach {
            digest.update(it.relPath.toByteArray())
            digest.update(it.text.toByteArray())
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
