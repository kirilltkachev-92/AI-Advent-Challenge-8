/**
 * Чанк с метаданными из задания: source, title/file, section, chunk_id.
 * Плюс служебные поля — стратегия и позиция в документе (для отладки и отчёта).
 */
data class Chunk(
    val chunkId: String,
    val source: String,
    val title: String,
    val section: String?, // у фиксированной стратегии раздела нет — она режет вслепую
    val strategy: String,
    val text: String,
    val charStart: Int,
    val charEnd: Int,
)

interface Chunker {
    val strategy: String
    fun chunk(doc: RawDocument): List<Chunk>
}

/**
 * Стратегия 1 — фиксированное окно с перекрытием.
 * Простая и универсальная: не знает ничего про структуру, режет по N символов,
 * стараясь не рвать слово (откат до последнего пробела/перевода строки).
 */
class FixedSizeChunker(
    private val size: Int,
    private val overlap: Int,
) : Chunker {
    override val strategy = "fixed"

    override fun chunk(doc: RawDocument): List<Chunk> {
        val text = doc.text
        val chunks = mutableListOf<Chunk>()
        var start = 0
        var index = 0
        while (start < text.length) {
            var end = (start + size).coerceAtMost(text.length)
            if (end < text.length) {
                // откатываемся к границе слова, но не дальше половины окна
                val lastBreak = text.lastIndexOfAny(charArrayOf(' ', '\n'), end)
                if (lastBreak > start + size / 2) end = lastBreak
            }
            val piece = text.substring(start, end).trim()
            if (piece.isNotEmpty()) {
                chunks += Chunk(
                    chunkId = "fixed-${slug(doc.title)}-%03d".format(index),
                    source = doc.source,
                    title = doc.title,
                    section = null,
                    strategy = strategy,
                    text = piece,
                    charStart = start,
                    charEnd = end,
                )
                index++
            }
            if (end >= text.length) break
            start = end - overlap // перекрытие: конец чанка повторяется в начале следующего
        }
        return chunks
    }
}

/**
 * Стратегия 2 — по структуре документа:
 *  - markdown → по заголовкам `#…`;
 *  - pdf-статья → по нумерованным разделам («3.2 Attention», «References»…);
 *  - код → по top-level объявлениям (fun/class/object…), преамбула отдельно.
 * Слишком длинные секции дорезаются фиксированным окном, но сохраняют section в метаданных.
 */
