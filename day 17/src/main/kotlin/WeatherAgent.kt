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

/** Запись об одном вызове инструмента — чтобы показать пользователю, что агент реально ходил в MCP. */
data class ToolInvocation(val tool: String, val arguments: String, val result: String)

/** Итог одного запроса к агенту: финальный ответ модели и список реальных вызовов MCP. */
data class AgentAnswer(val text: String, val toolCalls: List<ToolInvocation>)

/**
 * Агент на DeepSeek (OpenAI-совместимый function calling), подключённый к MCP-инструментам.
 *
 * Цикл работы:
 *   1) отдаём модели system + историю + СПИСОК ИНСТРУМЕНТОВ (из tools/list, как функции);
 *   2) если модель просит tool_calls — ВЫЗЫВАЕМ инструмент через MCP (tools/call);
 *   3) возвращаем результат инструмента в модель;
 *   4) повторяем, пока модель не даст финальный текстовый ответ (ИСПОЛЬЗУЕТ результат).
 */
class WeatherAgent(
    private val mcp: McpClient,
    mcpTools: List<McpTool>,
    private val apiKey: String,
    private val model: String = Config.model(),
) {
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30)).build()
    private val json = Json { ignoreUnknownKeys = true }

    // Инструменты MCP → формат функций DeepSeek/OpenAI (inputSchema идёт как parameters).
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
        "Ты — голосовой помощник по погоде. Когда пользователь спрашивает о погоде или " +
            "координатах города, ОБЯЗАТЕЛЬНО вызывай подходящий инструмент и отвечай, опираясь " +
            "на его результат. Не выдумывай числа. Отвечай кратко и по-русски."

    /** Обрабатывает один запрос пользователя. maxSteps ограничивает число раундов tool-calling. */
    fun ask(userMessage: String, maxSteps: Int = 4): AgentAnswer {
        val messages = mutableListOf<JsonObject>(
            buildJsonObject {
                put("role", "system")
                put("content", systemPrompt)
            },
            buildJsonObject {
                put("role", "user")
                put("content", userMessage)
            },
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

            // Модель попросила инструменты — выполняем каждый через MCP и возвращаем результат.
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
        return AgentAnswer("Не удалось завершить ответ за $maxSteps шагов.", invocations)
    }

    /** Один запрос к DeepSeek; возвращает message ассистента (с возможными tool_calls). */
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
