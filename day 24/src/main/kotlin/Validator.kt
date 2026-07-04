/**
 * Анти-галлюцинационные проверки ответа КОДОМ (не доверяем модели на слово):
 *  - источники есть и ссылаются только на реально извлечённые чанки;
 *  - цитаты есть и ДОСЛОВНО присутствуют в тексте указанных чанков
 *    (сравнение с нормализацией пробелов — PDF-текст переносит строки).
 */
object Validator {

    data class QuoteCheck(val quote: Quote, val verbatim: Boolean, val foundInClaimedChunk: Boolean)

    data class Result(
        val hasSources: Boolean,
        val sourcesValid: Boolean,      // все chunk_id из ответа реально были в контексте
        val hasQuotes: Boolean,
        val quoteChecks: List<QuoteCheck>,
    ) {
        val quotesVerbatim: Int get() = quoteChecks.count { it.verbatim }
        val allQuotesVerbatim: Boolean get() = quoteChecks.isNotEmpty() && quoteChecks.all { it.verbatim }
    }

    fun validate(answer: StructuredAnswer): Result {
        val retrieved = answer.trace.final.associateBy { it.hit.chunk.chunk_id }
        val sourcesValid = answer.sources.isNotEmpty() &&
            answer.sources.all { retrieved.containsKey(it.chunkId) }

        val checks = answer.quotes.map { q ->
            val claimed = retrieved[q.chunkId]?.hit?.chunk?.text
            val inClaimed = claimed != null && contains(claimed, q.quote)
            // цитата может быть дословной, но с перепутанным chunk_id — ищем и по всем чанкам
            val anywhere = inClaimed || retrieved.values.any { contains(it.hit.chunk.text, q.quote) }
            QuoteCheck(q, anywhere, inClaimed)
        }
        return Result(
            hasSources = answer.sources.isNotEmpty(),
            sourcesValid = sourcesValid,
            hasQuotes = answer.quotes.isNotEmpty(),
            quoteChecks = checks,
        )
    }

    /**
     * Дословное вхождение с точностью до пробелов/переносов (PDF рвёт строки) и
     * типографики (модель «выпрямляет» кавычки/тире). Цитата с купюрами «...»
     * проверяется по кускам: каждый фрагмент дословен и идёт в исходном порядке.
     */
    private fun contains(chunkText: String, quote: String): Boolean {
        val text = normalize(chunkText)
        val parts = normalize(quote).split("...").map { it.trim() }.filter { it.isNotEmpty() }
        var from = 0
        parts.forEach { part ->
            val idx = text.indexOf(part, from)
            if (idx < 0) return false
            from = idx + part.length
        }
        return parts.isNotEmpty()
    }

    private fun normalize(s: String): String = s
        // кавычки/апострофы выкидываем целиком: модель пишет 'x' там, где в PDF “x”,
        // а для дословности важен сам текст, а не вид кавычек
        .replace(Regex("[’‘`'\"“”«»]"), "")
        .replace(Regex("[–—]"), "-")
        .replace("…", "...")
        .replace(Regex("\\.\\.\\."), " ... ") // купюры выделяем отдельным токеном
        .replace(Regex("\\s+"), " ")
        .trim().lowercase()
}
