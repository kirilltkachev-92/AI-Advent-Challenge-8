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
private data class DsChatRequest(
    val model: String,
    val messages: List<ChatMessage>,
    val stream: Boolean = false,
    val temperature: Double? = null,
)

@Serializable
private data class DsChatChoice(
    val message: ChatMessage? = null,
)

@Serializable
private data class DsUsage(
    @SerialName("prompt_tokens") val promptTokens: Int = 0,
    @SerialName("completion_tokens") val completionTokens: Int = 0,
    @SerialName("total_tokens") val totalTokens: Int = 0,
)

@Serializable
private data class DsChatResponse(
    val choices: List<DsChatChoice> = emptyList(),
    val usage: DsUsage? = null,
    val error: DsApiError? = null,
)

@Serializable
private data class DsApiError(
    val message: String? = null,
    @SerialName("type") val type: String? = null,
)

class DeepSeekClient(
    private val apiKey: String,
    private val baseUrl: String = Config.DEEPSEEK_API_BASE,
    private val model: String = Config.DEEPSEEK_STRONG_MODEL_DEFAULT,
) {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    private val http = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(30))
        .build()

    fun chat(
        messages: List<ChatMessage>,
        temperature: Double = 0.3,
        modelOverride: String? = null,
        tier: ModelTier? = null,
    ): ChatResult {
        val activeModel = modelOverride ?: model
        val body = json.encodeToString(
            DsChatRequest(
                model = activeModel,
                messages = messages,
                stream = false,
                temperature = temperature,
            ),
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
            val parsed = runCatching { json.decodeFromString<DsChatResponse>(response.body()) }.getOrNull()
            val detail = parsed?.error?.message ?: response.body()
            error("DeepSeek API error ${response.statusCode()}: $detail")
        }

        val parsed = json.decodeFromString<DsChatResponse>(response.body())
        val content = parsed.choices.firstOrNull()?.message?.content?.trim()
            ?: error("Пустой ответ от DeepSeek")

        val promptText = messages.joinToString { it.content }
        val usage = parsed.usage
        val tokensEstimated: Boolean
        val promptTokens: Int
        val completionTokens: Int
        val totalTokens: Int

        if (usage != null && usage.totalTokens > 0) {
            tokensEstimated = false
            promptTokens = usage.promptTokens
            completionTokens = usage.completionTokens
            totalTokens = usage.totalTokens
        } else {
            tokensEstimated = true
            promptTokens = Config.estimateTokensFromText(promptText)
            completionTokens = Config.estimateTokensFromText(content)
            totalTokens = promptTokens + completionTokens
        }

        return ChatResult(
            content = content,
            latencyMs = latencyMs,
            promptTokens = promptTokens,
            completionTokens = completionTokens,
            totalTokens = totalTokens,
            tokensEstimated = tokensEstimated,
            estimatedCostUsd = Config.estimateDeepSeekCost(promptTokens, completionTokens),
            modelId = activeModel,
            tier = tier,
        )
    }
}
