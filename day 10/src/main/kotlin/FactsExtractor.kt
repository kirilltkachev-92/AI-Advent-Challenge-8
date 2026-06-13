import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

class FactsExtractor(
    private val client: ChatClient,
    private val systemPrompt: String = Config.FACTS_EXTRACTOR_PROMPT,
) {
    private val json = Json { ignoreUnknownKeys = true }

    var extractionCalls: Int = 0
        private set

    fun updateFacts(
        currentFacts: MutableMap<String, String>,
        userMessage: String,
        recentContext: List<ChatMessage>,
    ): Int {
        val existingJson = if (currentFacts.isEmpty()) {
            "{}"
        } else {
            currentFacts.entries.joinToString(prefix = "{", postfix = "}") { (k, v) ->
                "\"${k.escapeJson()}\":\"${v.escapeJson()}\""
            }
        }

        val contextText = recentContext
            .takeLast(4)
            .joinToString("\n") { "${it.role}: ${it.content}" }

        val prompt = buildString {
            appendLine("Текущие факты (JSON):")
            appendLine(existingJson)
            appendLine()
            if (contextText.isNotBlank()) {
                appendLine("Недавний контекст:")
                appendLine(contextText)
                appendLine()
            }
            appendLine("Новое сообщение пользователя:")
            appendLine(userMessage)
            appendLine()
            append("Верни обновлённый JSON со всеми фактами.")
        }

        val messages = listOf(
            ChatMessage(role = "system", content = systemPrompt),
            ChatMessage(role = "user", content = prompt),
        )

        extractionCalls++
        val result = client.chat(messages)
        val parsed = parseFactsJson(result.content)
        currentFacts.clear()
        currentFacts.putAll(parsed)
        return result.promptTokens + result.completionTokens
    }

    fun parseFactsJson(raw: String): Map<String, String> {
        val cleaned = raw.trim()
            .removePrefix("```json")
            .removePrefix("```")
            .removeSuffix("```")
            .trim()

        return runCatching {
            val obj = json.decodeFromString<JsonObject>(cleaned)
            obj.mapValues { (_, v) -> v.jsonPrimitive.content }
        }.getOrElse {
            parseKeyValueLines(cleaned)
        }
    }

    private fun parseKeyValueLines(text: String): Map<String, String> =
        text.lines()
            .mapNotNull { line ->
                val trimmed = line.trim().removePrefix("•").trim()
                val idx = trimmed.indexOf(':')
                if (idx <= 0) return@mapNotNull null
                val key = trimmed.substring(0, idx).trim().trim('"')
                val value = trimmed.substring(idx + 1).trim().trim('"', ',')
                if (key.isNotEmpty() && value.isNotEmpty()) key to value else null
            }
            .toMap()

    private fun String.escapeJson(): String =
        replace("\\", "\\\\").replace("\"", "\\\"")
}
