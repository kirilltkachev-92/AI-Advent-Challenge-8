import kotlinx.serialization.Serializable

/** Три слоя памяти ассистента. Каждый слой хранится и наполняется отдельно. */
enum class MemoryLayer(val id: String, val title: String, val subtitle: String) {
    SHORT_TERM(
        id = "short_term",
        title = "Краткосрочная",
        subtitle = "Текущий диалог — последние реплики, эфемерно",
    ),
    WORKING(
        id = "working",
        title = "Рабочая",
        subtitle = "Данные текущей задачи: название, стадия, требования",
    ),
    LONG_TERM(
        id = "long_term",
        title = "Долговременная",
        subtitle = "Профиль, ограничения, решения, знания",
    ),
}

/** Стадии задачи (рабочая память). */
enum class TaskStage(val id: String, val label: String) {
    CLARIFY("clarify", "Знакомство и цели"),
    PLANNING("planning", "Составление программы"),
    EXECUTION("execution", "Тренировки"),
    VALIDATION("validation", "Разбор и корректировка"),
    DONE("done", "Цикл завершён");

    companion object {
        fun fromId(value: String?): TaskStage? =
            entries.firstOrNull { it.id.equals(value?.trim(), ignoreCase = true) }
    }
}

/** Одна запись от роутера: что сохранить и в какой слой. */
@Serializable
data class MemoryWrite(
    val layer: String,
    val kind: String,
    val key: String = "",
    val value: String,
)

@Serializable
data class RouterDecision(
    val writes: List<MemoryWrite> = emptyList(),
)

// ---------- Краткосрочная память ----------

/** Лента текущего диалога. В API уходит только последнее окно. */
class ShortTermMemory(private val windowSize: Int) {
    private val dialog = mutableListOf<ChatMessage>()

    val size: Int get() = dialog.size
    fun all(): List<ChatMessage> = dialog.toList()

    fun appendUser(text: String) {
        dialog += ChatMessage(role = "user", content = text)
    }

    fun appendAssistant(text: String) {
        dialog += ChatMessage(role = "assistant", content = text)
    }

    /** Последние N реплик user+assistant — то, что реально видит модель. */
    fun window(): List<ChatMessage> =
        dialog.filter { it.role == "user" || it.role == "assistant" }.takeLast(windowSize)

    fun clear() = dialog.clear()

    fun snapshot(): ShortTermSnapshot =
        ShortTermSnapshot(windowSize = windowSize, messages = dialog.toList())

    fun restore(snapshot: ShortTermSnapshot) {
        dialog.clear()
        dialog += snapshot.messages
    }
}

@Serializable
data class ShortTermSnapshot(
    val windowSize: Int,
    val messages: List<ChatMessage> = emptyList(),
)

// ---------- Рабочая память ----------

class WorkingMemory {
    var taskTitle: String? = null
        private set
    var stage: TaskStage = TaskStage.CLARIFY
        private set
    private val requirements = mutableListOf<String>()
    private val notes = mutableListOf<String>()

    fun setTask(title: String) {
        taskTitle = title.trim().ifBlank { null }
    }

    fun setStage(stage: TaskStage) {
        this.stage = stage
    }

    fun addRequirement(text: String) {
        val t = text.trim()
        if (t.isNotEmpty() && requirements.none { it.equals(t, ignoreCase = true) }) requirements += t
    }

    fun addNote(text: String) {
        val t = text.trim()
        if (t.isNotEmpty()) notes += t
    }

    fun requirements(): List<String> = requirements.toList()
    fun notes(): List<String> = notes.toList()
    fun isEmpty(): Boolean = taskTitle == null && requirements.isEmpty() && notes.isEmpty()

    fun clear() {
        taskTitle = null
        stage = TaskStage.CLARIFY
        requirements.clear()
        notes.clear()
    }

    fun snapshot(): WorkingSnapshot =
        WorkingSnapshot(
            taskTitle = taskTitle,
            stage = stage.id,
            requirements = requirements.toList(),
            notes = notes.toList(),
        )

    fun restore(snapshot: WorkingSnapshot) {
        clear()
        taskTitle = snapshot.taskTitle
        stage = TaskStage.fromId(snapshot.stage) ?: TaskStage.CLARIFY
        requirements += snapshot.requirements
        notes += snapshot.notes
    }
}

@Serializable
data class WorkingSnapshot(
    val taskTitle: String? = null,
    val stage: String = TaskStage.CLARIFY.id,
    val requirements: List<String> = emptyList(),
    val notes: List<String> = emptyList(),
)

// ---------- Долговременная память ----------

class LongTermMemory {
    private val profile = linkedMapOf<String, String>()
    private val constraints = linkedMapOf<String, String>()
    private val knowledge = mutableListOf<String>()
    private val decisions = mutableListOf<String>()

    fun putProfile(key: String, value: String) = put(profile, key, value)
    fun putConstraint(key: String, value: String) = put(constraints, key, value)

    fun addKnowledge(text: String) {
        val t = text.trim()
        if (t.isNotEmpty() && knowledge.none { it.equals(t, ignoreCase = true) }) knowledge += t
    }

    fun addDecision(text: String) {
        val t = text.trim()
        if (t.isNotEmpty() && decisions.none { it.equals(t, ignoreCase = true) }) decisions += t
    }

    private fun put(map: MutableMap<String, String>, key: String, value: String) {
        val k = key.trim().ifBlank { "—" }
        val v = value.trim()
        if (v.isNotEmpty()) map[k] = v
    }

    fun profile(): Map<String, String> = profile.toMap()
    fun constraints(): Map<String, String> = constraints.toMap()
    fun knowledge(): List<String> = knowledge.toList()
    fun decisions(): List<String> = decisions.toList()

    fun isEmpty(): Boolean =
        profile.isEmpty() && constraints.isEmpty() && knowledge.isEmpty() && decisions.isEmpty()

    fun clear() {
        profile.clear()
        constraints.clear()
        knowledge.clear()
        decisions.clear()
    }

    fun snapshot(): LongTermSnapshot =
        LongTermSnapshot(
            profile = profile.toMap(),
            constraints = constraints.toMap(),
            knowledge = knowledge.toList(),
            decisions = decisions.toList(),
        )

    fun restore(snapshot: LongTermSnapshot) {
        clear()
        profile.putAll(snapshot.profile)
        constraints.putAll(snapshot.constraints)
        knowledge += snapshot.knowledge
        decisions += snapshot.decisions
    }
}

@Serializable
data class LongTermSnapshot(
    val profile: Map<String, String> = emptyMap(),
    val constraints: Map<String, String> = emptyMap(),
    val knowledge: List<String> = emptyList(),
    val decisions: List<String> = emptyList(),
)
