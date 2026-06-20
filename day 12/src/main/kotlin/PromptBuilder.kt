/**
 * Собирает финальный запрос к LLM.
 *
 * Порядок блоков system-промпта:
 *   1. базовый системный промпт (роль ассистента);
 *   2. ПРОФИЛЬ пользователя — персонализация, подмешивается в каждый запрос;
 *   3. включённые слои памяти (долговременная → рабочая → краткосрочная);
 *   4. сам запрос пользователя.
 *
 * Профиль идёт сразу после базового промпта и раньше памяти: это «кто пользователь и
 * как с ним говорить», и именно он задаёт тон всему ответу.
 */
object PromptBuilder {
    fun build(
        userText: String,
        profile: UserProfile?,
        personalizationOn: Boolean,
        enabledLayers: Set<MemoryLayer>,
        longTerm: LongTermMemory,
        working: WorkingMemory,
        shortTerm: ShortTermMemory,
    ): List<ChatMessage> = buildList {
        add(ChatMessage(role = "system", content = Config.SYSTEM_PROMPT))

        if (personalizationOn && profile != null) {
            add(ChatMessage(role = "system", content = renderProfile(profile)))
        }

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

    /** Профиль пользователя в человекочитаемом виде — то, что ассистент учитывает автоматически. */
    fun renderProfile(p: UserProfile): String = buildString {
        append("[Персонализация — профиль пользователя «${p.name}»]\n")
        append("Учитывай профиль в каждом ответе автоматически, не переспрашивая то, что уже известно.\n")

        append("\nКонтекст:\n")
        if (p.context.who.isNotBlank()) append("• Кто: ${p.context.who}\n")
        if (p.context.goal.isNotBlank()) append("• Цель: ${p.context.goal}\n")
        if (p.context.level.isNotBlank()) append("• Уровень: ${p.context.level}\n")
        if (p.context.equipment.isNotBlank()) append("• Инвентарь/окружение: ${p.context.equipment}\n")

        append("\nСтиль общения:\n")
        append("• Подробность: ${p.style.verbosity}\n")
        append("• Тон: ${p.style.tone}\n")
        append("• Терминология: ${p.style.jargon}\n")
        append("• Эмодзи: ${if (p.style.emoji) "уместны" else "не использовать"}\n")

        append("\nФормат ответа:\n")
        append("• Структура: ${p.format.structure}\n")
        append("• Длина: ${p.format.length}\n")
        append("• Конкретные цифры (подходы×повторы, %, RPE, время): ${if (p.format.numbers) "да" else "нет"}\n")

        if (p.constraints.isNotEmpty()) {
            append("\nОграничения (соблюдать строго):\n")
            p.constraints.forEach { append("• $it\n") }
        }
    }.trimEnd()

    fun renderLongTerm(m: LongTermMemory): String = buildString {
        append("[Долговременная память — выучено в диалоге]\n")
        if (m.profile().isNotEmpty()) {
            append("Факты о пользователе:\n")
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
