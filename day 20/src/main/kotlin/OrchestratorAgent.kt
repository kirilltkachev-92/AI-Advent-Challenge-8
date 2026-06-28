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

/** Запись об одном вызове инструмента: на КАКОМ сервере, какой инструмент, аргументы и результат. */
data class ToolInvocation(val server: String, val tool: String, val arguments: String, val result: String)

/** Итог запроса: финальный текст агента + последовательность реальных кросс-серверных вызовов. */
data class AgentAnswer(val text: String, val toolCalls: List<ToolInvocation>)

/**
 * Агент-оркестратор на DeepSeek (function calling), подключённый к НЕСКОЛЬКИМ MCP-серверам
 * через [McpRouter].
 *
 * КЛЮЧЕВОЕ для Дня 20: модель видит плоский список инструментов со всех серверов и САМА
 * (а) выбирает нужный инструмент, (б) строит длинный флоу, передавая результат одного
 * инструмента в следующий. Маршрутизацию вызова на сервер-владельца берёт на себя роутер —
 * последовательность и выбор НЕ захардкожены, их определяет LLM по запросу пользователя.
 */
class OrchestratorAgent(
    private val router: McpRouter,
    private val apiKey: String,
    private val model: String = Config.model(),
) {
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30)).build()
    private val json = Json { ignoreUnknownKeys = true }

    private val toolsJson: JsonArray = buildJsonArray {
        router.allTools().forEach { tool ->
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

    private val systemPrompt = buildString {
        append("Ты — агент-оркестратор. Тебе доступны инструменты с НЕСКОЛЬКИХ MCP-серверов:\n")
        append("• research-mcp: wiki_search, wiki_extract — справки из Википедии;\n")
        append("• weather-mcp: geocode, get_weather — координаты и текущая погода;\n")
        append("• report-mcp: compose_report, word_count — сборка markdown-отчёта и статистика;\n")
        append("• storage-mcp: save_to_file, list_files, read_file — файловое хранилище.\n")
        append("Выполняй запрос пользователя, САМ выбирая нужные инструменты и передавая результат ")
        append("одного в следующий. Инструменты могут быть с РАЗНЫХ серверов в одном флоу. ")
        append("Типичный длинный флоу «досье по городу»: wiki_search → wiki_extract (справка) → ")
        append("get_weather (погода) → compose_report (собрать оба раздела в отчёт) → ")
        append("save_to_file (сохранить) → list_files (подтвердить). ")
        append("Не выдумывай содержимое — бери его строго из результатов инструментов. ")
        append("В конце кратко ответь по-русски и укажи путь к сохранённому файлу.")
    }

    /** Выполняет запрос пользователя, позволяя модели построить длинный кросс-серверный флоу. */
    fun run(userMessage: String, maxSteps: Int = 12): AgentAnswer {
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

                // Роутер сам доставит вызов на сервер-владельца инструмента.
                val result = runCatching { router.callTool(toolName, args) }
                    .getOrElse { McpToolResult("Ошибка вызова MCP: ${it.message}", isError = true) }

                invocations.add(
                    ToolInvocation(router.serverOf(toolName), toolName, compact(argsRaw), result.text),
                )
                messages.add(
                    buildJsonObject {
                        put("role", "tool")
                        put("tool_call_id", callId)
                        put("content", result.text)
                    },
                )
            }
        }
        return AgentAnswer("Не удалось завершить флоу за $maxSteps шагов.", invocations)
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

    /** Укорачивает длинные аргументы (например, content отчёта) для читаемого лога флоу. */
    private fun compact(argsRaw: String): String {
        val oneLine = argsRaw.replace(Regex("\\s+"), " ").trim()
        return if (oneLine.length <= 160) oneLine else oneLine.take(160) + "…}"
    }
}
