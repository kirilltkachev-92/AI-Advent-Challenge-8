import kotlinx.serialization.Serializable

/**
 * Этапы задачи как состояния конечного автомата: planning → execution → validation → done.
 * Каждый этап обслуживается своими агентами и порождает АРТЕФАКТ, который передаётся дальше
 * (план → код → вердикт). Переходы разрешены только по таблице и проверяются в коде.
 */
enum class TaskStage(
    val id: String,
    val label: String,
    /** Что обслуживает этап и какой артефакт он порождает. */
    val produces: String,
) {
    PLANNING("planning", "Планирование", "план/ТЗ (исследование + синтез)"),
    EXECUTION("execution", "Выполнение", "код и тесты"),
    VALIDATION("validation", "Проверка", "вердикт ревью (PASS/FAIL)"),
    DONE("done", "Готово", "итоговая сборка");

    val nextStage: TaskStage? get() = when (this) {
        PLANNING -> EXECUTION
        EXECUTION -> VALIDATION
        VALIDATION -> DONE
        DONE -> null
    }
    val prevStage: TaskStage? get() = when (this) {
        EXECUTION -> PLANNING
        VALIDATION -> EXECUTION
        else -> null
    }

    companion object {
        val INITIAL = PLANNING

        /** Разрешённые переходы — единственный источник истины, проверяется в коде. */
        private val transitions: Map<TaskStage, Set<TaskStage>> = mapOf(
            PLANNING to setOf(EXECUTION),
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
        TaskStage.PLANNING -> "Проверить план; /next — принять и перейти к коду, /back недоступен"
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
                "переход «${stage.label}» → «${target.label}» запрещён",
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
