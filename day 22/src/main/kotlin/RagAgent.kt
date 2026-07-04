/** Ответ агента: текст + что было извлечено (для режима без RAG — пустой список). */
data class AgentAnswer(
    val text: String,
    val retrieved: List<Hit>,
)

/**
 * Агент с ДВУМЯ режимами (суть Дня 22):
 *  - без RAG: вопрос уходит модели как есть;
 *  - с RAG: вопрос → эмбеддинг → top-k чанков из индекса → контекст + вопрос → модель.
 * Один и тот же LLM, одинаковая temperature — отличается только наличие контекста.
 */
class RagAgent(
    private val llm: DeepSeekClient,
    private val embedder: OllamaEmbedder,
    private val index: DocumentIndex,
    private val topK: Int = Config.topK(),
) {
    private val plainSystem =
        "You are a careful assistant answering questions about the GPT-3 paper " +
            "(Brown et al., \"Language Models are Few-Shot Learners\"). " +
            "Answer concisely (3-6 sentences). If you are not sure about specific numbers " +
            "or facts, say so explicitly instead of guessing."

    private val ragSystem =
        "You are a careful assistant answering questions about the GPT-3 paper " +
            "(Brown et al., \"Language Models are Few-Shot Learners\"). " +
            "Answer ONLY based on the provided context chunks. Answer concisely (3-6 sentences). " +
            "Cite the chunks you used by their [section] label. " +
            "If the context does not contain the answer, say exactly that."

    /** Режим без RAG: «голый» вопрос. */
    fun askPlain(question: String): AgentAnswer =
        AgentAnswer(llm.chat(plainSystem, question), emptyList())

    /** Режим с RAG: поиск релевантных чанков → объединение с вопросом → запрос к LLM. */
    fun askRag(question: String): AgentAnswer {
        val hits = Search.topK(index, embedder.embedQuery(question), topK)
        val context = hits.joinToString("\n\n") { hit ->
            "[${hit.chunk.section ?: hit.chunk.chunk_id}] (score ${"%.3f".format(hit.score)})\n${hit.chunk.text}"
        }
        val user = "Context chunks:\n$context\n\nQuestion: $question"
        return AgentAnswer(llm.chat(ragSystem, user), hits)
    }
}
