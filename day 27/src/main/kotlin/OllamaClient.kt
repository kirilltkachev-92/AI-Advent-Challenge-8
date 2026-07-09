import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.double
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

/** Одна реплика диалога: system / user / assistant. */
data class ChatMessage(val role: String, val content: String)

/**
 * Клиент локальной Ollama. Протокол написан вручную, четыре эндпоинта:
 *
 *   GET  /api/version → {"version": "0.31.1"}            — сервер жив
 *   GET  /api/tags    → {"models": [{"name": ...}, ...]} — какие модели скачаны
 *   POST /api/chat    → ответ модели + метрики           — диалог с историей
 *   POST /api/embed   → {"embeddings": [[...], ...]}     — векторы для поиска
 *
 * В отличие от Дня 26, /api/chat принимает весь диалог целиком
 * (system + история user/assistant) — так модель помнит контекст беседы.
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

    fun chat(model: String, messages: List<ChatMessage>): ChatResult {
        val body = buildJsonObject {
            put("model", model)
            putJsonArray("messages") {
                messages.forEach { msg ->
                    add(buildJsonObject { put("role", msg.role); put("content", msg.content) })
                }
            }
            put("stream", false)
            // Температура 0 — ответы на одни и те же вопросы воспроизводимы.
            putJsonObject("options") { put("temperature", 0) }
        }
        val response = post("/api/chat", body.toString(), Duration.ofMinutes(10))
        val obj = json.parseToJsonElement(response).jsonObject
        return ChatResult(
            answer = obj.getValue("message").jsonObject.getValue("content").jsonPrimitive.content.trim(),
            promptTokens = obj["prompt_eval_count"]?.jsonPrimitive?.long ?: 0,
            answerTokens = obj["eval_count"]?.jsonPrimitive?.long ?: 0,
            totalMs = nanosToMs(obj["total_duration"]),
            evalMs = nanosToMs(obj["eval_duration"]),
        )
    }

    /** Векторы для списка текстов одним запросом (порядок сохраняется). */
    fun embed(model: String, texts: List<String>): List<DoubleArray> {
        val body = buildJsonObject {
            put("model", model)
            putJsonArray("input") { texts.forEach { add(it) } }
        }
        val response = post("/api/embed", body.toString(), Duration.ofMinutes(5))
        return json.parseToJsonElement(response).jsonObject.getValue("embeddings").jsonArray
            .map { row -> DoubleArray(row.jsonArray.size) { i -> row.jsonArray[i].jsonPrimitive.double } }
    }

    private fun nanosToMs(value: kotlinx.serialization.json.JsonElement?): Long =
        ((value as? JsonPrimitive)?.long ?: 0) / 1_000_000

    private fun post(path: String, body: String, timeout: Duration): String {
        val request = HttpRequest.newBuilder()
            .uri(URI.create("$baseUrl$path"))
            .header("Content-Type", "application/json")
            .timeout(timeout)
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()
        val response = http.send(request, HttpResponse.BodyHandlers.ofString())
        check(response.statusCode() == 200) {
            "Ollama $path → HTTP ${response.statusCode()}: ${response.body().take(300)}"
        }
        return response.body()
    }

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
