import kotlinx.serialization.json.Json
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Кандидат после реранка: чанк + косинус из поиска + балл реранкера (0–10). */
data class RankedHit(val hit: Hit, val rerankScore: Int)

/**
 * Второй этап после поиска — реранкер по паттерну cross-encoder, реализованный как
 * LLM-судья: модель видит запрос и чанк ВМЕСТЕ и оценивает релевантность 0–10
 * (bi-encoder так не умеет — он кодирует их порознь). Ollama реранк-модели через API
 * не отдаёт, поэтому LLM-судья — самый простой честный путь без новых зависимостей.
 * Все кандидаты оцениваются одним запросом (JSON in / JSON out).
 */
class Reranker(private val llm: DeepSeekClient) {
    private val json = Json { ignoreUnknownKeys = true }

    private val system =
        "You are a relevance grader for retrieval. Given a question and a numbered list of text chunks, " +
            "score EACH chunk from 0 to 10: 10 = chunk directly contains the answer; " +
            "5 = related topic but no direct answer; 0 = unrelated. " +
            "Judge only whether the chunk helps answer THIS question. " +
            "Reply strictly as JSON: {\"scores\": [{\"n\": <chunk number>, \"score\": <0-10>}, ...]} " +
            "with exactly one entry per chunk."

    fun rerank(question: String, candidates: List<Hit>): List<RankedHit> {
        if (candidates.isEmpty()) return emptyList()
        val listing = candidates.mapIndexed { i, hit ->
            // Чанк урезаем: для оценки релевантности хватает начала, а промпт с 20
            // полными чанками раздувается на десятки тысяч символов.
            "#${i + 1} [${hit.chunk.section ?: hit.chunk.chunk_id}]\n${hit.chunk.text.take(700)}"
        }.joinToString("\n\n")
        val user = "Question: $question\n\nChunks:\n$listing"

        val scores = try {
            val raw = llm.chat(system, user, jsonMode = true)
            json.parseToJsonElement(raw).jsonObject.getValue("scores").jsonArray.associate { entry ->
                val obj = entry.jsonObject
                (obj["n"]?.jsonPrimitive?.intOrNull ?: 0) to (obj["score"]?.jsonPrimitive?.intOrNull ?: 0)
            }
        } catch (e: Exception) {
            emptyMap() // при сбое реранка все баллы 0 — сработает fallback уровнем выше
        }
        return candidates.mapIndexed { i, hit -> RankedHit(hit, scores[i + 1] ?: 0) }
            .sortedByDescending { it.rerankScore }
    }
}
