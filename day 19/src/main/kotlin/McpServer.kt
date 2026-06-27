import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
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
 * Свой MCP-сервер (JSON-RPC 2.0 поверх Streamable HTTP, без SDK; зеркально Дням 16–18).
 *
 * День 19 — КОМПОЗИЦИЯ ИНСТРУМЕНТОВ: один сервер отдаёт несколько tools (search →
 * summarize → save_to_file), а агент по запросу пользователя сам выстраивает из них
 * цепочку. Никакого LLM на сервере нет — он только источник данных и файловое хранилище.
 */
class McpServer(private val serverName: String = "advent-day19-composition-mcp") {
    private val json = Json { ignoreUnknownKeys = true }
    private val tools = LinkedHashMap<String, McpToolDef>()
    private var httpServer: HttpServer? = null

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

    // --- JSON-RPC ядро -------------------------------------------------------

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
        put("instructions", "Пайплайн знаний: search ищет статьи, summarize собирает конспект, " +
            "save_to_file сохраняет его в файл; save_articles сохраняет полный текст каждой статьи " +
            "в отдельный файл без суммаризации. Передавайте результат одного инструмента в следующий.")
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
        private const val SESSION_ID = "advent-day19-session"

        private fun stringProp(builder: kotlinx.serialization.json.JsonObjectBuilder, name: String, desc: String) {
            builder.putJsonObject(name) {
                put("type", "string")
                put("description", desc)
            }
        }

