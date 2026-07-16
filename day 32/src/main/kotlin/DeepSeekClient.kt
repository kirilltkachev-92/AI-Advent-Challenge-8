import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * Облачная сторона сравнения: DeepSeek (OpenAI-совместимый /chat/completions),
 * протокол вручную — как в Неделе 5. temperature=0, чтобы сравнение было честным.
 * Возвращает те же метрики, что локальный клиент: токены из usage,
 * скорость — по времени всего запроса (сеть внутри, у облака иначе не бывает).
 */
class DeepSeekClient(
    private val apiKey: String,
    private val model: String = Config.deepSeekModel(),
) {
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()
    private val json = Json { ignoreUnknownKeys = true }

    data class ChatResult(
        val answer: String,
        val promptTokens: Long,
        val answerTokens: Long,
        val totalMs: Long,
    ) {
        val tokensPerSec: Double
            get() = if (totalMs > 0) answerTokens * 1000.0 / totalMs else 0.0
    }

    fun chat(system: String, user: String, jsonMode: Boolean = false): ChatResult {
        val body = buildJsonObject {
            put("model", model)
            put("messages", buildJsonArray {
                add(buildJsonObject { put("role", "system"); put("content", system) })
                add(buildJsonObject { put("role", "user"); put("content", user) })
            })
            if (jsonMode) put("response_format", buildJsonObject { put("type", "json_object") })
            put("temperature", 0.0)
        }
        val request = HttpRequest.newBuilder()
            .uri(URI.create("${Config.DEEPSEEK_API_BASE}/chat/completions"))
            .header("Content-Type", "application/json")
            .header("Authorization", "Bearer $apiKey")
            .timeout(Duration.ofSeconds(120))
            .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
            .build()
        val startedAt = System.nanoTime()
        val response = http.send(request, HttpResponse.BodyHandlers.ofString())
        val totalMs = (System.nanoTime() - startedAt) / 1_000_000
        check(response.statusCode() == 200) {
            "DeepSeek → HTTP ${response.statusCode()}: ${response.body().take(300)}"
        }
        val obj = json.parseToJsonElement(response.body()).jsonObject
        val usage = obj["usage"]?.jsonObject
        return ChatResult(
            answer = obj.getValue("choices").jsonArray[0].jsonObject
                .getValue("message").jsonObject
                .getValue("content").jsonPrimitive.content.trim(),
            promptTokens = usage?.get("prompt_tokens")?.jsonPrimitive?.long ?: 0,
            answerTokens = usage?.get("completion_tokens")?.jsonPrimitive?.long ?: 0,
            totalMs = totalMs,
        )
    }
}
