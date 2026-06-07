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
private data class HfChatRequest(
    val model: String,
    val messages: List<ChatMessage>,
    val stream: Boolean = false,
    @SerialName("max_tokens") val maxTokens: Int = 512,
)

@Serializable
private data class HfChatChoice(
    val message: ChatMessage? = null,
)

@Serializable
private data class HfUsage(
    @SerialName("prompt_tokens") val promptTokens: Int = 0,
    @SerialName("completion_tokens") val completionTokens: Int = 0,
    @SerialName("total_tokens") val totalTokens: Int = 0,
)

@Serializable
private data class HfChatResponse(
    val choices: List<HfChatChoice> = emptyList(),
    val usage: HfUsage? = null,
    val error: HfApiError? = null,
)

@Serializable
private data class HfApiError(
    val message: String? = null,
    @SerialName("type") val type: String? = null,
)

class HuggingFaceClient(
    private val token: String,
    private val baseUrl: String = Config.HF_API_BASE,
) {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    private val http = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(30))
        .build()

    fun chat(model: String, messages: List<ChatMessage>, tier: ModelTier): ChatResult {
        val body = json.encodeToString(
            HfChatRequest(
                model = model,
                messages = messages,
                stream = false,
                maxTokens = 512,
            ),
        )

        val request = HttpRequest.newBuilder()
            .uri(URI.create("$baseUrl/v1/chat/completions"))
            .timeout(Duration.ofMinutes(5))
            .header("Content-Type", "application/json")
            .header("Authorization", "Bearer $token")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()

        val started = System.nanoTime()
        val response = http.send(request, HttpResponse.BodyHandlers.ofString())
        val latencyMs = (System.nanoTime() - started) / 1_000_000

        if (response.statusCode() !in 200..299) {
            val parsed = runCatching { json.decodeFromString<HfChatResponse>(response.body()) }.getOrNull()
            val detail = parsed?.error?.message ?: response.body()
            val hint = if (
                response.statusCode() == 400 &&
                detail.contains("not supported by any provider", ignoreCase = true)
            ) {
                " Проверьте, что модель доступна в Inference Providers: " +
                    "https://huggingface.co/settings/inference-providers " +
                    "или задайте HF_WEAK_MODEL / HF_MEDIUM_MODEL в .env."
            } else {
                ""
            }
            error("HuggingFace API error ${response.statusCode()}: $detail$hint")
        }

        val parsed = json.decodeFromString<HfChatResponse>(response.body())
        val content = parsed.choices.firstOrNull()?.message?.content?.trim()
            ?: error("Пустой ответ от HuggingFace")

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
            estimatedCostUsd = Config.estimateHuggingFaceCost(tier, promptTokens, completionTokens),
            modelId = model,
            tier = tier,
        )
    }
}
