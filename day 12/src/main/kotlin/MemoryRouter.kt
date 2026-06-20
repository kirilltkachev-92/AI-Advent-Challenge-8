import kotlinx.serialization.json.Json

data class RouterResult(
    val writes: List<MemoryWrite>,
    val routerTokens: Int,
)

/**
 * Явный выбор «что и куда сохранять». После каждой реплики пользователя роутер
 * (отдельный вызов LLM) решает, какие стабильные сведения вынести в рабочую или
 * долговременную память. Краткосрочный слой роутер не трогает — туда попадает
 * сам диалог автоматически.
 */
class MemoryRouter(private val client: ChatClient) {
    private val json = Json { ignoreUnknownKeys = true }

    var routerCalls: Int = 0
        private set

    fun route(userMessage: String, recentContext: List<ChatMessage>): RouterResult {
        val contextBlock = recentContext.takeLast(6).joinToString("\n") { "${it.role}: ${it.content}" }
        val messages = listOf(
            ChatMessage(role = "system", content = Config.ROUTER_PROMPT),
            ChatMessage(
                role = "user",
                content = buildString {
                    if (contextBlock.isNotBlank()) {
                        append("Контекст диалога:\n").append(contextBlock).append("\n\n")
                    }
                    append("Последнее сообщение пользователя:\n").append(userMessage)
                },
            ),
        )

        val decision = runCatching {
            val result = client.chat(messages)
            routerCalls++
            val parsed = parse(result.content)
            RouterResult(writes = parsed.writes, routerTokens = result.totalTokens)
        }
        // Роутер не должен ломать основной поток: при сбое просто ничего не маршрутизируем.
        return decision.getOrElse { RouterResult(writes = emptyList(), routerTokens = 0) }
    }

    private fun parse(raw: String): RouterDecision {
        val cleaned = stripFences(raw)
        val start = cleaned.indexOf('{')
        val end = cleaned.lastIndexOf('}')
        if (start < 0 || end <= start) return RouterDecision()
        val jsonText = cleaned.substring(start, end + 1)
        return runCatching { json.decodeFromString<RouterDecision>(jsonText) }.getOrElse { RouterDecision() }
    }

    private fun stripFences(text: String): String {
        var t = text.trim()
        if (t.startsWith("```")) {
            t = t.removePrefix("```json").removePrefix("```").trim()
            val fence = t.lastIndexOf("```")
            if (fence >= 0) t = t.substring(0, fence).trim()
        }
        return t
    }
}
