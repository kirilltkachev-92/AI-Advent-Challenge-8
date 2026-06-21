import kotlinx.serialization.Serializable

/**
 * Этапы задачи как состояния конечного автомата:
 * clarify → planning → execution → validation → done.
 * Каждый этап обслуживается своими агентами и порождает АРТЕФАКТ, который передаётся дальше
 * (требования → план → код → вердикт). Переходы разрешены только по таблице и проверяются в коде.
 */
enum class TaskStage(
    val id: String,
    val label: String,
    /** Что обслуживает этап и какой артефакт он порождает. */
    val produces: String,
) {
    CLARIFY("clarify", "Уточнение", "уточнённые требования"),
    PLANNING("planning", "Планирование", "план/ТЗ (совет + дебаты)"),
    EXECUTION("execution", "Выполнение", "код и тесты"),
    VALIDATION("validation", "Проверка", "вердикт ревью (PASS/FAIL)"),
    DONE("done", "Готово", "итоговая сборка");

    val nextStage: TaskStage? get() = when (this) {
        CLARIFY -> PLANNING
        PLANNING -> EXECUTION
        EXECUTION -> VALIDATION
        VALIDATION -> DONE
        DONE -> null
    }
    val prevStage: TaskStage? get() = when (this) {
        PLANNING -> CLARIFY
        EXECUTION -> PLANNING
        VALIDATION -> EXECUTION
        else -> null
    }

    companion object {
        val INITIAL = CLARIFY

        /** Разрешённые переходы — единственный источник истины, проверяется в коде. */
        private val transitions: Map<TaskStage, Set<TaskStage>> = mapOf(
            CLARIFY to setOf(PLANNING),
            PLANNING to setOf(EXECUTION, CLARIFY),
            EXECUTION to setOf(VALIDATION, PLANNING),
            VALIDATION to setOf(DONE, EXECUTION),
            DONE to emptySet(),
        )

        fun allowedFrom(stage: TaskStage): Set<TaskStage> = transitions[stage] ?: emptySet()

        fun fromId(value: String?): TaskStage? =
            entries.firstOrNull { it.id.equals(value?.trim(), ignoreCase = true) }
    }
}

/** Результат попытки перехода между этапами. */
sealed interface TransitionResult {
    data class Moved(val from: TaskStage, val to: TaskStage) : TransitionResult
    data class Rejected(val reason: String, val allowed: Set<TaskStage>) : TransitionResult
}

/**
 * Состояние задачи: запрос пользователя, текущий этап автомата и артефакты этапов.
 * Артефакт каждого этапа сохраняется — поэтому работу можно поставить на паузу на любом
 * этапе и продолжить без повторных объяснений (артефакты не пересчитываются).
 */
class TaskStateMachine {
    var request: String? = null
        private set
    var stage: TaskStage = TaskStage.INITIAL
        private set

    private val artifacts = linkedMapOf<TaskStage, String>()
    private val log = mutableListOf<String>()

    /** Что должен сделать пользователь сейчас (детерминированно из этапа). */
    val expectedAction: String get() = when (stage) {
        TaskStage.CLARIFY -> "Проверить требования; /next — принять и перейти к планированию"
        TaskStage.PLANNING -> "Проверить план; /next — к коду, /back — уточнить требования"
        TaskStage.EXECUTION -> "Проверить код; /next — на ревью, /back — переделать план"
        TaskStage.VALIDATION -> "Принять (/next — done) или вернуть на доработку (/back — execution)"
        TaskStage.DONE -> "Задача завершена; /task — начать новую"
    }

    fun start(request: String) {
        clear()
        this.request = request.trim().ifBlank { null }
        log += "Создана задача · этап: ${stage.label}"
    }

    fun putArtifact(stage: TaskStage, content: String) {
        artifacts[stage] = content.trim()
        // Перегенерация этапа делает последующие артефакты устаревшими — удаляем их,
        // чтобы нельзя было «перепрыгнуть» вперёд по устаревшим данным (например, после
        // повторного планирования снова нужно пройти execution и validation).
        TaskStage.entries.filter { it.ordinal > stage.ordinal }.forEach { artifacts.remove(it) }
    }

    fun artifact(stage: TaskStage): String? = artifacts[stage]
    fun artifacts(): Map<TaskStage, String> = artifacts.toMap()
    fun log(): List<String> = log.toList()
    val allowedNext: Set<TaskStage> get() = TaskStage.allowedFrom(stage)

    fun canMoveTo(target: TaskStage): Boolean = target in TaskStage.allowedFrom(stage)

    /** Валидируемый переход. Недопустимый переход не меняет состояние. */
    fun moveTo(target: TaskStage): TransitionResult {
        if (target == stage) {
            return TransitionResult.Rejected("уже на этапе «${stage.label}»", allowedNext)
        }
        if (!canMoveTo(target)) {
            return TransitionResult.Rejected(
                "переход «${stage.label}» → «${target.label}» запрещён — этапы нельзя перепрыгивать",
                allowedNext,
            )
        }
        // Предусловие этапа: его нельзя начать без артефакта предыдущего этапа
        // («нельзя реализацию без утверждённого плана», «нельзя финал без валидации»).
        val required = target.prevStage
        if (required != null && artifacts[required] == null) {
            return TransitionResult.Rejected(
                "нельзя в «${target.label}» — сначала нужен результат этапа «${required.label}»",
                allowedNext,
            )
        }
        val from = stage
        stage = target
        log += "${from.label} → ${target.label}"
        return TransitionResult.Moved(from, target)
    }

    fun next(): TransitionResult =
        stage.nextStage?.let { moveTo(it) }
            ?: TransitionResult.Rejected("из «${stage.label}» нет следующего этапа", allowedNext)

    fun back(): TransitionResult =
        stage.prevStage?.let { moveTo(it) }
            ?: TransitionResult.Rejected("из «${stage.label}» нельзя вернуться назад", allowedNext)

    fun isEmpty(): Boolean = request == null && artifacts.isEmpty() && stage == TaskStage.INITIAL

    fun clear() {
        request = null
        stage = TaskStage.INITIAL
        artifacts.clear()
        log.clear()
    }

    fun snapshot(): TaskSnapshot =
        TaskSnapshot(
            request = request,
            stage = stage.id,
            artifacts = artifacts.mapKeys { it.key.id },
            log = log.toList(),
        )

    fun restore(snapshot: TaskSnapshot) {
        clear()
        request = snapshot.request
        stage = TaskStage.fromId(snapshot.stage) ?: TaskStage.INITIAL
        snapshot.artifacts.forEach { (k, v) -> TaskStage.fromId(k)?.let { artifacts[it] = v } }
        log += snapshot.log
    }
}

@Serializable
data class TaskSnapshot(
    val request: String? = null,
    val stage: String = TaskStage.INITIAL.id,
    val artifacts: Map<String, String> = emptyMap(),
    val log: List<String> = emptyList(),
)
