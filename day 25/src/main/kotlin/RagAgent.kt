import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Источник, на который ссылается ответ (в чате выводится всегда). */
data class SourceRef(val chunkId: String, val section: String)

/** Дословная цитата из чанка. */
data class Quote(val chunkId: String, val quote: String)

/** Трассировка поиска (воронка Дня 23) + признак «мета-вопроса» без обращения к базе. */
data class RagTrace(
    val rewrittenQuery: String,
    val needsRetrieval: Boolean,
    val candidatesBefore: Int,
    val afterSimFilter: Int,
    val afterRerank: Int,
    val final: List<RankedHit>,
)

/** Структурированный ответ: как в Дне 24 + флаг мета-ответа (из памяти диалога, без базы). */
data class StructuredAnswer(
    val canAnswer: Boolean,
    val answer: String,
    val sources: List<SourceRef>,
    val quotes: List<Quote>,
    val clarification: String,
    val refusedByRetrieval: Boolean,
    val meta: Boolean,
    val trace: RagTrace,
)

/**
 * RAG-агент Дня 25 — диалоговый. Отличия от Дня 24:
 *  - rewrite стал conversation-aware: follow-up («а на переводах?») переписывается
 *    в самостоятельный поисковый запрос с учётом истории и памяти задачи; заодно
 *    роутер решает, нужен ли базе этот вопрос вообще (мета-вопросы о самом диалоге —
 *    «какая у нас цель?», «суммируй» — отвечаются из памяти, без ретривала);
 *  - в промпт генерации инжектятся память задачи и последние ходы истории;
 *  - правила Дня 24 сохранены: ответы только по чанкам, обязательные источники
 *    и дословные цитаты, «не знаю» при слабом контексте.
 */
class RagAgent(
    private val llm: DeepSeekClient,
    private val embedder: OllamaEmbedder,
    private val index: DocumentIndex,
    private val reranker: Reranker,
) {
    private val json = Json { ignoreUnknownKeys = true }

    private val routerSystem =
        "You prepare user messages for RAG over the GPT-3 paper (Brown et al., \"Language Models are Few-Shot Learners\"). " +
            "Given the task memory, recent dialog and a new user message, decide:\n" +
            "- needs_retrieval=true if answering requires facts from the paper; then rewrite the message " +
            "(resolving pronouns and follow-ups from the dialog) into a standalone English search query " +
            "with paper terminology and the concrete terms the answer would contain;\n" +
            "- needs_retrieval=false if it is about the dialog itself (goal, what was clarified, summary of the conversation), " +
            "a greeting, a message that only sets a goal/constraint/term without asking a factual question, " +
            "or pure formatting request.\n" +
            "Reply strictly as JSON: {\"needs_retrieval\": true|false, \"query\": \"...\"}."

    private val ragSystem =
        "You are a careful assistant helping the user with the GPT-3 paper " +
            "(Brown et al., \"Language Models are Few-Shot Learners\") in an ongoing dialog. " +
            "Use the task memory and dialog history to interpret the question, but answer factual claims " +
            "STRICTLY from the provided context chunks. Respect the constraints from task memory. " +
            "Reply STRICTLY as JSON:\n" +
            "{\"can_answer\": true|false,\n" +
            " \"answer\": \"concise answer (3-6 sentences; in the user's language), empty if can_answer=false\",\n" +
            " \"sources\": [{\"chunk_id\": \"...\", \"section\": \"...\"}],\n" +
            " \"quotes\": [{\"chunk_id\": \"...\", \"quote\": \"fragment copied VERBATIM from that chunk\"}],\n" +
            " \"clarification\": \"if can_answer=false: say you don't know and ask a clarifying question; else empty\"}\n" +
            "Rules: every factual claim must be backed by a quote; quotes are CONTIGUOUS exact substrings " +
            "(mark gaps with \"...\"); list every used chunk in sources; if chunks lack the answer, can_answer=false."

    private val metaSystem =
        "You are the same assistant, answering a question about the DIALOG itself (its goal, clarifications, summary) " +
            "from the task memory and history — no document base needed. Answer briefly in the user's language. " +
            "Reply strictly as JSON: {\"answer\": \"...\"}."

    fun ask(question: String, dialog: String, state: TaskState): StructuredAnswer {
        val route = route(question, dialog, state)

        if (!route.second) { // мета-вопрос: отвечаем из памяти диалога, база не нужна
            val raw = llm.chat(metaSystem, contextBlock(dialog, state) + "\n\nВопрос: $question", jsonMode = true)
            val answer = try {
                json.parseToJsonElement(raw).jsonObject["answer"]?.jsonPrimitive?.content ?: raw
            } catch (e: Exception) { raw }
            val trace = RagTrace(route.first, false, 0, 0, 0, emptyList())
            return StructuredAnswer(true, answer, emptyList(), emptyList(), "", false, true, trace)
        }

        val candidates = Search.topK(index, embedder.embedQuery(route.first), Config.candidatesK())
        val afterSim = candidates.filter { it.score >= Config.simThreshold() }
        val reranked = reranker.rerank(question, afterSim)
        val passed = reranked.filter { it.rerankScore >= Config.rerankThreshold() }
        val final = passed.take(Config.finalK())
        val trace = RagTrace(route.first, true, candidates.size, afterSim.size, passed.size, final)

        if (final.isEmpty()) {
            return StructuredAnswer(
                canAnswer = false, answer = "", sources = emptyList(), quotes = emptyList(),
                clarification = "Не знаю: в базе (статья GPT-3) не нашлось достаточно релевантного контекста " +
                    "для этого вопроса. Уточните, пожалуйста, что именно вас интересует в рамках статьи.",
                refusedByRetrieval = true, meta = false, trace = trace,
            )
        }

        val chunksText = final.joinToString("\n\n") { rh ->
            "chunk_id: ${rh.hit.chunk.chunk_id}\nsection: ${rh.hit.chunk.section ?: "-"}\n${rh.hit.chunk.text}"
        }
        val user = contextBlock(dialog, state) + "\n\nContext chunks:\n$chunksText\n\nQuestion: $question"
        return parse(llm.chat(ragSystem, user, jsonMode = true), trace)
    }

    private fun contextBlock(dialog: String, state: TaskState): String = buildString {
        if (!state.isEmpty()) {
            appendLine("=== Память задачи ===")
            appendLine(state.render())
        }
        if (dialog.isNotBlank()) {
            appendLine("=== Последние ходы диалога ===")
            appendLine(dialog)
        }
    }.trim()

    /** Роутер + conversation-aware rewrite. Возвращает (запрос, нужен ли ретривал). */
    private fun route(question: String, dialog: String, state: TaskState): Pair<String, Boolean> = try {
        val raw = llm.chat(routerSystem, contextBlock(dialog, state) + "\n\nNew user message: $question", jsonMode = true)
        val obj = json.parseToJsonElement(raw).jsonObject
        val query = obj["query"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() } ?: question
        val needs = obj["needs_retrieval"]?.jsonPrimitive?.booleanOrNull ?: true
        query to needs
    } catch (e: Exception) {
        question to true // при сбое роутера ведём себя консервативно: ищем в базе
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
            meta = false,
            trace = trace,
        )
    }
}
