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

/** Запись об одном вызове инструмента — чтобы показать, что агент реально ходил в MCP. */
data class ToolInvocation(val tool: String, val arguments: String, val result: String)

/** Итог запроса к агенту: финальный текст + список реальных вызовов MCP. */
data class AgentAnswer(val text: String, val toolCalls: List<ToolInvocation>)

/**
 * Агент-сводка на DeepSeek, подключённый к MCP-инструментам планировщика.
 *
 * По расписанию планировщик зовёт [summarize] — агент САМ вызывает MCP-инструмент
 * weather_summary (function calling) по каждому городу, получает агрегированные данные
 * и формулирует короткую сводку с бытовым советом. Так выполняется требование
 * «агент периодически выдаёт сводку», опираясь на агрегированный результат инструмента.
 */
class SummaryAgent(
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
        "Ты — фоновый агент-метеоролог, который раз в период выдаёт сводку погоды. " +
            "Для КАЖДОГО запрошенного города ОБЯЗАТЕЛЬНО вызови инструмент weather_summary " +
            "и опирайся ТОЛЬКО на его агрегированные данные (не выдумывай числа). " +
            "Затем дай общий короткий отчёт по-русски: 1–2 строки на город с трендом и бытовым " +
            "советом (зонт/одежда). Будь лаконичен."

    /** Просит агента собрать сводку по списку городов. */
    fun summarize(cities: List<String>, maxSteps: Int = 6): AgentAnswer {
        val userMessage = "Сделай сводку погоды по городам: ${cities.joinToString(", ")}. " +
            "По каждому вызови weather_summary."
        return ask(userMessage, maxSteps)
    }

    private fun ask(userMessage: String, maxSteps: Int): AgentAnswer {
        val messages = mutableListOf<JsonObject>(
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

                invocations.add(ToolInvocation(toolName, argsRaw, result.text))
                messages.add(
                    buildJsonObject {
                        put("role", "tool")
                        put("tool_call_id", callId)
                        put("content", result.text)
                    },
                )
            }
        }
        return AgentAnswer("Не удалось завершить сводку за $maxSteps шагов.", invocations)
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
}
