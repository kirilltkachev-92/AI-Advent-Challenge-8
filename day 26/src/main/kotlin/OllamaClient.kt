import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * Клиент локальной Ollama. Протокол написан вручную, три эндпоинта:
 *
 *   GET  /api/version → {"version": "0.31.1"}            — сервер жив
 *   GET  /api/tags    → {"models": [{"name": ...}, ...]} — какие модели скачаны
 *   POST /api/chat    → ответ модели + метрики             — сам запрос к LLM
 *
 * Тело /api/chat (stream=false — весь ответ одним JSON):
 *   {"model": "qwen2.5:14b", "messages": [{"role": "user", "content": "..."}], "stream": false}
 * В ответе, кроме текста, Ollama отдаёт счётчики: prompt_eval_count / eval_count
 * (токены промпта/ответа) и *_duration в наносекундах — из них считаем скорость.
 */
class OllamaClient(private val baseUrl: String = Config.ollamaBaseUrl()) {
    private val http = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .build()
    private val json = Json { ignoreUnknownKeys = true }

    data class ChatResult(
        val answer: String,
        val promptTokens: Long,
        val answerTokens: Long,
        val loadMs: Long,      // подгрузка модели в память (0, если уже загружена)
        val totalMs: Long,     // весь запрос целиком
        val evalMs: Long,      // чистая генерация ответа
    ) {
        val tokensPerSec: Double
            get() = if (evalMs > 0) answerTokens * 1000.0 / evalMs else 0.0
    }

    /** Сервер запущен? Возвращает версию или null, если порт не отвечает. */
    fun version(): String? = try {
        val response = get("/api/version")
        json.parseToJsonElement(response).jsonObject.getValue("version").jsonPrimitive.content
    } catch (e: Exception) {
        null
    }

    /** Имена локально скачанных моделей (ollama list). */
    fun localModels(): List<String> {
        val response = get("/api/tags")
        return json.parseToJsonElement(response).jsonObject.getValue("models").jsonArray
            .map { it.jsonObject.getValue("name").jsonPrimitive.content }
    }

    fun chat(model: String, userPrompt: String, systemPrompt: String? = null): ChatResult {
        val body = buildJsonObject {
            put("model", model)
            putJsonArray("messages") {
                if (systemPrompt != null) {
                    add(buildJsonObject { put("role", "system"); put("content", systemPrompt) })
                }
                add(buildJsonObject { put("role", "user"); put("content", userPrompt) })
            }
            put("stream", false)
            // Температура 0 — прогоны воспроизводимы, отчёт честно повторяется.
            putJsonObject("options") { put("temperature", 0) }
        }
        val request = HttpRequest.newBuilder()
            .uri(URI.create("$baseUrl/api/chat"))
            .header("Content-Type", "application/json")
            .timeout(Duration.ofMinutes(10))
            .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
            .build()
        val response = http.send(request, HttpResponse.BodyHandlers.ofString())
        check(response.statusCode() == 200) {
            "Ollama /api/chat → HTTP ${response.statusCode()}: ${response.body().take(300)}\n" +
                "Проверьте, что модель скачана: ollama pull $model"
        }
        val obj = json.parseToJsonElement(response.body()).jsonObject
        return ChatResult(
            answer = obj.getValue("message").jsonObject.getValue("content").jsonPrimitive.content.trim(),
            promptTokens = obj["prompt_eval_count"]?.jsonPrimitive?.long ?: 0,
            answerTokens = obj["eval_count"]?.jsonPrimitive?.long ?: 0,
            loadMs = nanosToMs(obj["load_duration"]),
            totalMs = nanosToMs(obj["total_duration"]),
            evalMs = nanosToMs(obj["eval_duration"]),
        )
    }

    private fun nanosToMs(value: kotlinx.serialization.json.JsonElement?): Long =
        ((value as? JsonPrimitive)?.long ?: 0) / 1_000_000

    private fun get(path: String): String {
        val request = HttpRequest.newBuilder()
            .uri(URI.create("$baseUrl$path"))
            .timeout(Duration.ofSeconds(10))
            .GET()
            .build()
        val response = http.send(request, HttpResponse.BodyHandlers.ofString())
        check(response.statusCode() == 200) { "Ollama $path → HTTP ${response.statusCode()}" }
        return response.body()
    }
}
