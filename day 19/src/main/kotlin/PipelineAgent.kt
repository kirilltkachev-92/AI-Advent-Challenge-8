import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
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

/** Запись об одном вызове инструмента в цепочке. */
data class ToolInvocation(val tool: String, val arguments: String, val result: String)

/** Итог запроса: финальный текст агента + последовательность реальных вызовов MCP. */
data class AgentAnswer(val text: String, val toolCalls: List<ToolInvocation>)

/**
 * Агент-пайплайн на DeepSeek (function calling), подключённый к MCP-инструментам.
 *
 * КЛЮЧЕВОЕ для Дня 19: последовательность инструментов НЕ захардкожена в коде —
 * её выбирает САМА модель, исходя из запроса пользователя. Агент лишь в цикле
 * выполняет запрошенные tool_calls и возвращает результат каждого обратно в модель,
 * поэтому данные естественно перетекают из search → summarize → save_to_file.
 */
class PipelineAgent(
    private val mcp: McpClient,
    mcpTools: List<McpTool>,
    private val apiKey: String,
    private val model: String = Config.model(),
) {
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30)).build()
    private val json = Json { ignoreUnknownKeys = true }

    private val toolsJson: JsonArray = buildJsonArray {
        mcpTools.forEach { tool ->
            add(
                buildJsonObject {
                    put("type", "function")
                    putJsonObject("function") {
                        put("name", tool.name)
                        put("description", tool.description)
                        put("parameters", tool.inputSchema)
                    }
                },
            )
        }
    }

    private val systemPrompt =
        "Ты — агент с MCP-инструментами. Выполняй запрос пользователя, самостоятельно " +
            "вызывая нужные инструменты и ПЕРЕДАВАЯ результат одного инструмента в следующий. " +
            "Если просят найти и сохранить КОНСПЕКТ/итог — это цепочка: search (найти статьи) → " +
            "summarize (собрать конспект по найденным заголовкам) → save_to_file (сохранить конспект). " +
            "Если же просят сохранить ОРИГИНАЛЬНЫЙ текст статей / тексты по отдельности / каждую в свой " +
            "файл БЕЗ суммаризации — это цепочка: search → save_articles (минуя summarize). " +
            "Не выдумывай содержимое — бери его из результатов инструментов. " +
            "В конце кратко ответь по-русски и укажи путь к сохранённому файлу."

    /** Выполняет запрос пользователя, позволяя модели построить цепочку из инструментов. */
    fun run(userMessage: String, maxSteps: Int = 8): AgentAnswer {
        val messages = mutableListOf(
            buildJsonObject { put("role", "system"); put("content", systemPrompt) },
            buildJsonObject { put("role", "user"); put("content", userMessage) },
        )
        val invocations = mutableListOf<ToolInvocation>()

        repeat(maxSteps) {
            val assistant = chat(messages)
            messages.add(assistant)

            val toolCalls = assistant["tool_calls"]?.jsonArray
            if (toolCalls.isNullOrEmpty()) {
                val text = assistant["content"]?.jsonPrimitive?.content.orEmpty().trim()
                return AgentAnswer(text, invocations)
            }

            for (call in toolCalls) {
                val callObj = call.jsonObject
                val callId = callObj["id"]?.jsonPrimitive?.content ?: ""
                val fn = callObj["function"]?.jsonObject
                val toolName = fn?.get("name")?.jsonPrimitive?.content ?: ""
                val argsRaw = fn?.get("arguments")?.jsonPrimitive?.content ?: "{}"
                val args = runCatching { json.parseToJsonElement(argsRaw).jsonObject }
                    .getOrElse { JsonObject(emptyMap()) }

                val result = runCatching { mcp.callTool(toolName, args) }
                    .getOrElse { McpToolResult("Ошибка вызова MCP: ${it.message}", isError = true) }

                invocations.add(ToolInvocation(toolName, compact(argsRaw), result.text))
                messages.add(
                    buildJsonObject {
                        put("role", "tool")
                        put("tool_call_id", callId)
                        put("content", result.text)
                    },
                )
            }
        }
        return AgentAnswer("Не удалось завершить цепочку за $maxSteps шагов.", invocations)
    }

    private fun chat(messages: List<JsonObject>): JsonObject {
        val body = buildJsonObject {
            put("model", model)
            put("messages", JsonArray(messages))
            put("tools", toolsJson)
            put("tool_choice", "auto")
            put("stream", false)
        }
        val request = HttpRequest.newBuilder()
            .uri(URI.create("${Config.API_BASE}/chat/completions"))
            .timeout(Duration.ofMinutes(2))
            .header("Content-Type", "application/json")
            .header("Authorization", "Bearer $apiKey")
            .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
            .build()
        val response = http.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() !in 200..299) {
            error("DeepSeek HTTP ${response.statusCode()}: ${response.body().take(300)}")
        }
        return json.parseToJsonElement(response.body()).jsonObject["choices"]
            ?.jsonArray?.firstOrNull()?.jsonObject?.get("message")?.jsonObject
            ?: error("Пустой ответ DeepSeek")
    }

    /** Укорачивает длинные аргументы (например, content конспекта) для читаемого лога цепочки. */
    private fun compact(argsRaw: String): String {
        val oneLine = argsRaw.replace(Regex("\\s+"), " ").trim()
        return if (oneLine.length <= 160) oneLine else oneLine.take(160) + "…}"
    }
}
