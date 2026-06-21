import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
private data class GuardViolation(val id: String = "", val why: String = "")

@Serializable
private data class GuardResult(val violations: List<GuardViolation> = emptyList())

/**
 * Контролёр инвариантов. По совету наставника проверка ДВОЙНАЯ и способы друг друга не
 * взаимоисключают:
 *   1) детерминированно в коде — по forbid-паттернам (надёжно, без ложной «доброты» модели);
 *   2) семантически через LLM-контролёра — ловит нарушения, которые подстрокой не найти.
 * Итог — объединение находок (дедуп по инварианту).
 */
class InvariantChecker(private val client: ChatClient) {
    private val json = Json { ignoreUnknownKeys = true }

    fun check(text: String, invariants: InvariantSet, kind: String): List<Violation> {
        if (invariants.isEmpty() || text.isBlank()) return emptyList()
        val deterministic = invariants.deterministicViolations(text)
        val semantic = llmViolations(text, invariants, kind)
        // Дедуп по id инварианта: детерминированные находки в приоритете.
        val byId = linkedMapOf<String, Violation>()
        (deterministic + semantic).forEach { v -> byId.putIfAbsent(v.invariant.id, v) }
        return byId.values.toList()
    }

    private fun llmViolations(text: String, invariants: InvariantSet, kind: String): List<Violation> {
        val list = invariants.all()
        val invBlock = list.joinToString("\n") { "- ${it.id}: [${it.category}] ${it.rule}" }
        val messages = listOf(
            ChatMessage("system", GUARD_PROMPT),
            ChatMessage(
                "user",
                "Инварианты:\n$invBlock\n\nПроверяемый текст ($kind):\n$text",
            ),
        )
        val raw = runCatching { client.chat(messages).content }.getOrElse { return emptyList() }
        val parsed = parse(raw)
        return parsed.violations.mapNotNull { gv ->
            list.firstOrNull { it.id == gv.id }?.let { inv ->
                Violation(inv, gv.why.ifBlank { "нарушение по мнению контролёра" }, "llm")
            }
        }
    }

    private fun parse(raw: String): GuardResult {
        var t = raw.trim()
        if (t.startsWith("```")) {
            t = t.removePrefix("```json").removePrefix("```").trim()
            val fence = t.lastIndexOf("```")
            if (fence >= 0) t = t.substring(0, fence).trim()
        }
        val start = t.indexOf('{')
        val end = t.lastIndexOf('}')
        if (start < 0 || end <= start) return GuardResult()
        return runCatching { json.decodeFromString<GuardResult>(t.substring(start, end + 1)) }
            .getOrElse { GuardResult() }
    }

    private companion object {
        const val GUARD_PROMPT =
            "Ты — строгий контролёр инвариантов проекта. На вход: список инвариантов (id + правило) и " +
                "проверяемый текст (запрос пользователя или код). Найди ТОЛЬКО те инварианты, которые " +
                "текст ЯВНО нарушает или требует нарушить. Не придумывай нарушений и не придирайся: " +
                "если сомневаешься — не нарушение. Ответь СТРОГО JSON без markdown в формате " +
                "{\"violations\":[{\"id\":\"<id инварианта>\",\"why\":\"кратко чем нарушает\"}]}. " +
                "Если нарушений нет — {\"violations\":[]}."
    }
}
