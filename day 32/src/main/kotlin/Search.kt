/** Результат поиска: чанк документации + оценка релевантности. */
data class Hit(val chunk: IndexedChunk, val score: Float)

object Search {
    /**
     * Поиск Недели 5: косинус (скалярное произведение нормированных векторов),
     * честный полный перебор — плюс гибридные бонусы за точные слова (0.1)
     * и биграммы (0.3) вопроса: «день 17» дословно бьёт ровно в нужный README,
     * даже когда многоязычные эмбеддинги промахиваются.
     */
    fun topK(index: DocumentIndex, queryVec: FloatArray, query: String, k: Int): List<Hit> {
        val words = query.lowercase()
            .split(Regex("[^\\p{L}\\p{N}]+"))
            .filter { it.length >= 3 || it.any(Char::isDigit) }
        val bigrams = words.zipWithNext { a, b -> "$a $b" }
        return index.chunks
            .map { chunk ->
                val lower = chunk.text.lowercase()
                val wordFrac = if (words.isEmpty()) 0f else words.count { lower.contains(it) }.toFloat() / words.size
                val bigramFrac = if (bigrams.isEmpty()) 0f else bigrams.count { lower.contains(it) }.toFloat() / bigrams.size
                Hit(chunk, dot(queryVec, chunk.embedding) + 0.1f * wordFrac + 0.3f * bigramFrac)
            }
            .sortedByDescending { it.score }
            .take(k)
    }

    /**
     * Фрагменты для промпта: каждый найденный чанк разворачивается соседними
     * разделами того же документа (±radius) — приём Дня 28: вектор точный,
     * контекст добавляем после поиска. Вопрос «какие инструменты есть» бьёт
     * во введение файла, а сам список лежит в следующих разделах.
     */
    fun renderFragments(index: DocumentIndex, hits: List<Hit>, radius: Int = 1): String {
        val byPosition = index.chunks.withIndex().associate { (i, c) -> c.chunk_id to i }
        val selected = sortedSetOf<Int>()
        hits.forEach { hit ->
            val pos = byPosition.getValue(hit.chunk.chunk_id)
            for (i in (pos - radius)..(pos + radius)) {
                val neighbor = index.chunks.getOrNull(i) ?: continue
                if (neighbor.source == hit.chunk.source) selected += i
            }
        }
        return selected.joinToString("\n\n") { i ->
            val chunk = index.chunks[i]
            "[${chunk.source} · ${chunk.section}]\n${chunk.text}"
        }
    }

    private fun dot(a: FloatArray, b: List<Float>): Float {
        var sum = 0f
        for (i in a.indices) sum += a[i] * b[i]
        return sum
    }
}
