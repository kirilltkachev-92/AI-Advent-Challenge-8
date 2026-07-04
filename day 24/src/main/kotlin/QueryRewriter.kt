import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Query rewrite — первый этап улучшенного RAG: пользовательский вопрос переписывается
 * в запрос, «удобный» семантическому поиску: терминология статьи, синонимы,
 * ключевые сущности. Именно эта переформулировка идёт в эмбеддинг вместо вопроса.
 */
class QueryRewriter(private val llm: DeepSeekClient) {
    private val json = Json { ignoreUnknownKeys = true }

    private val system =
        "You rewrite user questions into search queries for semantic search over the GPT-3 paper " +
            "(Brown et al., \"Language Models are Few-Shot Learners\"). " +
            "Rewrite the question as a short English passage of keywords and paper terminology " +
            "that would appear verbatim in the relevant section: expand abbreviations, add synonyms " +
            "and the concrete terms/units the answer would contain (e.g. dataset names, metric names). " +
            "Do not answer the question. Reply strictly as JSON: {\"query\": \"...\"}."

    fun rewrite(question: String): String = try {
        val raw = llm.chat(system, question, jsonMode = true)
        json.parseToJsonElement(raw).jsonObject["query"]?.jsonPrimitive?.content
            ?.takeIf { it.isNotBlank() } ?: question
    } catch (e: Exception) {
        question // переписывание — оптимизация; при сбое ищем по исходному вопросу
    }
}
