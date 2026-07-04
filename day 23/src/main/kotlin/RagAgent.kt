/** Трассировка улучшенного пайплайна — для терминала и отчёта. */
data class RagTrace(
    val originalQuestion: String,
    val rewrittenQuery: String?,   // null в baseline-режиме
    val candidatesBefore: Int,     // top-K до фильтрации
    val afterSimFilter: Int,       // после порога косинуса
    val afterRerank: Int,          // после порога реранкера (до среза FINAL_K)
    val final: List<RankedHit>,    // что ушло в контекст
    val fallbackUsed: Boolean,     // фильтры выели всё — взяли лучший чанк по реранку
)

data class AgentAnswer(
    val text: String,
    val trace: RagTrace,
)

/**
 * RAG-агент Дня 23 с двумя режимами:
 *  - baseline — пайплайн Дня 22: вопрос → top-FINAL_K по косинусу → LLM;
 *  - improved — query rewrite → top-CANDIDATES_K → порог косинуса →
 *    LLM-реранкер → порог балла → top-FINAL_K → LLM.
 * LLM, промпт генерации и размер финального контекста одинаковы —
 * сравнивается именно вклад rewrite + фильтрации/реранка.
 */
class RagAgent(
    private val llm: DeepSeekClient,
    private val embedder: OllamaEmbedder,
    private val index: DocumentIndex,
    private val rewriter: QueryRewriter,
    private val reranker: Reranker,
) {
    private val ragSystem =
        "You are a careful assistant answering questions about the GPT-3 paper " +
            "(Brown et al., \"Language Models are Few-Shot Learners\"). " +
            "Answer ONLY based on the provided context chunks. Answer concisely (3-6 sentences). " +
            "Cite the chunks you used by their [section] label. " +
            "If the context does not contain the answer, say exactly that."

    /** Baseline (День 22): без rewrite, без фильтра — top-FINAL_K по косинусу как есть. */
    fun askBaseline(question: String): AgentAnswer {
        val hits = Search.topK(index, embedder.embedQuery(question), Config.finalK())
        val ranked = hits.map { RankedHit(it, -1) }
        val trace = RagTrace(question, null, hits.size, hits.size, hits.size, ranked, false)
        return AgentAnswer(generate(question, ranked), trace)
    }

    /** Improved: rewrite → широкая выборка → порог similarity → реранк → порог балла → top-K. */
    fun askImproved(question: String): AgentAnswer {
        val rewritten = rewriter.rewrite(question)
        val candidates = Search.topK(index, embedder.embedQuery(rewritten), Config.candidatesK())

        // Этап фильтрации 1 (дёшево): порог косинусной близости.
        val afterSim = candidates.filter { it.score >= Config.simThreshold() }

        // Этап фильтрации 2 (точно): LLM-реранкер, запрос и чанк оцениваются совместно.
        val reranked = reranker.rerank(question, afterSim)
        val passed = reranked.filter { it.rerankScore >= Config.rerankThreshold() }

        // Фильтры могут выесть всё (вопрос не по базе — это норма). Но если кандидаты
        // были, берём лучший по реранку: пусть генератор сам скажет, хватает ли его.
        val fallback = passed.isEmpty() && reranked.isNotEmpty()
        val final = (if (fallback) reranked.take(1) else passed.take(Config.finalK()))

        val trace = RagTrace(
            originalQuestion = question,
            rewrittenQuery = rewritten,
            candidatesBefore = candidates.size,
            afterSimFilter = afterSim.size,
            afterRerank = passed.size,
            final = final,
            fallbackUsed = fallback,
        )
        return AgentAnswer(generate(question, final), trace)
    }

    private fun generate(question: String, context: List<RankedHit>): String {
        if (context.isEmpty()) return "В базе не нашлось релевантных чанков для этого вопроса."
        val contextText = context.joinToString("\n\n") { rh ->
            "[${rh.hit.chunk.section ?: rh.hit.chunk.chunk_id}]\n${rh.hit.chunk.text}"
        }
        return llm.chat(ragSystem, "Context chunks:\n$contextText\n\nQuestion: $question")
    }
}
