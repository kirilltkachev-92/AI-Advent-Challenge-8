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

/** Один инструмент, как его описывает MCP-сервер в ответе на tools/list. */
data class McpTool(
    val name: String,
    val description: String,
    /** Имена обязательных входных параметров (из inputSchema.required). */
    val requiredParams: List<String>,
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
 * Минимальный MCP-клиент поверх транспорта Streamable HTTP.
 *
 * Под капотом — обычный JSON-RPC 2.0: каждый запрос это POST с телом
 * {"jsonrpc":"2.0","id":N,"method":...}. Сервер отвечает либо application/json,
 * либо потоком text/event-stream (SSE) — клиент понимает оба формата.
 *
 * Рукопожатие по протоколу:
 *   1) initialize                 — договариваемся о версии и возможностях;
 *   2) notifications/initialized  — подтверждаем, что готовы (уведомление, без ответа);
 *   3) tools/list                 — получаем список инструментов.
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

    // Сервер может выдать идентификатор сессии в заголовке Mcp-Session-Id —
    // его нужно возвращать во всех последующих запросах.
    private var sessionId: String? = null

    // Согласованная версия протокола. После initialize строгие серверы (напр. MS Learn)
    // требуют слать её в заголовке MCP-Protocol-Version на всех дальнейших запросах.
    private var negotiatedProtocol: String? = null

    /** Шаг 1+2 рукопожатия. Возвращает, кто на сервере. */
    fun connect(): McpServerInfo {
        val params = buildJsonObject {
            put("protocolVersion", PROTOCOL_VERSION)
            putJsonObject("capabilities") {}
            putJsonObject("clientInfo") {
                put("name", "advent-day16-mcp-client")
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

        // С этого момента шлём согласованную версию протокола в заголовке.
        negotiatedProtocol = info.protocolVersion

        // Уведомление без id и без ожидания ответа — завершает рукопожатие.
        sendNotification("notifications/initialized")
        return info
    }

    /** Шаг 3: список доступных инструментов. */
    fun listTools(): List<McpTool> {
        val result = rpcCall("tools/list", buildJsonObject {})
        val tools = result["tools"]?.jsonArray ?: return emptyList()
        return tools.map { element ->
            val obj = element.jsonObject
            val required = obj["inputSchema"]?.jsonObject
                ?.get("required")?.jsonArray
                ?.map { it.jsonPrimitive.content }
                ?: emptyList()
            McpTool(
                name = obj["name"]?.jsonPrimitive?.content ?: "(без имени)",
                description = obj["description"]?.jsonPrimitive?.content?.lineSequence()?.first()?.trim()
                    ?: "",
                requiredParams = required,
            )
        }
    }

    /**
     * Вызывает инструмент (tools/call) и возвращает его текстовый результат.
     *
     * @param name      имя инструмента из [listTools];
     * @param arguments аргументы как JSON-объект (`{}` — без аргументов).
     */
    fun callTool(name: String, arguments: JsonObject): McpToolResult {
        val params = buildJsonObject {
            put("name", name)
            put("arguments", arguments)
        }
        val result = rpcCall("tools/call", params)

        // Результат — массив блоков content; нас интересуют текстовые.
        val text = result["content"]?.jsonArray
            ?.mapNotNull { block ->
                val obj = block.jsonObject
                if (obj["type"]?.jsonPrimitive?.content == "text") {
                    obj["text"]?.jsonPrimitive?.content
                } else {
                    null
                }
            }
            ?.joinToString("\n")
            ?: ""
        val isError = result["isError"]?.jsonPrimitive?.content == "true"
        return McpToolResult(text = text, isError = isError)
    }

    // --- транспорт -----------------------------------------------------------

    /** Отправляет JSON-RPC-запрос и возвращает поле result; на JSON-RPC error кидает исключение. */
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

    /** Отправляет уведомление (без id) — ответа от сервера не ждём. */
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
            // tools/call может надолго запускать удалённый инструмент (напр. скрейпер Apify),
            // поэтому таймаут запроса щедрый.
            .timeout(Duration.ofSeconds(180))
            .header("Content-Type", "application/json")
            // Принимаем оба формата ответа MCP — простой JSON и поток событий.
            .header("Accept", "application/json, text/event-stream")
            .POST(HttpRequest.BodyPublishers.ofString(body))
        authToken?.let { builder.header("Authorization", "Bearer $it") }
        sessionId?.let { builder.header("Mcp-Session-Id", it) }
        negotiatedProtocol?.let { builder.header("MCP-Protocol-Version", it) }
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString())
    }

    private fun captureSession(response: HttpResponse<String>) {
        response.headers().firstValue("Mcp-Session-Id").ifPresent { sessionId = it }
    }

    companion object {
        const val PROTOCOL_VERSION = "2025-06-18"

        /**
         * Достаёт JSON-RPC-сообщение из тела ответа. Если это SSE (text/event-stream),
         * берём данные из строк «data:»; иначе считаем тело обычным JSON.
         * Вынесено в companion и без сети — чтобы покрыть юнит-тестом.
         */
        fun extractJsonRpc(rawBody: String): String? {
            val body = rawBody.trim()
            if (body.isEmpty()) return null
            if (!body.contains("data:")) return body // обычный application/json

            // SSE: собираем все строки data: одного события в один JSON.
            val data = body.lineSequence()
                .filter { it.startsWith("data:") }
                .joinToString("\n") { it.removePrefix("data:").trim() }
                .trim()
            return data.ifEmpty { null }
        }
    }
}
