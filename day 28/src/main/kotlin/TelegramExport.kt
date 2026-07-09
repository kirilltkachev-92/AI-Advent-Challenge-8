import java.nio.file.Path
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.readText

/** Одно сообщение из экспорта Telegram. */
data class TgMessage(
    val date: String,    // "31.05.2026"
    val time: String,    // "14:01"
    val author: String,
    val text: String,
    val file: String,    // из какого messages*.html пришло
)

/**
 * Парсер HTML-экспорта Telegram (как в Дне 27), написан вручную. Большой чат
 * Telegram режет на несколько файлов: messages.html, messages2.html, … —
 * parseDir читает их в правильном порядке (по номеру) и склеивает.
 */
object TelegramExport {

    fun parseDir(dir: Path): List<TgMessage> = dir
        .listDirectoryEntries("messages*.html")
        .sortedBy { it.name.removePrefix("messages").removeSuffix(".html").toIntOrNull() ?: 1 }
        .flatMap { parse(it) }

    fun parse(path: Path): List<TgMessage> {
        val html = path.readText()
        val messages = mutableListOf<TgMessage>()
        var lastAuthor = "?"

        val blockRegex = Regex("""<div class="message (default clearfix(?: joined)?|service)"[^>]*>""")
        val blocks = blockRegex.findAll(html).toList()
        blocks.forEachIndexed { i, match ->
            val block = html.substring(match.range.last + 1, blocks.getOrNull(i + 1)?.range?.first ?: html.length)
            if (match.groupValues[1] == "service") return@forEachIndexed

            val title = Regex("""class="pull_right date details" title="([^"]+)"""")
                .find(block)?.groupValues?.get(1) ?: return@forEachIndexed
            val (date, time) = title.split(" ").let { it[0] to it.getOrElse(1) { "" }.take(5) }

            Regex("""<div class="from_name">\s*([^<]+?)\s*</div>""")
                .find(block)?.let { lastAuthor = it.groupValues[1].trim() }

            val textStart = block.indexOf("""<div class="text">""")
            if (textStart < 0) return@forEachIndexed // только медиа/стикер без текста
            val inner = block.substring(textStart + """<div class="text">""".length)
                .substringBefore("</div>")

            val text = cleanHtml(inner)
            if (text.isNotBlank()) messages += TgMessage(date, time, lastAuthor, text, path.name)
        }
        return messages
    }

    /** <br> → перенос строки, остальные теги долой, HTML-сущности назад в символы. */
    private fun cleanHtml(fragment: String): String = fragment
        .replace(Regex("<br\\s*/?>"), "\n")
        .replace(Regex("<[^>]+>"), "")
        .replace("&lt;", "<").replace("&gt;", ">")
        .replace("&quot;", "\"").replace("&#39;", "'")
        .replace("&nbsp;", " ").replace("&amp;", "&")
        .lines().joinToString("\n") { it.trim() }
        .trim()
}
