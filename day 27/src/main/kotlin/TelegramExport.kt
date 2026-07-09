import java.nio.file.Path
import kotlin.io.path.readText

/** Одно сообщение из экспорта Telegram. */
data class TgMessage(
    val date: String,    // "07.07.2026"
    val time: String,    // "14:01"
    val author: String,
    val text: String,
)

/**
 * Парсер HTML-экспорта Telegram (Export chat history → HTML), написан вручную.
 *
 * Структура экспорта простая и регулярная:
 *   <div class="message service">…<div class="body details">7 July 2026</div>   — смена даты
 *   <div class="message default clearfix" id="…">                               — сообщение
 *     <div class="pull_right date details" title="07.07.2026 14:01:49 …">14:01
 *     <div class="from_name">Автор</div>                                        — нет у joined
 *     <div class="text">текст с <br> и инлайн-тегами</div>
 *
 * Сообщения с классом joined идут без from_name — автор берётся из предыдущего.
 * Внутри <div class="text"> вложенных div нет, только инлайн-теги — поэтому
 * содержимое можно брать до ближайшего </div>.
 */
object TelegramExport {

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
            if (text.isNotBlank()) messages += TgMessage(date, time, lastAuthor, text)
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
