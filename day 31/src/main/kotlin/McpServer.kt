import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets

/**
 * Описание одного MCP-инструмента — то, что отдаётся в tools/list, плюс обработчик вызова.
 */
class McpToolDef(
    val name: String,
    val description: String,
    val inputSchema: JsonObject,
    val handler: (JsonObject) -> String,
)

/**
 * Свой MCP-сервер, написанный вручную поверх JSON-RPC 2.0 и транспорта
 * Streamable HTTP — тот же код, что в Дне 17, только инструменты другие
 * (git вместо погоды, см. GitMcp.kt).
 *
 * Методы: initialize, notifications/initialized, ping, tools/list, tools/call.
 * На запрос с id отвечаем application/json; на уведомление — 202 без тела.
 */
class McpServer(private val serverName: String = "advent-day31-git-mcp") {
    private val json = Json { ignoreUnknownKeys = true }
    private val tools = LinkedHashMap<String, McpToolDef>()
    private var httpServer: HttpServer? = null

    fun register(tool: McpToolDef): McpServer {
        tools[tool.name] = tool
        return this
    }

    // --- HTTP-транспорт ------------------------------------------------------

    fun start(port: Int, path: String): McpServer {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", port), 0)
        server.createContext(path) { exchange -> handleHttp(exchange) }
        server.executor = null
        server.start()
        httpServer = server
        return this
    }

    fun stop() {
        httpServer?.stop(0)
        httpServer = null
    }

    private fun handleHttp(exchange: HttpExchange) {
        try {
            if (exchange.requestMethod != "POST") {
                respond(exchange, 405, """{"error":"only POST"}""")
                return
            }
            val requestBody = exchange.requestBody.readBytes().toString(StandardCharsets.UTF_8)
            val message = json.parseToJsonElement(requestBody).jsonObject

            val response = handleRpc(message)
            if (response == null) {
                exchange.responseHeaders.add("Mcp-Session-Id", SESSION_ID)
                exchange.sendResponseHeaders(202, -1)
                exchange.close()
            } else {
                exchange.responseHeaders.add("Content-Type", "application/json")
                exchange.responseHeaders.add("Mcp-Session-Id", SESSION_ID)
                respond(exchange, 200, response.toString())
            }
        } catch (e: Exception) {
            respond(exchange, 400, errorEnvelope(JsonNull, -32700, "Parse error: ${e.message}").toString())
        }
    }

    private fun respond(exchange: HttpExchange, status: Int, body: String) {
        val bytes = body.toByteArray(StandardCharsets.UTF_8)
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }

    // --- JSON-RPC ядро ---------------------------------------------------------

    fun handleRpc(message: JsonObject): JsonObject? {
        val method = message["method"]?.jsonPrimitive?.content
        val id: JsonElement = message["id"] ?: JsonNull
        val params = message["params"]?.jsonObject ?: JsonObject(emptyMap())
        val isNotification = message["id"] == null

        return when (method) {
            "initialize" -> result(id, initializeResult())
            "notifications/initialized" -> null
            "ping" -> result(id, buildJsonObject {})
            "tools/list" -> result(id, toolsListResult())
            "tools/call" -> result(id, toolsCallResult(params))
            else -> {
                if (isNotification) null
                else errorEnvelope(id, -32601, "Method not found: $method")
            }
        }
    }

    private fun initializeResult(): JsonObject = buildJsonObject {
        put("protocolVersion", PROTOCOL_VERSION)
        putJsonObject("capabilities") {
            putJsonObject("tools") { put("listChanged", false) }
        }
        putJsonObject("serverInfo") {
            put("name", serverName)
            put("version", "1.0.0")
        }
        put(
            "instructions",
            "Git-контекст репозитория AI Advent Challenge: ветка, статус, файлы, diff, лог.",
        )
    }

    private fun toolsListResult(): JsonObject = buildJsonObject {
        putJsonArray("tools") {
            tools.values.forEach { tool ->
                add(
                    buildJsonObject {
                        put("name", tool.name)
                        put("description", tool.description)
                        put("inputSchema", tool.inputSchema)
                    },
                )
            }
        }
    }

    private fun toolsCallResult(params: JsonObject): JsonObject {
        val name = params["name"]?.jsonPrimitive?.content
        val arguments = params["arguments"]?.jsonObject ?: JsonObject(emptyMap())
        val tool = tools[name]
            ?: return toolContent("Инструмент «$name» не зарегистрирован", isError = true)

        return try {
            toolContent(tool.handler(arguments), isError = false)
        } catch (e: Exception) {
            // По спецификации MCP ошибки инструмента — результат с isError=true,
            // а не JSON-RPC error: агент должен увидеть текст и среагировать.
            toolContent("Ошибка инструмента «$name»: ${e.message}", isError = true)
        }
    }

    private fun toolContent(text: String, isError: Boolean): JsonObject = buildJsonObject {
        putJsonArray("content") {
            add(
                buildJsonObject {
                    put("type", "text")
                    put("text", text)
                },
            )
        }
        put("isError", isError)
    }

    private fun result(id: JsonElement, payload: JsonObject): JsonObject = buildJsonObject {
        put("jsonrpc", "2.0")
        put("id", id)
        put("result", payload)
    }

    private fun errorEnvelope(id: JsonElement, code: Int, msg: String): JsonObject = buildJsonObject {
        put("jsonrpc", "2.0")
        put("id", id)
        putJsonObject("error") {
            put("code", code)
            put("message", msg)
        }
    }

    companion object {
        const val PROTOCOL_VERSION = "2025-06-18"
        private const val SESSION_ID = "advent-day31-session"
    }
}
