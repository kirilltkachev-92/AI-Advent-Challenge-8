import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * Ollama /api/chat + /api/ps + /api/tags, протокол вручную (как в Днях 26–28).
 *
 * Вся оптимизация Дня 29 проходит через параметр `options` этого клиента:
 * temperature, num_ctx, num_predict, seed — на стороне нашего софта,
 * никакие настройки в GUI не трогаются. Метрики скорости — из счётчиков
 * самой Ollama (eval_count / eval_duration), а не секундомером снаружи.
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
        val totalMs: Long,
        val evalMs: Long,
        val loadMs: Long,
    ) {
        val tokensPerSec: Double
            get() = if (evalMs > 0) answerTokens * 1000.0 / evalMs else 0.0
    }

    /** Загруженная в память модель — размер целиком и та часть, что уехала на GPU. */
    data class LoadedModel(val name: String, val sizeBytes: Long, val vramBytes: Long)

    fun version(): String? = try {
        val response = get("/api/version")
        json.parseToJsonElement(response).jsonObject.getValue("version").jsonPrimitive.content
    } catch (e: Exception) {
        null
    }

    fun localModels(): Map<String, Long> {
        val response = get("/api/tags")
        return json.parseToJsonElement(response).jsonObject.getValue("models").jsonArray
            .associate {
                val obj = it.jsonObject
                obj.getValue("name").jsonPrimitive.content to obj.getValue("size").jsonPrimitive.long
            }
    }

    fun loadedModels(): List<LoadedModel> {
        val response = get("/api/ps")
        return json.parseToJsonElement(response).jsonObject["models"]?.jsonArray.orEmpty()
            .map {
                val obj = it.jsonObject
                LoadedModel(
                    name = obj.getValue("name").jsonPrimitive.content,
                    sizeBytes = obj["size"]?.jsonPrimitive?.long ?: 0,
                    vramBytes = obj["size_vram"]?.jsonPrimitive?.long ?: 0,
                )
            }
    }

    /** Выгружает модель из памяти (keep_alive=0), чтобы профили мерились независимо. */
    fun unload(model: String) {
        val body = buildJsonObject {
            put("model", model)
            put("keep_alive", 0)
        }
        runCatching { post("/api/generate", body.toString(), Duration.ofSeconds(30)) }
    }

    fun chat(
        model: String,
        system: String,
        user: String,
        options: JsonObject? = null,
        jsonMode: Boolean = false,
    ): ChatResult {
        val body = buildJsonObject {
            put("model", model)
            putJsonArray("messages") {
                add(buildJsonObject { put("role", "system"); put("content", system) })
                add(buildJsonObject { put("role", "user"); put("content", user) })
            }
            put("stream", false)
            if (jsonMode) put("format", "json")
            if (options != null) put("options", options)
        }
        val response = post("/api/chat", body.toString(), Duration.ofMinutes(10))
        val obj = json.parseToJsonElement(response).jsonObject
        return ChatResult(
            answer = obj.getValue("message").jsonObject.getValue("content").jsonPrimitive.content.trim(),
            promptTokens = obj["prompt_eval_count"]?.jsonPrimitive?.long ?: 0,
            answerTokens = obj["eval_count"]?.jsonPrimitive?.long ?: 0,
            totalMs = nanosToMs(obj["total_duration"]),
            evalMs = nanosToMs(obj["eval_duration"]),
            loadMs = nanosToMs(obj["load_duration"]),
        )
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
