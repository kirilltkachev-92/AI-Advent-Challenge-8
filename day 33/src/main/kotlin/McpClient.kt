import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger

/** Один инструмент, как его описывает MCP-сервер в ответе tools/list. */
data class McpTool(
    val name: String,
    val description: String,
    /** Полная JSON Schema входных параметров — пригодится агенту как описание функции. */
    val inputSchema: JsonObject,
)

/** Результат вызова инструмента: собранный текст и флаг ошибки от сервера. */
data class McpToolResult(
    val text: String,
    val isError: Boolean,
)

/** Кто на том конце: имя и версия сервера из ответа initialize. */
data class McpServerInfo(
    val name: String,
    val version: String,
    val protocolVersion: String,
)

/**
 * Минимальный MCP-клиент поверх транспорта Streamable HTTP (тот же, что в Дне 16).
 *
 * Под капотом — JSON-RPC 2.0: POST с телом {"jsonrpc":"2.0","id":N,"method":...}.
 * Рукопожатие: initialize → notifications/initialized → tools/list.
 */
class McpClient(
    private val serverUrl: String,
    private val authToken: String? = null,
) {
    private val http: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(15))
        .build()

    private val json = Json { ignoreUnknownKeys = true }
    private val nextId = AtomicInteger(1)
    private var sessionId: String? = null

    fun connect(): McpServerInfo {
        val params = buildJsonObject {
            put("protocolVersion", PROTOCOL_VERSION)
            putJsonObject("capabilities") {}
            putJsonObject("clientInfo") {
                put("name", "advent-day33-support-assistant")
                put("version", "1.0.0")
            }
        }
        val result = rpcCall("initialize", params)
        val serverInfo = result["serverInfo"]?.jsonObject
        val info = McpServerInfo(
            name = serverInfo?.get("name")?.jsonPrimitive?.content ?: "unknown",
            version = serverInfo?.get("version")?.jsonPrimitive?.content ?: "unknown",
            protocolVersion = result["protocolVersion"]?.jsonPrimitive?.content ?: PROTOCOL_VERSION,
        )
        sendNotification("notifications/initialized")
        return info
    }

    fun listTools(): List<McpTool> {
        val result = rpcCall("tools/list", buildJsonObject {})
        val tools = result["tools"]?.jsonArray ?: return emptyList()
        return tools.map { element ->
            val obj = element.jsonObject
            McpTool(
                name = obj["name"]?.jsonPrimitive?.content ?: "(без имени)",
                description = obj["description"]?.jsonPrimitive?.content ?: "",
                inputSchema = obj["inputSchema"]?.jsonObject ?: JsonObject(emptyMap()),
            )
        }
    }

    fun callTool(name: String, arguments: JsonObject): McpToolResult {
        val params = buildJsonObject {
            put("name", name)
            put("arguments", arguments)
        }
        val result = rpcCall("tools/call", params)
        val text = result["content"]?.jsonArray
            ?.mapNotNull { block ->
                val obj = block.jsonObject
                if (obj["type"]?.jsonPrimitive?.content == "text") obj["text"]?.jsonPrimitive?.content else null
            }
            ?.joinToString("\n")
            ?: ""
        val isError = result["isError"]?.jsonPrimitive?.content == "true"
        return McpToolResult(text = text, isError = isError)
    }

    // --- транспорт -----------------------------------------------------------

    private fun rpcCall(method: String, params: JsonObject): JsonObject {
        val id = nextId.getAndIncrement()
        val body = buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", id)
            put("method", method)
            put("params", params)
        }
        val response = post(body.toString())
        captureSession(response)
        val payload = extractJsonRpc(response.body())
            ?: error("Пустой ответ на «$method» (HTTP ${response.statusCode()})")
        val message = json.parseToJsonElement(payload).jsonObject
        message["error"]?.jsonObject?.let { err ->
            val code = err["code"]?.jsonPrimitive?.int ?: 0
            val msg = err["message"]?.jsonPrimitive?.content ?: "unknown error"
            error("MCP error $code на «$method»: $msg")
        }
        return message["result"]?.jsonObject
            ?: error("В ответе на «$method» нет поля result")
    }

    private fun sendNotification(method: String) {
        val body = buildJsonObject {
            put("jsonrpc", "2.0")
            put("method", method)
        }
        post(body.toString())
    }

    private fun post(body: String): HttpResponse<String> {
        val builder = HttpRequest.newBuilder()
            .uri(URI.create(serverUrl))
            .timeout(Duration.ofSeconds(60))
            .header("Content-Type", "application/json")
            .header("Accept", "application/json, text/event-stream")
            .POST(HttpRequest.BodyPublishers.ofString(body))
        authToken?.let { builder.header("Authorization", "Bearer $it") }
        sessionId?.let { builder.header("Mcp-Session-Id", it) }
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString())
    }

    private fun captureSession(response: HttpResponse<String>) {
        response.headers().firstValue("Mcp-Session-Id").ifPresent { sessionId = it }
    }

    companion object {
        const val PROTOCOL_VERSION = "2025-06-18"

        /** Достаёт JSON-RPC-сообщение из тела: обычный JSON или SSE (строки data:). */
        fun extractJsonRpc(rawBody: String): String? {
            val body = rawBody.trim()
            if (body.isEmpty()) return null
            if (!body.contains("data:")) return body
            val data = body.lineSequence()
                .filter { it.startsWith("data:") }
                .joinToString("\n") { it.removePrefix("data:").trim() }
                .trim()
            return data.ifEmpty { null }
        }
    }
}
