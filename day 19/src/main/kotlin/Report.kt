/**
 * Сборка отчёта-итога из найденных статей (инструмент summarize).
 *
 * Это НЕ LLM-суммаризация: чистая детерминированная функция, которая берёт вводные
 * абзацы статей и складывает их в один markdown-конспект. Так MCP-сервер остаётся
 * адаптером к данным и не зависит от модели (по уточнению Алексея — это просто «итог»).
 */
object Report {

    /** Максимальная длина одного фрагмента в конспекте, символов. */
    private const val MAX_EXTRACT = 600

    /**
     * @param topic    тема (обычно исходный запрос пользователя);
     * @param sections пары «заголовок статьи → вводный текст».
     */
    fun build(topic: String, sections: List<Pair<String, String>>): String {
        val nonEmpty = sections.filter { it.second.isNotBlank() }
        return buildString {
            appendLine("# Конспект: $topic")
            appendLine()
            if (nonEmpty.isEmpty()) {
                appendLine("_По теме «$topic» не удалось собрать ни одного фрагмента._")
                return@buildString
            }
            appendLine("Источник: Википедия. Статей в конспекте: ${nonEmpty.size}.")
            appendLine()
            nonEmpty.forEachIndexed { i, (title, extract) ->
                appendLine("## ${i + 1}. $title")
                appendLine(trim(extract))
                appendLine()
            }
            append("> Конспект собран автоматически инструментом summarize.")
        }
    }

    private fun trim(text: String): String {
        val clean = text.replace(Regex("\\s+"), " ").trim()
        return if (clean.length <= MAX_EXTRACT) clean
        else clean.take(MAX_EXTRACT).substringBeforeLast(' ', clean.take(MAX_EXTRACT)) + "…"
    }
}
