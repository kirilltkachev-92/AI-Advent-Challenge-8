import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.float
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import kotlin.math.sqrt

/**
 * Эмбеддинги через локальную Ollama — без изменений из Недели 5 (День 21):
 * POST {base}/api/embed, батчи по 16, task-префиксы search_document/search_query,
 * векторы нормируются при индексации (косинус = скалярное произведение).
 */
class OllamaEmbedder(
    private val baseUrl: String = Config.ollamaBaseUrl(),
    private val model: String = Config.embedModel(),
) {
    private val http = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .build()
    private val json = Json { ignoreUnknownKeys = true }

    fun embedDocuments(texts: List<String>, onProgress: (Int, Int) -> Unit = { _, _ -> }): List<FloatArray> {
        val result = mutableListOf<FloatArray>()
        texts.chunked(16).forEach { batch ->
            result += embedBatch(batch.map { "search_document: $it" })
            onProgress(result.size, texts.size)
        }
        return result
    }

    fun embedQuery(query: String): FloatArray = embedBatch(listOf("search_query: $query")).first()

    private fun embedBatch(inputs: List<String>): List<FloatArray> {
        val body = buildJsonObject {
            put("model", model)
            putJsonArray("input") { inputs.forEach { add(JsonPrimitive(it)) } }
        }
        val request = HttpRequest.newBuilder()
            .uri(URI.create("$baseUrl/api/embed"))
            .header("Content-Type", "application/json")
            .timeout(Duration.ofSeconds(120))
            .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
            .build()
        val response = http.send(request, HttpResponse.BodyHandlers.ofString())
        check(response.statusCode() == 200) {
            "Ollama /api/embed → HTTP ${response.statusCode()}: ${response.body().take(300)}\n" +
                "Проверьте, что Ollama запущена и модель скачана: ollama pull $model"
        }
        val embeddings = json.parseToJsonElement(response.body())
            .jsonObject.getValue("embeddings").jsonArray
        return embeddings.map { row ->
            val vec = row.jsonArray.map { it.jsonPrimitive.float }.toFloatArray()
            normalize(vec)
        }
    }

    private fun normalize(v: FloatArray): FloatArray {
        var sum = 0f
        v.forEach { sum += it * it }
        val norm = sqrt(sum)
        if (norm > 0f) for (i in v.indices) v[i] /= norm
        return v
    }
}
