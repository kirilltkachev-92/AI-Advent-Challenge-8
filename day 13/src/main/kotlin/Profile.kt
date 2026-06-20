import kotlinx.serialization.Serializable

/**
 * Профиль разработчика — персонализация кодинг-агента (наследие дня 12).
 * Подмешивается в КАЖДОГО агента пайплайна, чтобы исследование, план, код и ревью
 * соответствовали стеку и правилам пользователя.
 */
@Serializable
data class DevProfile(
    val language: String = "Kotlin",
    val stack: String = "",
    /** уровень: junior | middle | senior */
    val experience: String = "middle",
    /** подробность объяснений: кратко | сбалансированно | подробно */
    val verbosity: String = "сбалансированно",
    /** жёсткие ограничения проекта: что нельзя / что обязательно использовать */
    val constraints: List<String> = emptyList(),
) {
    fun isEmpty(): Boolean =
        stack.isBlank() && constraints.isEmpty() && language == "Kotlin" && experience == "middle"

    /** Короткая подпись для статус-строки. */
    fun shortLabel(): String =
        listOf(language, stack.ifBlank { "—" }, experience).joinToString(", ")

    /** Блок персонализации, который добавляется в system prompt каждого агента. */
    fun render(): String = buildString {
        append("[Профиль разработчика — учитывай в работе]\n")
        append("• Язык: $language\n")
        if (stack.isNotBlank()) append("• Стек/фреймворки: $stack\n")
        append("• Уровень: $experience\n")
        append("• Подробность: $verbosity\n")
        if (constraints.isNotEmpty()) {
            append("• Ограничения (соблюдать строго):\n")
            constraints.forEach { append("   - $it\n") }
        }
    }.trimEnd()
}
