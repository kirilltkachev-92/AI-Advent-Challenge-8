import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * Опциональный LLM-судья сравнения стратегий: DeepSeek получает вопрос и два
 * извлечённых контекста (A = fixed, B = structure) и говорит, какого хватает
 * лучше для ответа. Протокол — вручную, POST /chat/completions (OpenAI-совместимый).
 */
class DeepSeekJudge(private val apiKey: String, private val model: String = Config.deepSeekModel()) {
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()
    private val json = Json { ignoreUnknownKeys = true }

    data class Verdict(val winner: String, val reason: String)

    fun judge(query: String, contextA: String, contextB: String): Verdict {
        val system = "Ты оцениваешь качество retrieval для RAG. Дан вопрос и два контекста, " +
            "извлечённых разными стратегиями chunking. Ответь строго JSON-объектом " +
            "{\"winner\": \"A\"|\"B\"|\"tie\", \"reason\": \"кратко почему, по-русски\"}. " +
            "Критерий: в каком контексте достаточнее и точнее информация для ответа на вопрос."
        val user = "Вопрос: $query\n\n=== Контекст A ===\n$contextA\n\n=== Контекст B ===\n$contextB"

        val body = buildJsonObject {
            put("model", model)
            put("messages", buildJsonArray {
                add(buildJsonObject { put("role", "system"); put("content", system) })
                add(buildJsonObject { put("role", "user"); put("content", user) })
            })
            put("response_format", buildJsonObject { put("type", "json_object") })
            put("temperature", 0.0)
        }
        val request = HttpRequest.newBuilder()
            .uri(URI.create("${Config.DEEPSEEK_API_BASE}/chat/completions"))
            .header("Content-Type", "application/json")
            .header("Authorization", "Bearer $apiKey")
            .timeout(Duration.ofSeconds(90))
            .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
            .build()
        val response = http.send(request, HttpResponse.BodyHandlers.ofString())
        check(response.statusCode() == 200) {
            "DeepSeek → HTTP ${response.statusCode()}: ${response.body().take(300)}"
        }
        val content = json.parseToJsonElement(response.body()).jsonObject
            .getValue("choices").jsonArray[0].jsonObject
            .getValue("message").jsonObject
            .getValue("content").jsonPrimitive.content
        val verdict = json.parseToJsonElement(content).jsonObject
        return Verdict(
            winner = verdict["winner"]?.jsonPrimitive?.content ?: "tie",
            reason = verdict["reason"]?.jsonPrimitive?.content ?: "",
        )
    }
}
