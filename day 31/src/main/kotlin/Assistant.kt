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

/** Запись об одном вызове инструмента — чтобы показать, что ассистент реально ходил в MCP. */
data class ToolInvocation(val tool: String, val arguments: String, val result: String)

/** Итог одного /help: ответ модели, вызовы MCP и источники документации из RAG. */
data class HelpAnswer(
    val text: String,
    val toolCalls: List<ToolInvocation>,
    val sources: List<Hit>,
)

/**
 * Ассистент разработчика: DeepSeek (function calling, протокол вручную) +
 * два вида контекста проекта.
 *
 * На каждый вопрос:
 *   1) RAG — top-K фрагментов документации (README всех дней + day 31/docs)
 *      кладутся прямо в сообщение пользователя;
 *   2) MCP — список git-инструментов из tools/list отдаётся модели как функции;
 *      если модель просит tool_calls, зовём их через tools/call и возвращаем
 *      результат, пока не появится финальный текстовый ответ.
 */
class Assistant(
    private val mcp: McpClient,
    mcpTools: List<McpTool>,
    private val index: DocumentIndex,
    private val embedder: OllamaEmbedder,
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
        Ты — ассистент разработчика по репозиторию AI Advent Challenge #8
        (31 ежедневный Kotlin-проект про работу с LLM). Отвечай по-русски, кратко и конкретно.

        Тебе даны два источника правды:
        1. Фрагменты документации проекта (README дней и docs) — приходят вместе с вопросом.
           Отвечая по ним, ссылайся на файл-источник в скобках.
        2. Git-инструменты (MCP): текущая ветка, статус, список файлов, diff, лог.
           Про живое состояние репозитория (ветка, изменения, коммиты, какие файлы есть)
           НЕ отвечай по памяти — обязательно вызови подходящий инструмент.
           И наоборот: если ответ уже есть в фрагментах документации (устройство,
           архитектура, форматы, запуск) — отвечай сразу, git для этого не нужен.

        Если ответа нет ни в документации, ни в git — честно скажи, что в проекте этого нет.
    """.trimIndent()

    fun help(question: String, maxSteps: Int = 4): HelpAnswer {
        val hits = Search.topK(index, embedder.embedQuery(question), question, Config.topK())
        val userContent = buildString {
            appendLine("Фрагменты документации проекта (по релевантности):")
            appendLine(Search.renderFragments(index, hits))
            appendLine()
            appendLine("Вопрос: $question")
        }

        val messages = mutableListOf<JsonObject>(
            buildJsonObject { put("role", "system"); put("content", systemPrompt) },
            buildJsonObject { put("role", "user"); put("content", userContent) },
        )
        val invocations = mutableListOf<ToolInvocation>()

        repeat(maxSteps) { step ->
            // Последний шаг — без инструментов: модель обязана ответить текстом
            // по уже собранному контексту, а не зависнуть в вызовах.
            val lastStep = step == maxSteps - 1
            if (lastStep) {
                messages.add(
                    buildJsonObject {
                        put("role", "user")
                        put(
                            "content",
                            "Инструменты больше недоступны. Дай финальный ответ по фрагментам " +
                                "документации и уже полученным результатам инструментов.",
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
                return HelpAnswer(text, invocations, hits)
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
        return HelpAnswer("Не удалось завершить ответ за $maxSteps шагов.", invocations, hits)
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
