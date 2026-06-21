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
private data class ChatRequest(
    val model: String,
    val messages: List<ChatMessage>,
    val stream: Boolean = false,
)

@Serializable
private data class ChatChoice(
    val message: ChatMessage? = null,
)

@Serializable
private data class Usage(
    @SerialName("prompt_tokens") val promptTokens: Int = 0,
    @SerialName("completion_tokens") val completionTokens: Int = 0,
    @SerialName("total_tokens") val totalTokens: Int = 0,
)

@Serializable
private data class ChatResponse(
    val choices: List<ChatChoice> = emptyList(),
    val usage: Usage? = null,
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
    private val model: String = Config.model(),
) : ChatClient {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val http = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(30))
        .build()

    override fun chat(messages: List<ChatMessage>): ChatResult {
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

        val started = System.nanoTime()
        val response = http.send(request, HttpResponse.BodyHandlers.ofString())
        val latencyMs = (System.nanoTime() - started) / 1_000_000

        if (response.statusCode() !in 200..299) {
            val parsed = runCatching { json.decodeFromString<ChatResponse>(response.body()) }.getOrNull()
            val detail = parsed?.error?.message ?: response.body()
            error("API error ${response.statusCode()}: $detail")
        }

        val parsed = json.decodeFromString<ChatResponse>(response.body())
        val content = parsed.choices.firstOrNull()?.message?.content?.trim()
            ?: error("Пустой ответ от API")

        val usage = parsed.usage
        val tokensFromApi: Boolean
        val promptTokens: Int
        val completionTokens: Int
        val totalTokens: Int

        if (usage != null && usage.totalTokens > 0) {
            tokensFromApi = true
            promptTokens = usage.promptTokens
            completionTokens = usage.completionTokens
            totalTokens = usage.totalTokens
        } else {
            tokensFromApi = false
            promptTokens = TokenCounter.estimateMessagesTokens(messages)
            completionTokens = TokenCounter.estimateTextTokens(content)
            totalTokens = promptTokens + completionTokens
        }

        return ChatResult(
            content = content,
            latencyMs = latencyMs,
            promptTokens = promptTokens,
            completionTokens = completionTokens,
            totalTokens = totalTokens,
            tokensFromApi = tokensFromApi,
            estimatedCostUsd = Config.estimateCostUsd(promptTokens, completionTokens),
        )
    }
}