class StructureChunker(
    private val maxSectionChars: Int = Config.MAX_SECTION_CHARS,
) : Chunker {
    override val strategy = "structure"

    override fun chunk(doc: RawDocument): List<Chunk> {
        val mdHeading = Regex("""^#{1,6}\s+(.+)$""")
        val sections = when (doc.kind) {
            DocKind.MARKDOWN -> splitByHeadings(doc.text) { mdHeading.matchEntire(it)?.groupValues?.get(1) }
            DocKind.PDF -> splitByHeadings(doc.text, ::pdfHeading)
            DocKind.CODE -> splitCode(doc.text, doc.title)
        }
        val chunks = mutableListOf<Chunk>()
        var index = 0
        sections.forEach { sec ->
            // секция длиннее лимита — дорезаем окном, чтобы не выйти за контекст эмбеддера
            val parts = if (sec.text.length <= maxSectionChars) listOf(sec.text)
            else windowSplit(sec.text, maxSectionChars)
            parts.forEach { part ->
                val piece = part.trim()
                // совсем короткие секции (голый заголовок, обрывок) — шум, не индексируем
                if (piece.length < 30) return@forEach
                chunks += Chunk(
                    chunkId = "structure-${slug(doc.title)}-%03d".format(index),
                    source = doc.source,
                    title = doc.title,
                    section = sec.name,
                    strategy = strategy,
                    text = piece,
                    charStart = sec.start,
                    charEnd = sec.start + sec.text.length,
                )
                index++
            }
        }
        return chunks
    }

    private data class Section(val name: String, val text: String, val start: Int)

    // Заголовки arXiv-статьи после PDFTextStripper: «1 Introduction», «3.2.1 Scaled Dot-Product
    // Attention», а также ненумерованные «Abstract» / «References» / «Acknowledgements».
    // Строки оглавления («2 Approach 6») кончаются номером страницы — отдельным словом-числом;
    // такие за заголовки не считаем (а «F Additional Samples from GPT-3» — считаем).
    // Приложения нумеруются буквой («C Details of …») — их ловим с проверкой Title Case,
    // чтобы не путать с обычной строкой текста вида «A model can …».
    private val pdfHeadingRegex =
        Regex("""^(\d+(?:\.\d+)*\s+[A-Z][\w\- ]{2,70}|Abstract|References|Acknowledgements?|Appendix.*)$""")
    private val appendixHeadingRegex = Regex("""^[A-Z]\s+[A-Z][\w\- ]{2,70}$""")

    /** Имя секции, если строка — заголовок статьи, иначе null. */
    private fun pdfHeading(line: String): String? {
        if (line.substringAfterLast(' ').all { it.isDigit() }) return null // строка оглавления
        pdfHeadingRegex.matchEntire(line)?.let { return it.groupValues[1] }
        if (appendixHeadingRegex.matches(line)) {
            // Title Case: большинство слов с заглавной («C Details of Common Crawl Filtering»)
            val words = line.split(' ').drop(1).filter { it.isNotBlank() }
            if (words.size >= 2 && words.count { it[0].isUpperCase() } * 2 > words.size) return line
        }
        return null
    }

    private fun splitByHeadings(text: String, heading: (String) -> String?): List<Section> {
        val lines = text.lines()
        val sections = mutableListOf<Section>()
        var name = "(преамбула)"
        var buf = StringBuilder()
        var sectionStart = 0
        var offset = 0
        lines.forEach { line ->
            val headingName = heading(line.trim())
            if (headingName != null) {
                if (buf.isNotBlank()) sections += Section(name, buf.toString(), sectionStart)
                name = headingName
                buf = StringBuilder(line).append('\n')
                sectionStart = offset
            } else {
                buf.append(line).append('\n')
            }
            offset += line.length + 1
        }
        if (buf.isNotBlank()) sections += Section(name, buf.toString(), sectionStart)
        return sections
    }

    // Новая «секция» кода — top-level объявление (колонка 0), включая его аннотации и KDoc выше.
    private val declRegex =
        Regex("""^(?:@\w+.*|(?:private |internal |public )?(?:fun|class|object|interface|enum class|data class|sealed class|const val|val|var)\b.*)""")

    private fun splitCode(text: String, file: String): List<Section> {
        val lines = text.lines()
        val sections = mutableListOf<Section>()
        var name = "$file: преамбула (package/import)"
        var bufStartLine = 0
        var pendingDoc = -1 // строка, с которой начинается KDoc/аннотации будущего объявления
        val breaks = mutableListOf<Pair<Int, String>>() // (номер строки, имя секции)

        lines.forEachIndexed { i, line ->
            when {
                line.startsWith("/**") || (line.startsWith("@") && declRegex.matches(line)) ->
                    if (pendingDoc < 0) pendingDoc = i
                line.isNotEmpty() && !line[0].isWhitespace() && declRegex.matches(line) &&
                    !line.startsWith("import") && !line.startsWith("package") -> {
                    val at = if (pendingDoc >= 0) pendingDoc else i
                    breaks += at to declName(line)
                    pendingDoc = -1
                }
                line.isNotEmpty() && !line[0].isWhitespace() && !line.startsWith("*") &&
                    !line.startsWith(" ") && !line.startsWith("//") -> pendingDoc = -1
            }
        }

        var lineOffsets = IntArray(lines.size + 1)
        lines.forEachIndexed { i, l -> lineOffsets[i + 1] = lineOffsets[i] + l.length + 1 }

        var prev = 0
        var prevName = name
        (breaks + listOf(lines.size to "")).forEach { (lineNo, secName) ->
            if (lineNo > prev) {
                val body = lines.subList(prev, lineNo).joinToString("\n")
                if (body.isNotBlank()) sections += Section(prevName, body, lineOffsets[prev])
            }
            prev = lineNo
            if (secName.isNotEmpty()) prevName = "$file: $secName"
        }
        return sections
    }

    private fun declName(line: String): String =
        Regex("""(?:fun|class|object|interface|val|var)\s+([A-Za-z_][\w]*)""")
            .find(line)?.groupValues?.get(1) ?: line.take(40)

    private fun windowSplit(text: String, size: Int): List<String> {
        val parts = mutableListOf<String>()
        var start = 0
        while (start < text.length) {
            var end = (start + size).coerceAtMost(text.length)
            if (end < text.length) {
                val lastBreak = text.lastIndexOfAny(charArrayOf('\n', ' '), end)
                if (lastBreak > start + size / 2) end = lastBreak
            }
            parts += text.substring(start, end)
            start = end
        }
        return parts
    }
}

/** Имя документа → безопасный кусочек chunk_id. */
fun slug(title: String): String =
    title.lowercase().replace(Regex("""[^a-z0-9а-яё]+"""), "-").trim('-').take(40)
