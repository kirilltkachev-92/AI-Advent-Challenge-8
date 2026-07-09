/** Результат поиска: чанк (одно сообщение) + оценка релевантности. */
data class Hit(val chunk: IndexedChunk, val score: Float)

/** Фрагмент контекста для генерации: найденное сообщение + соседние реплики. */
data class Fragment(val header: String, val text: String)

object Search {
    /**
     * Поиск Недели 5 (косинус = скалярное произведение нормированных векторов,
     * честный полный перебор) + гибридные бонусы: доля точных слов вопроса
     * в тексте (0.1) и доля точных БИГРАММ (0.3) — «день 21» дословно бьёт
     * ровно в анонс, даже когда многоязычные эмбеддинги промахиваются.
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
     * Разворачивает точечные попадания в фрагменты с контекстом: ±radius
     * соседних сообщений вокруг каждого найденного, пересекающиеся диапазоны
     * склеиваются. Вектор точный — контекст диалога добавляем уже после поиска.
     */
    fun expand(hits: List<Hit>, messages: List<TgMessage>, radius: Int = 3): List<Fragment> {
        val ranges = hits
            .map { it.chunk.char_start } // char_start = индекс сообщения в корпусе
            .sorted()
            .map { maxOf(0, it - radius) to minOf(messages.size - 1, it + radius) }
        val merged = mutableListOf<Pair<Int, Int>>()
        ranges.forEach { r ->
            val last = merged.lastOrNull()
            if (last != null && r.first <= last.second + 1) {
                merged[merged.size - 1] = last.first to maxOf(last.second, r.second)
            } else {
                merged += r
            }
        }
        return merged.map { (from, to) ->
            Fragment(
                header = "${messages[from].date} ${messages[from].time} – ${messages[to].date} ${messages[to].time}",
                text = (from..to).joinToString("\n") { Chunking.render(messages[it]) },
            )
        }
    }

    private fun dot(a: FloatArray, b: List<Float>): Float {
        var sum = 0f
        for (i in a.indices) sum += a[i] * b[i]
        return sum
    }
}
