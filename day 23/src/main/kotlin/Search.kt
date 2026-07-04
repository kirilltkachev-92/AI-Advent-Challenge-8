/** Результат поиска: чанк + косинусная близость к запросу. */
data class Hit(val chunk: IndexedChunk, val score: Float)

object Search {
    /**
     * Векторы нормированы при индексации, поэтому косинус = скалярное произведение.
     * Индекс маленький (сотни чанков) — честный полный перебор, без ANN.
     */
    fun topK(index: DocumentIndex, queryVec: FloatArray, k: Int): List<Hit> =
        index.chunks
            .map { Hit(it, dot(queryVec, it.embedding)) }
            .sortedByDescending { it.score }
            .take(k)

    private fun dot(a: FloatArray, b: List<Float>): Float {
        var sum = 0f
        for (i in a.indices) sum += a[i] * b[i]
        return sum
    }
}
