import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.io.path.isDirectory
import kotlin.io.path.name
import kotlin.io.path.readText

/** Один документ корпуса: путь от корня репозитория, содержимое и тип (docs | code). */
data class Doc(val relPath: String, val text: String, val kind: String)

/** Чанк до эмбеддинга. */
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
 * Корпус Дня 32 — документация И код, как требует задание:
 * README всех дней + папки docs (markdown, чанк = раздел) и все исходники
 * Kotlin (чанк = окно ~60 строк с путём файла в заголовке). Ревьюер достаёт
 * отсюда конвенции проекта и похожий код из других дней.
 */
object RepoCorpus {
    const val STRATEGY = "markdown-section+code-window"
    private const val CODE_WINDOW_LINES = 60

    fun collect(repoRoot: Path): List<Doc> {
        val docs = mutableListOf<Doc>()
        dayDirs(repoRoot).forEach { dayDir ->
            val readme = dayDir.resolve("README.md")
            if (Files.exists(readme)) docs += Doc(rel(repoRoot, readme), readme.readText(), "docs")
            val docsDir = dayDir.resolve("docs")
            if (Files.exists(docsDir)) {
                Files.list(docsDir).use { entries ->
                    entries.filter { it.name.endsWith(".md") }.sorted()
                        .forEach { docs += Doc(rel(repoRoot, it), it.readText(), "docs") }
                }
            }
            val srcDir = dayDir.resolve("src")
            if (Files.exists(srcDir)) {
                Files.walk(srcDir).use { paths ->
                    paths.filter { it.name.endsWith(".kt") }.sorted()
                        .forEach { docs += Doc(rel(repoRoot, it), it.readText(), "code") }
                }
            }
        }
        return docs
    }

    private fun dayDirs(repoRoot: Path): List<Path> = Files.list(repoRoot).use { entries ->
        entries.filter { it.isDirectory() && it.name.startsWith("day ") }.toList()
            .sortedBy { it.name.removePrefix("day ").trim().toIntOrNull() ?: 0 }
    }

    private fun rel(root: Path, path: Path): String = root.relativize(path).toString()

    fun chunk(doc: Doc, maxChars: Int): List<DocChunk> =
        if (doc.kind == "code") chunkCode(doc) else chunkMarkdown(doc, maxChars)

    // --- markdown: чанк = раздел (как в Дне 31) --------------------------------

    private fun chunkMarkdown(doc: Doc, maxChars: Int): List<DocChunk> {
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

    // --- код: чанк = окно строк с путём файла в заголовке ------------------------

    private fun chunkCode(doc: Doc): List<DocChunk> {
        val lines = doc.text.lines()
        val windows = lines.chunked(CODE_WINDOW_LINES)
        var charStart = 0
        return windows.mapIndexed { i, window ->
            val body = window.joinToString("\n")
            val fromLine = i * CODE_WINDOW_LINES + 1
            val chunk = DocChunk(
                chunkId = "${doc.relPath}#$i",
                source = doc.relPath,
                title = doc.relPath,
                section = "строки $fromLine–${fromLine + window.size - 1}",
                charStart = charStart,
                charEnd = charStart + body.length,
                text = "// ${doc.relPath} (строки $fromLine–${fromLine + window.size - 1})\n$body",
            )
            charStart += body.length + 1
            chunk
        }.filter { it.text.isNotBlank() }
    }

    /** SHA-256 корпуса: индекс (и кеш Actions) пересчитываются только при смене файлов. */
    fun corpusSha(docs: List<Doc>): String {
        val digest = MessageDigest.getInstance("SHA-256")
        docs.forEach {
            digest.update(it.relPath.toByteArray())
            digest.update(it.text.toByteArray())
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
