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
import java.time.Instant

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
 * (без MCP SDK — зеркально клиенту из Дня 16). День 18 добавляет к нему инструменты
 * планировщика: сбор замеров с персистом и агрегированную сводку.
 *
 * Поддерживаемые методы:
 *   initialize / notifications/initialized / ping
 *   tools/list  — РЕГИСТРАЦИЯ инструментов: имя, описание, схема входа;
 *   tools/call  — ВЫЗОВ инструмента и ВОЗВРАТ результата.
 */
class McpServer(private val serverName: String = "advent-day18-weather-scheduler-mcp") {
    private val json = Json { ignoreUnknownKeys = true }
    private val tools = LinkedHashMap<String, McpToolDef>()
    private var httpServer: HttpServer? = null

    /** Регистрация инструмента в сервере. */
    fun register(tool: McpToolDef): McpServer {
        tools[tool.name] = tool
        return this
    }

    // --- HTTP-транспорт ------------------------------------------------------

    fun start(port: Int, path: String): McpServer {
        val server = HttpServer.create(InetSocketAddress(port), 0)
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

    // --- JSON-RPC ядро (без сети — удобно тестировать) -----------------------

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
        put("instructions", "Планировщик погоды: record_weather_sample собирает замер, " +
            "weather_summary отдаёт агрегированную сводку по сохранённым данным.")
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
        private const val SESSION_ID = "advent-day18-session"

        /** Схема одного обязательного строкового параметра `city`. */
        private fun citySchema(desc: String): JsonObject = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("city") {
                    put("type", "string")
                    put("description", desc)
                }
            }
            putJsonArray("required") { add("city") }
        }

        private fun requireCity(args: JsonObject): String =
            args["city"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
                ?: error("не задан обязательный параметр city")

        /**
         * Собирает MCP-сервер планировщика погоды. Здесь — РЕГИСТРАЦИЯ инструментов
         * и ОПИСАНИЕ входных параметров.
         *
         * @param store   персистентное хранилище замеров (JSON-файл);
         * @param weather функция получения текущей погоды (по умолчанию — реальный Open-Meteo;
         *                в тестах подменяется заглушкой без сети).
         */
        fun schedulerServer(
            store: SampleStore,
            weather: (String) -> CurrentWeather = WeatherApi()::currentWeather,
        ): McpServer {
            val server = McpServer()

            // 1) Текущая погода — источник данных (как в Дне 17), без персиста.
            server.register(
                McpToolDef(
                    name = "get_weather",
                    description = "Возвращает текущую погоду в городе (температура, ветер, небо). Источник — Open-Meteo.",
                    inputSchema = citySchema("Название города, например «Берлин» или «Tokyo»."),
                ) { args ->
                    val w = weather(requireCity(args))
                    "Погода в ${w.place.name} (${w.place.country}): ${w.description}, " +
                        "температура ${"%.1f".format(w.temperatureC)}°C, ветер ${"%.1f".format(w.windSpeed)} км/ч."
                },
            )

            // 2) СБОР ДАННЫХ ПО РАСПИСАНИЮ: снять замер и СОХРАНИТЬ его в хранилище.
            //    Этот инструмент планировщик дёргает на каждый тик.
            server.register(
                McpToolDef(
                    name = "record_weather_sample",
                    description = "Снимает текущий замер погоды по городу и СОХРАНЯЕТ его в хранилище " +
                        "(для последующей агрегированной сводки). Возвращает сохранённый замер и их общее число.",
                    inputSchema = citySchema("Город, по которому снять и сохранить замер."),
                ) { args ->
                    val city = requireCity(args)
                    val w = weather(city)
                    val sample = WeatherSample(
                        time = w.time,
                        fetchedAt = Instant.now().toString(),
                        temperatureC = w.temperatureC,
                        windSpeed = w.windSpeed,
                        weatherCode = w.weatherCode,
                        description = w.description,
                    )
                    val count = store.add(city, sample)
                    "Замер по «$city» сохранён: ${w.description}, ${"%.1f".format(w.temperatureC)}°C, " +
                        "ветер ${"%.1f".format(w.windSpeed)} км/ч. Всего замеров по городу: $count."
                },
            )

            // 3) АГРЕГИРОВАННЫЙ РЕЗУЛЬТАТ: сводка по сохранённым замерам.
            //    Этот инструмент планировщик/агент зовёт периодически для отчёта.
            server.register(
                McpToolDef(
                    name = "weather_summary",
                    description = "Возвращает агрегированную сводку по СОХРАНЁННЫМ замерам города: " +
                        "средняя/мин/макс температура, средний ветер, частое состояние неба и тренд.",
                    inputSchema = citySchema("Город, по которому построить сводку из сохранённых замеров."),
                ) { args ->
                    val city = requireCity(args)
                    WeatherSummary.aggregate(city, store.samples(city))
                },
            )

            return server
        }
    }
}
