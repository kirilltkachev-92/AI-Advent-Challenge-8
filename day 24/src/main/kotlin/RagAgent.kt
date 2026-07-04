import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Источник, на который ссылается ответ (обязательная часть ответа Дня 24). */
data class SourceRef(val chunkId: String, val section: String)

/** Дословная цитата из чанка (обязательная часть ответа Дня 24). */
data class Quote(val chunkId: String, val quote: String)

/** Трассировка поиска — та же воронка, что в Дне 23. */
data class RagTrace(
    val rewrittenQuery: String,
    val candidatesBefore: Int,
    val afterSimFilter: Int,
    val afterRerank: Int,
    val final: List<RankedHit>,
)

/**
 * Структурированный ответ агента. Либо can_answer=true и тогда ОБЯЗАТЕЛЬНЫ
 * answer + sources + quotes, либо can_answer=false — режим «не знаю»
 * с просьбой уточнить вопрос (clarification).
 */
data class StructuredAnswer(
    val canAnswer: Boolean,
    val answer: String,
    val sources: List<SourceRef>,
    val quotes: List<Quote>,
    val clarification: String,
    val refusedByRetrieval: Boolean, // «не знаю» сработал ещё до генерации (слабый контекст)
    val trace: RagTrace,
)

/**
 * RAG-агент Дня 24: пайплайн Дня 23 (rewrite → top-K → порог косинуса → реранк → порог)
 * + два анти-галлюцинационных правила:
 *  1) если после фильтрации не осталось релевантных чанков (релевантность ниже порога) —
 *     агент ОБЯЗАН сказать «не знаю» и попросить уточнение, генератор даже не вызывается
 *     (fallback Дня 23 «взять лучший чанк» убран сознательно);
 *  2) генератор возвращает строгий JSON: ответ + источники (chunk_id + section) +
 *     ДОСЛОВНЫЕ цитаты из чанков; их дословность потом проверяется кодом (Validator).
 */
class RagAgent(
    private val llm: DeepSeekClient,
    private val embedder: OllamaEmbedder,
    private val index: DocumentIndex,
    private val rewriter: QueryRewriter,
    private val reranker: Reranker,
) {
    private val json = Json { ignoreUnknownKeys = true }

    private val system =
        "You are a careful assistant answering questions about the GPT-3 paper " +
            "(Brown et al., \"Language Models are Few-Shot Learners\") strictly from the provided context chunks. " +
            "Each chunk is labeled with its chunk_id and section. " +
            "Reply STRICTLY as JSON:\n" +
            "{\"can_answer\": true|false,\n" +
            " \"answer\": \"concise answer (3-6 sentences), empty string if can_answer=false\",\n" +
            " \"sources\": [{\"chunk_id\": \"...\", \"section\": \"...\"}],\n" +
            " \"quotes\": [{\"chunk_id\": \"...\", \"quote\": \"fragment copied VERBATIM, character for character, from that chunk\"}],\n" +
            " \"clarification\": \"if can_answer=false: say you don't know and ask a clarifying question; else empty string\"}\n" +
            "Rules: use ONLY the provided chunks; every claim in the answer must be backed by a quote; " +
            "quotes must be CONTIGUOUS exact substrings of the chunks (do not paraphrase, do not fix typos, " +
            "do not reorder table cells, 1-3 sentences each); if you must skip text inside a quote, mark the gap " +
            "with \"...\" and keep each fragment exact; " +
            "list every chunk you used in sources. If the chunks do not contain the answer, set can_answer=false."

    fun ask(question: String): StructuredAnswer {
        val rewritten = rewriter.rewrite(question)
        val candidates = Search.topK(index, embedder.embedQuery(rewritten), Config.candidatesK())
        val afterSim = candidates.filter { it.score >= Config.simThreshold() }
        val reranked = reranker.rerank(question, afterSim)
        val passed = reranked.filter { it.rerankScore >= Config.rerankThreshold() }
        val final = passed.take(Config.finalK())
        val trace = RagTrace(rewritten, candidates.size, afterSim.size, passed.size, final)

        // Правило «не знаю»: релевантность ниже порога — отказ без вызова генератора.
        if (final.isEmpty()) {
            return StructuredAnswer(
                canAnswer = false,
                answer = "",
                sources = emptyList(),
                quotes = emptyList(),
                clarification = "Не знаю: в базе (статья GPT-3) не нашлось достаточно релевантного контекста " +
                    "для этого вопроса. Уточните, пожалуйста, вопрос — например, какой раздел или аспект статьи вас интересует.",
                refusedByRetrieval = true,
                trace = trace,
            )
        }

        val contextText = final.joinToString("\n\n") { rh ->
            "chunk_id: ${rh.hit.chunk.chunk_id}\nsection: ${rh.hit.chunk.section ?: "-"}\n${rh.hit.chunk.text}"
        }
        val raw = llm.chat(system, "Context chunks:\n$contextText\n\nQuestion: $question", jsonMode = true)
        return parse(raw, trace)
    }

    private fun parse(raw: String, trace: RagTrace): StructuredAnswer {
        val obj = json.parseToJsonElement(raw).jsonObject
        val sources = obj["sources"]?.jsonArray?.mapNotNull { s ->
            val so = s.jsonObject
            val id = so["chunk_id"]?.jsonPrimitive?.content ?: return@mapNotNull null
            SourceRef(id, so["section"]?.jsonPrimitive?.content ?: "")
        } ?: emptyList()
        val quotes = obj["quotes"]?.jsonArray?.mapNotNull { q ->
            val qo = q.jsonObject
            val id = qo["chunk_id"]?.jsonPrimitive?.content ?: return@mapNotNull null
            val text = qo["quote"]?.jsonPrimitive?.content ?: return@mapNotNull null
            Quote(id, text)
        } ?: emptyList()
        return StructuredAnswer(
            canAnswer = obj["can_answer"]?.jsonPrimitive?.booleanOrNull ?: false,
            answer = obj["answer"]?.jsonPrimitive?.content ?: "",
            sources = sources,
            quotes = quotes,
            clarification = obj["clarification"]?.jsonPrimitive?.content ?: "",
            refusedByRetrieval = false,
            trace = trace,
        )
    }
}
