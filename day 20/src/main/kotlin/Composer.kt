/**
 * Детерминированная обработка текста для report-MCP (инструменты compose_report / word_count).
 *
 * Никакого LLM здесь нет (по уточнению Алексея — «суммаризация это просто отчёт/итог»):
 * compose_report механически склеивает переданные блоки в один markdown-документ,
 * word_count считает статистику. Это «процессинговый» сервер, отдельный по природе
 * от серверов-источников данных (research/weather) и от хранилища (storage).
 */
object Composer {

    /** Максимальная длина одного блока в отчёте, символов (чтобы документ не разрастался). */
    private const val MAX_BLOCK = 1200

    /**
     * Склеивает отчёт из заголовка и набора текстовых блоков (каждый — отдельный раздел).
     * Блоки обычно приходят из РАЗНЫХ серверов: например, справка из research-MCP и
     * сводка погоды из weather-MCP. Возвращает готовый markdown для сохранения в storage-MCP.
     */
    fun report(title: String, sections: List<String>): String {
        val blocks = sections.map { it.trim() }.filter { it.isNotEmpty() }
        return buildString {
            appendLine("# $title")
            appendLine()
            if (blocks.isEmpty()) {
                appendLine("_Нет ни одного блока для отчёта._")
                return@buildString
            }
            appendLine("Разделов в отчёте: ${blocks.size}.")
            appendLine()
            blocks.forEachIndexed { i, block ->
                appendLine("## Раздел ${i + 1}")
                appendLine(trim(block))
                appendLine()
            }
            append("> Отчёт собран инструментом compose_report (report-MCP) из данных нескольких серверов.")
        }
    }

    /** Статистика по тексту: символы, слова, строки. */
    fun stats(text: String): String {
        val chars = text.length
        val words = text.split(Regex("\\s+")).count { it.isNotBlank() }
        val lines = text.split('\n').size
        return "Символов: $chars, слов: $words, строк: $lines."
    }

    private fun trim(text: String): String =
        if (text.length <= MAX_BLOCK) text
        else text.take(MAX_BLOCK).substringBeforeLast(' ', text.take(MAX_BLOCK)) + "…"
}