        /**
         * Собирает MCP-сервер из ТРЁХ инструментов пайплайна (search → summarize → save_to_file).
         * Источник данных и хранилище инъектируются — в тестах подменяются заглушками без сети.
         *
         * @param search  поиск статей: (запрос, лимит) → список найденного;
         * @param extract вводный текст статьи по заголовку (для summarize);
         * @param saver   сохранение результата в файл;
         * @param article полный текст статьи по заголовку (для save_articles —
         *                сохранение оригинала без суммаризации); по умолчанию = extract.
         */
        fun compositionServer(
            search: (String, Int) -> List<WikiHit>,
            extract: (String) -> String,
            saver: NoteSaver,
            article: (String) -> String = extract,
        ): McpServer {
            val server = McpServer()

            // 1) ПОЛУЧАЕТ ДАННЫЕ: поиск статей по запросу.
            server.register(
                McpToolDef(
                    name = "search",
                    description = "Ищет статьи в Википедии по запросу. Возвращает список заголовков " +
                        "с короткими сниппетами. Заголовки нужно передать в инструмент summarize.",
                    inputSchema = buildJsonObject {
                        put("type", "object")
                        putJsonObject("properties") {
                            stringProp(this, "query", "Поисковый запрос, например «Эрмитаж» или «Kotlin».")
                            putJsonObject("limit") {
                                put("type", "integer")
                                put("description", "Сколько статей вернуть (1–10, по умолчанию 5).")
                            }
                        }
                        putJsonArray("required") { add("query") }
                    },
                ) { args ->
                    val query = requireString(args, "query")
                    val limit = (args["limit"]?.jsonPrimitive?.intOrNull() ?: 5).coerceIn(1, 10)
                    val hits = search(query, limit)
                    if (hits.isEmpty()) {
                        "По запросу «$query» ничего не найдено."
                    } else {
                        buildString {
                            appendLine("Найдено статей: ${hits.size} (запрос «$query»).")
                            hits.forEach { appendLine("• ${it.title} — ${it.snippet}") }
                            append("Передайте нужные заголовки в summarize (titles).")
                        }
                    }
                },
            )

            // 2) ОБРАБАТЫВАЕТ: собирает конспект-итог по переданным заголовкам.
            server.register(
                McpToolDef(
                    name = "summarize",
                    description = "Собирает конспект (итоговый отчёт) по списку заголовков статей: " +
                        "берёт вводные абзацы из Википедии и складывает в один markdown-текст. " +
                        "Результат нужно передать в save_to_file (content).",
                    inputSchema = buildJsonObject {
                        put("type", "object")
                        putJsonObject("properties") {
                            stringProp(this, "topic", "Тема конспекта (обычно исходный запрос пользователя).")
                            putJsonObject("titles") {
                                put("type", "array")
                                put("description", "Заголовки статей из инструмента search.")
                                putJsonObject("items") { put("type", "string") }
                            }
                        }
                        putJsonArray("required") { add("titles") }
                    },
                ) { args ->
                    val titles = (args["titles"]?.jsonArray ?: error("не задан обязательный параметр titles"))
                        .mapNotNull { it.jsonPrimitive.contentOrNull() }
                        .filter { it.isNotBlank() }
                    if (titles.isEmpty()) error("список titles пуст")
                    val topic = args["topic"]?.jsonPrimitive?.contentOrNull()?.takeIf { it.isNotBlank() }
                        ?: titles.first()
                    val sections = titles.map { it to extract(it) }
                    Report.build(topic, sections)
                },
            )

            // 3) СОХРАНЯЕТ РЕЗУЛЬТАТ: пишет переданный текст в файл.
            server.register(
                McpToolDef(
                    name = "save_to_file",
                    description = "Сохраняет переданный текст (content) в файл с именем filename. " +
                        "Возвращает путь к сохранённому файлу. Финальный шаг пайплайна.",
                    inputSchema = buildJsonObject {
                        put("type", "object")
                        putJsonObject("properties") {
                            stringProp(this, "filename", "Имя файла, например «эрмитаж.md».")
                            stringProp(this, "content", "Содержимое для сохранения (обычно конспект из summarize).")
                        }
                        putJsonArray("required") { add("filename"); add("content") }
                    },
                ) { args ->
                    val filename = requireString(args, "filename")
                    val content = requireString(args, "content")
                    val saved = saver.save(filename, content)
                    "Сохранено в ${saved.path} (${saved.bytes} байт)."
                },
            )

            // 4) СОХРАНЯЕТ ОРИГИНАЛ (без суммаризации): каждую статью — в свой файл.
            server.register(
                McpToolDef(
                    name = "save_articles",
                    description = "Сохраняет ПОЛНЫЙ оригинальный текст каждой статьи в ОТДЕЛЬНЫЙ файл " +
                        "(по одному файлу на заголовок), БЕЗ суммаризации. Используй вместо summarize+save_to_file, " +
                        "когда пользователь просит сохранить тексты статей как есть / по отдельности / оригиналом.",
                    inputSchema = buildJsonObject {
                        put("type", "object")
                        putJsonObject("properties") {
                            putJsonObject("titles") {
                                put("type", "array")
                                put("description", "Заголовки статей из инструмента search.")
                                putJsonObject("items") { put("type", "string") }
                            }
                        }
                        putJsonArray("required") { add("titles") }
                    },
                ) { args ->
                    val titles = (args["titles"]?.jsonArray ?: error("не задан обязательный параметр titles"))
                        .mapNotNull { it.jsonPrimitive.contentOrNull() }
                        .filter { it.isNotBlank() }
                    if (titles.isEmpty()) error("список titles пуст")
                    val saved = titles.mapNotNull { title ->
                        val text = article(title)
                        if (text.isBlank()) null
                        else title to saver.save("$title.md", "# $title\n\n$text\n")
                    }
                    if (saved.isEmpty()) error("ни по одному заголовку не удалось получить текст статьи")
                    buildString {
                        appendLine("Сохранено статей по отдельности (без суммаризации): ${saved.size}.")
                        saved.forEach { (title, res) -> appendLine("• $title → ${res.path} (${res.bytes} байт)") }
                        append("Каждая статья сохранена в свой файл оригинальным текстом.")
                    }
                },
            )

            return server
        }

        private fun requireString(args: JsonObject, key: String): String =
            args[key]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
                ?: error("не задан обязательный параметр $key")

        private fun kotlinx.serialization.json.JsonPrimitive.intOrNull(): Int? =
            runCatching { int }.getOrNull()

        private fun kotlinx.serialization.json.JsonPrimitive.contentOrNull(): String? = content
    }
}
