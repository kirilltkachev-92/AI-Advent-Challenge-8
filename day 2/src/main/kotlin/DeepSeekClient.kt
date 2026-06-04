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

enum class ResponseControlMode {
    /** Без ограничений */
    Unrestricted,
    /** Явное описание формата ответа (system) */
    FormatDescription,
    /** Ограничение длины (max_tokens) */
    LengthLimit,
    /** Условие завершения (stop + инструкция в system) */
    StopCondition,
}

data class ChatResult(
    val content: String,
    val requestBodyJson: String,
    val mode: ResponseControlMode,
)

@Serializable
private data class ChatRequest(
    val model: String,
    val messages: List<ChatMessage>,
    val stream: Boolean = false,
    val max_tokens: Int? = null,
    val stop: List<String>? = null,
    val temperature: Double? = null,
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
        explicitNulls = false
    }

    private val http = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(30))
        .build()

    fun systemPrompt(mode: ResponseControlMode): String = when (mode) {
        ResponseControlMode.Unrestricted,
        ResponseControlMode.LengthLimit,
        -> Config.SYSTEM_BASELINE
        ResponseControlMode.FormatDescription -> Config.SYSTEM_FORMAT
        ResponseControlMode.StopCondition -> Config.SYSTEM_STOP
    }

    fun messagesForUserPrompt(userContent: String, mode: ResponseControlMode): List<ChatMessage> =
        listOf(
            ChatMessage(role = "system", content = systemPrompt(mode)),
            ChatMessage(role = "user", content = userContent),
        )

    fun chat(messages: List<ChatMessage>, mode: ResponseControlMode = ResponseControlMode.Unrestricted): String =
        chatWithDetails(messages, mode).content

    fun chatWithDetails(messages: List<ChatMessage>, mode: ResponseControlMode): ChatResult {
        val request = when (mode) {
            ResponseControlMode.Unrestricted -> ChatRequest(
                model = model,
                messages = messages,
                stream = false,
                temperature = Config.COMPARE_TEMPERATURE,
            )
            ResponseControlMode.FormatDescription -> ChatRequest(
                model = model,
                messages = messages,
                stream = false,
                temperature = Config.COMPARE_TEMPERATURE,
            )
            ResponseControlMode.LengthLimit -> ChatRequest(
                model = model,
                messages = messages,
                stream = false,
                max_tokens = Config.LENGTH_MAX_TOKENS,
                temperature = Config.COMPARE_TEMPERATURE,
            )
            ResponseControlMode.StopCondition -> ChatRequest(
                model = model,
                messages = messages,
                stream = false,
                stop = Config.STOP_SEQUENCES,
                temperature = Config.COMPARE_TEMPERATURE,
            )
        }
        val body = json.encodeToString(request)
        println("[DeepSeek] mode=$mode request:\n$body")

        val httpRequest = HttpRequest.newBuilder()
            .uri(URI.create("$baseUrl/chat/completions"))
            .timeout(Duration.ofMinutes(5))
            .header("Content-Type", "application/json")
            .header("Authorization", "Bearer $apiKey")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()

        val response = http.send(httpRequest, HttpResponse.BodyHandlers.ofString())

        if (response.statusCode() !in 200..299) {
            val parsed = runCatching { json.decodeFromString<ChatResponse>(response.body()) }.getOrNull()
            val detail = parsed?.error?.message ?: response.body()
            error("API error ${response.statusCode()}: $detail")
        }

        val parsed = json.decodeFromString<ChatResponse>(response.body())
        val content = parsed.choices.firstOrNull()?.message?.content?.trim()
            ?: error("Empty response from API")

        return ChatResult(content = content, requestBodyJson = body, mode = mode)
    }
}

fun modeLabel(mode: ResponseControlMode): String = when (mode) {
    ResponseControlMode.Unrestricted -> "Без ограничений"
    ResponseControlMode.FormatDescription -> "Формат ответа"
    ResponseControlMode.LengthLimit -> "Ограничение длины"
    ResponseControlMode.StopCondition -> "Условие завершения"
}
