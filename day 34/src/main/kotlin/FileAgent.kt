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

/** Запись об одном вызове инструмента — след реальной работы с файлами. */
data class ToolInvocation(val tool: String, val arguments: String, val result: String)

/** Итог одной задачи: отчёт ассистента и все файловые операции по пути к нему. */
data class TaskResult(
    val text: String,
    val toolCalls: List<ToolInvocation>,
)

/**
 * Ассистент по файлам проекта: DeepSeek (function calling, протокол вручную) +
 * файловые MCP-инструменты. Задача ставится на уровне цели («обнови README»),
 * а какие файлы открыть, где искать и что писать — ассистент решает сам:
 * промпт требует начинать с list_files/search_files и не спрашивать
 * разрешения на очевидные шаги.
 */
class FileAgent(
    private val mcp: McpClient,
    mcpTools: List<McpTool>,
    private val apiKey: String,
    private val model: String = Config.deepSeekModel(),
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

    private val systemPrompt = """
        Ты — ассистент разработчика, который РАБОТАЕТ С ФАЙЛАМИ проекта через
        инструменты, а не просто отвечает текстом. Проект — «Инфопанель»
        (маленькая Kotlin-утилита), доступ к нему только через инструменты.

        Правила работы:
        1. Задача ставится на уровне цели. Сам реши, какие файлы нужны:
           начни с list_files, при поиске по содержимому используй search_files,
           читай файлы read_file — НИКОГДА не отвечай о содержимом по догадке.
        2. Изменения вноси через write_file (полный новый текст файла).
           Меняй только то, что требует задача; стиль и язык файлов сохраняй.
        3. Не задавай уточняющих вопросов и не проси разрешения — выполни
           задачу целиком и отчитайся.
        4. Финальный ответ — краткий отчёт по-русски: что нашёл, какие файлы
           изменил/создал и почему. Ссылайся на файлы и строки (файл:строка).

        Если задача не про файлы этого проекта — скажи об этом прямо.
    """.trimIndent()

    fun run(goal: String, maxSteps: Int = 12): TaskResult {
        val messages = mutableListOf<JsonObject>(
            buildJsonObject { put("role", "system"); put("content", systemPrompt) },
            buildJsonObject { put("role", "user"); put("content", "Задача: $goal") },
        )
        val invocations = mutableListOf<ToolInvocation>()

        repeat(maxSteps) { step ->
            // Последний шаг — без инструментов: модель обязана отчитаться текстом.
            val lastStep = step == maxSteps - 1
            if (lastStep) {
                messages.add(
                    buildJsonObject {
                        put("role", "user")
                        put(
                            "content",
                            "Инструменты больше недоступны. Дай финальный отчёт по уже сделанному.",
                        )
                    },
                )
            }
            val assistant = chat(messages, allowTools = !lastStep)
            messages.add(assistant)

            val toolCalls = assistant["tool_calls"]?.jsonArray
            if (toolCalls.isNullOrEmpty()) {
                val text = assistant["content"]?.jsonPrimitive?.content.orEmpty().trim()
                    .substringBefore("<｜") // страховка от протечки служебной разметки в текст
                    .trim()
                return TaskResult(text, invocations)
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
        return TaskResult("Не удалось завершить задачу за $maxSteps шагов.", invocations)
    }

    private fun chat(messages: List<JsonObject>, allowTools: Boolean = true): JsonObject {
        val body = buildJsonObject {
            put("model", model)
            put("messages", JsonArray(messages))
            put("tools", toolsJson)
            put("tool_choice", if (allowTools) "auto" else "none")
            put("temperature", 0.2)
            put("stream", false)
        }
        val request = HttpRequest.newBuilder()
            .uri(URI.create("${Config.DEEPSEEK_API_BASE}/chat/completions"))
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
