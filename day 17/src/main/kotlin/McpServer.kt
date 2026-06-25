import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
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
 *
 * @param name        идентификатор инструмента (его называет агент в tools/call);
 * @param description что инструмент делает (видит LLM, чтобы решить, когда звать);
 * @param inputSchema JSON Schema входных параметров (properties + required);
 * @param handler     сама логика: получает аргументы, возвращает текстовый результат.
 */
class McpToolDef(
    val name: String,
    val description: String,
    val inputSchema: JsonObject,
    val handler: (JsonObject) -> String,
)

/**
 * Свой MCP-сервер, написанный вручную поверх JSON-RPC 2.0 и транспорта Streamable HTTP
 * (без MCP SDK — чтобы протокол был виден целиком; зеркально клиенту из Дня 16).
 *
 * Поддерживаемые методы:
 *   initialize                 — рукопожатие, отдаём serverInfo и capabilities;
 *   notifications/initialized  — уведомление от клиента (ответа нет);
 *   ping                       — проверка живости;
 *   tools/list                 — РЕГИСТРАЦИЯ инструментов: имя, описание, схема входа;
 *   tools/call                 — ВЫЗОВ инструмента и ВОЗВРАТ результата.
 *
 * Транспорт: единый HTTP-эндпоинт (Config.MCP_PATH). На запрос с id отвечаем
 * application/json; на уведомление (без id) — 202 Accepted без тела.
 */
class McpServer(private val serverName: String = "advent-day17-weather-mcp") {
    private val json = Json { ignoreUnknownKeys = true }
    private val tools = LinkedHashMap<String, McpToolDef>()
    private var httpServer: HttpServer? = null

    /** Регистрация инструмента в сервере. */
    fun register(tool: McpToolDef): McpServer {
        tools[tool.name] = tool
        return this
    }

    // --- HTTP-транспорт ------------------------------------------------------

    /** Поднимает встроенный HTTP-сервер и начинает слушать JSON-RPC на [path]. */
    fun start(port: Int, path: String): McpServer {
        val server = HttpServer.create(InetSocketAddress(port), 0)
        server.createContext(path) { exchange -> handleHttp(exchange) }
        server.executor = null // дефолтный исполнитель — для учебного сервера достаточно
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
                // Уведомление (без id) — по протоколу отвечаем 202 без тела.
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

    // --- JSON-RPC ядро (без сети — удобно тестировать юнит-тестом) ------------

    /**
     * Обрабатывает одно JSON-RPC сообщение. Возвращает JSON-ответ или null,
     * если это уведомление (id отсутствует), на которое отвечать не нужно.
     */
    fun handleRpc(message: JsonObject): JsonObject? {
        val method = message["method"]?.jsonPrimitive?.content
        val id: JsonElement = message["id"] ?: JsonNull
        val params = message["params"]?.jsonObject ?: JsonObject(emptyMap())
        val isNotification = message["id"] == null

        return when (method) {
            "initialize" -> result(id, initializeResult())
            "notifications/initialized" -> null // подтверждение готовности клиента
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
        put("instructions", "Сервер погоды поверх Open-Meteo. Зовите get_weather с названием города.")
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

    /** Выполняет tools/call: ищет инструмент, зовёт handler, оборачивает результат/ошибку. */
    private fun toolsCallResult(params: JsonObject): JsonObject {
        val name = params["name"]?.jsonPrimitive?.content
        val arguments = params["arguments"]?.jsonObject ?: JsonObject(emptyMap())
        val tool = tools[name]
            ?: return toolContent("Инструмент «$name» не зарегистрирован", isError = true)

        return try {
            toolContent(tool.handler(arguments), isError = false)
        } catch (e: Exception) {
            // Ошибки инструмента возвращаем как результат с isError=true (по спецификации MCP),
            // а не как JSON-RPC error — чтобы агент мог увидеть текст и среагировать.
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
        private const val SESSION_ID = "advent-day17-session"

        /**
         * Собирает сервер с зарегистрированными инструментами вокруг Open-Meteo.
         * Здесь — РЕГИСТРАЦИЯ инструментов и ОПИСАНИЕ входных параметров (inputSchema).
         */
        fun weatherServer(api: WeatherApi = WeatherApi()): McpServer {
            val server = McpServer()

            // Инструмент 1: текущая погода в городе.
            server.register(
                McpToolDef(
                    name = "get_weather",
                    description = "Возвращает текущую погоду в указанном городе " +
                        "(температуру, ветер, состояние неба). Данные — Open-Meteo.",
                    inputSchema = buildJsonObject {
                        put("type", "object")
                        putJsonObject("properties") {
                            putJsonObject("city") {
                                put("type", "string")
                                put("description", "Название города, например «Берлин» или «Tokyo».")
                            }
                        }
                        putJsonArray("required") { add("city") }
                    },
                ) { args ->
                    val city = args["city"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
                        ?: error("не задан обязательный параметр city")
                    val w = api.currentWeather(city)
                    "Погода в ${w.place.name} (${w.place.country}): ${w.description}, " +
                        "температура ${"%.1f".format(w.temperatureC)}°C, " +
                        "ветер ${"%.1f".format(w.windSpeed)} км/ч. " +
                        "Время измерения: ${w.time} (${w.place.timezone})."
                },
            )

            // Инструмент 2: геокодирование — координаты города.
            server.register(
                McpToolDef(
                    name = "geocode_city",
                    description = "Находит географические координаты (широту и долготу) города.",
                    inputSchema = buildJsonObject {
                        put("type", "object")
                        putJsonObject("properties") {
                            putJsonObject("city") {
                                put("type", "string")
                                put("description", "Название города для поиска координат.")
                            }
                        }
                        putJsonArray("required") { add("city") }
                    },
                ) { args ->
                    val city = args["city"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
                        ?: error("не задан обязательный параметр city")
                    val p = api.geocode(city) ?: error("город «$city» не найден")
                    "${p.name} (${p.country}): широта ${p.latitude}, долгота ${p.longitude}, " +
                        "часовой пояс ${p.timezone}."
                },
            )

            return server
        }
    }
}
