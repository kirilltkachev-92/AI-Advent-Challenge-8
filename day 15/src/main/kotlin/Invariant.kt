import kotlinx.serialization.Serializable

/**
 * Инвариант — жёсткое ограничение состояния, которое ассистент НЕ имеет права нарушать ни при
 * каких условиях. Хранится отдельно от диалога (см. [InvariantStore]) и подмешивается в каждого
 * агента. Примеры категорий: архитектура, принятые решения, стек, бизнес-правила.
 *
 * Проверка инварианта — двойная (по совету наставника): детерминированная по [forbid]-паттернам
 * (надёжно, в коде) и семантическая через LLM-контролёра (ловит то, что подстрокой не найти).
 */
@Serializable
data class Invariant(
    val id: String,
    val category: String,
    val rule: String,
    /** Запрещённые токены для детерминированной проверки (необязательно). */
    val forbid: List<String> = emptyList(),
)

/** Найденное нарушение инварианта: какой инвариант, чем подтверждается и кто нашёл. */
data class Violation(
    val invariant: Invariant,
    val evidence: String,
    val source: String, // "код" | "llm"
)

/**
 * Набор инвариантов: рендер для промпта и детерминированная проверка текста.
 */
class InvariantSet(initial: List<Invariant> = emptyList()) {
    private val items = initial.toMutableList()

    fun all(): List<Invariant> = items.toList()
    fun isEmpty(): Boolean = items.isEmpty()
    val size: Int get() = items.size

    fun add(inv: Invariant) {
        if (items.none { it.id == inv.id }) items += inv
    }

    fun removeAt(index: Int): Invariant? =
        if (index in items.indices) items.removeAt(index) else null

    fun clear() = items.clear()

    fun replaceAll(list: List<Invariant>) {
        items.clear()
        items += list
    }

    /** Детерминированная проверка: какие инварианты нарушает текст по их [forbid]-паттернам. */
    fun deterministicViolations(text: String): List<Violation> = buildList {
        for (inv in items) {
            for (pattern in inv.forbid) {
                val p = pattern.trim()
                if (p.isEmpty()) continue
                // Граница по не-буквам/цифрам (учитывая кириллицу), регистронезависимо:
                // «java» сматчит «Java 17», но не «javascript».
                val rx = Regex("(?i)(?<![\\p{L}\\p{N}])${Regex.escape(p)}(?![\\p{L}\\p{N}])")
                val m = rx.find(text)
                if (m != null) {
                    add(Violation(inv, "найдено «${m.value}»", "код"))
                    break // одного срабатывания на инвариант достаточно
                }
            }
        }
    }

    /** Блок для system prompt каждого агента. */
    fun render(): String = buildString {
        append("[Инварианты проекта — НЕЛЬЗЯ нарушать ни при каких условиях]\n")
        append("Учитывай их в рассуждениях. Если запрос или решение нарушает инвариант — НЕ выполняй, ")
        append("откажись и объясни, какой инвариант нарушен; предложи допустимую альтернативу.\n")
        items.forEach { inv ->
            append("• [${inv.category}] ${inv.rule}")
            if (inv.forbid.isNotEmpty()) append(" (запрещено: ${inv.forbid.joinToString(", ")})")
            append("\n")
        }
    }.trimEnd()
}
