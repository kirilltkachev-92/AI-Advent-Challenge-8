import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * «Память задачи» (усиление Дня 25) — то, что должно пережить скользящее окно истории:
 *  - цель диалога;
 *  - что пользователь уже уточнил;
 *  - зафиксированные ограничения и термины.
 * Инжектится в каждый промпт агента и обновляется после каждого хода.
 */
@Serializable
data class TaskState(
    val goal: String = "",
    val clarified: List<String> = emptyList(),
    val constraints: List<String> = emptyList(),
    val terms: List<String> = emptyList(),
) {
    fun isEmpty(): Boolean = goal.isBlank() && clarified.isEmpty() && constraints.isEmpty() && terms.isEmpty()

    fun render(): String = buildString {
        appendLine("Цель диалога: ${goal.ifBlank { "(не зафиксирована)" }}")
        if (clarified.isNotEmpty()) appendLine("Уже уточнено: ${clarified.joinToString("; ")}")
        if (constraints.isNotEmpty()) appendLine("Ограничения: ${constraints.joinToString("; ")}")
        if (terms.isNotEmpty()) appendLine("Термины: ${terms.joinToString("; ")}")
    }.trim()
}

/** Обновляет память задачи после каждого хода диалога (отдельный вызов LLM). */
class TaskStateUpdater(private val llm: DeepSeekClient) {
    private val json = Json { ignoreUnknownKeys = true }

    private val system =
        "Ты ведёшь «память задачи» диалога с ассистентом по статье GPT-3. " +
            "Дано текущее состояние, новое сообщение пользователя и ответ ассистента. " +
            "Обнови состояние: goal — цель диалога (формулируется из слов пользователя, " +
            "меняй только если пользователь явно сменил цель); clarified — что пользователь уже уточнил; " +
            "constraints — зафиксированные ограничения/пожелания к ответам; terms — важные термины с их значением. " +
            "Пиши по-русски, кратко, без дублей, не выдумывай. Ответь строго JSON: " +
            "{\"goal\": \"...\", \"clarified\": [\"...\"], \"constraints\": [\"...\"], \"terms\": [\"...\"]}"

    fun update(state: TaskState, userMessage: String, assistantAnswer: String): TaskState = try {
        val user = "Текущее состояние:\n${state.render()}\n\n" +
            "Сообщение пользователя: $userMessage\n\nОтвет ассистента: ${assistantAnswer.take(600)}"
        json.decodeFromString(TaskState.serializer(), llm.chat(system, user, jsonMode = true))
    } catch (e: Exception) {
        state // память — не критичный путь: при сбое сохраняем прежнее состояние
    }
}
