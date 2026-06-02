import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

@Serializable
data class ChatMessage(
    val role: String,
    val content: String,
)

@Serializable
private data class ChatRequest(
    val model: String,
    val messages: List<ChatMessage>,
    val stream: Boolean = false,
)

@Serializable
private data class ChatChoice(
    val message: ChatMessage? = null,
    val delta: ChatMessage? = null,
)

@Serializable
private data class ChatResponse(
    val choices: List<ChatChoice> = emptyList(),
    val error: ApiError? = null,
)

@Serializable
private data class ApiError(
    val message: String? = null,
    @SerialName("type") val type: String? = null,
)

class DeepSeekClient(
    private val apiKey: String,
    private val baseUrl: String = Config.API_BASE,
    private val model: String = Config.MODEL,
) {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val http = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(30))
        .build()

    fun chat(messages: List<ChatMessage>): String {
        val body = json.encodeToString(
            ChatRequest(model = model, messages = messages, stream = false),
        )

        val request = HttpRequest.newBuilder()
            .uri(URI.create("$baseUrl/chat/completions"))
            .timeout(Duration.ofMinutes(5))
            .header("Content-Type", "application/json")
            .header("Authorization", "Bearer $apiKey")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()

        val response = http.send(request, HttpResponse.BodyHandlers.ofString())

        if (response.statusCode() !in 200..299) {
            val parsed = runCatching { json.decodeFromString<ChatResponse>(response.body()) }.getOrNull()
            val detail = parsed?.error?.message ?: response.body()
            error("API error ${response.statusCode()}: $detail")
        }

        val parsed = json.decodeFromString<ChatResponse>(response.body())
        return parsed.choices.firstOrNull()?.message?.content?.trim()
            ?: error("Empty response from API")
    }
}
