/**
 * Собирает финальный запрос к LLM из выбранных слоёв памяти.
 * Какие слои включены — то и влияет на ответ. Это и есть «явный выбор, что идёт в промпт».
 */
object PromptBuilder {
    fun build(
        userText: String,
        enabledLayers: Set<MemoryLayer>,
        longTerm: LongTermMemory,
        working: WorkingMemory,
        shortTerm: ShortTermMemory,
    ): List<ChatMessage> = buildList {
        add(ChatMessage(role = "system", content = Config.SYSTEM_PROMPT))

        if (MemoryLayer.LONG_TERM in enabledLayers && !longTerm.isEmpty()) {
            add(ChatMessage(role = "system", content = renderLongTerm(longTerm)))
        }
        if (MemoryLayer.WORKING in enabledLayers && !working.isEmpty()) {
            add(ChatMessage(role = "system", content = renderWorking(working)))
        }
        if (MemoryLayer.SHORT_TERM in enabledLayers) {
            addAll(shortTerm.window())
        }

        add(ChatMessage(role = "user", content = userText))
    }

    fun renderLongTerm(m: LongTermMemory): String = buildString {
        append("[Долговременная память — профиль и знания]\n")
        if (m.profile().isNotEmpty()) {
            append("Профиль:\n")
            m.profile().forEach { (k, v) -> append("• $k: $v\n") }
        }
        if (m.constraints().isNotEmpty()) {
            append("Ограничения (соблюдать строго):\n")
            m.constraints().forEach { (k, v) -> append("• $k: $v\n") }
        }
        if (m.decisions().isNotEmpty()) {
            append("Принятые решения:\n")
            m.decisions().forEach { append("• $it\n") }
        }
        if (m.knowledge().isNotEmpty()) {
            append("Знания:\n")
            m.knowledge().forEach { append("• $it\n") }
        }
    }.trimEnd()

    fun renderWorking(m: WorkingMemory): String = buildString {
        append("[Рабочая память — текущая задача]\n")
        append("Задача: ${m.taskTitle ?: "(не задана)"}\n")
        append("Стадия: ${m.stage.label}\n")
        if (m.requirements().isNotEmpty()) {
            append("Требования:\n")
            m.requirements().forEach { append("• $it\n") }
        }
        if (m.notes().isNotEmpty()) {
            append("Заметки:\n")
            m.notes().forEach { append("• $it\n") }
        }
    }.trimEnd()

    /** Человекочитаемое превью того, что реально уходит в API. */
    fun preview(messages: List<ChatMessage>): String =
        messages.joinToString("\n\n") { "[${it.role}]\n${it.content}" }
}
