/** Что произошло при запросе к автомату: этап отработал, переход отклонён, или задачи нет. */
sealed interface StageOutcome {
    data class Ran(
        val stage: TaskStage,
        val result: StageResult,
        val transition: TransitionResult.Moved?,
    ) : StageOutcome

    data class Blocked(val rejected: TransitionResult.Rejected) : StageOutcome
    object NoActiveTask : StageOutcome
}

/**
 * Оркестратор кодинг-агента. Держит профиль разработчика и состояние задачи (конечный автомат),
 * а саму работу на каждом этапе выполняет [CodingPipeline] — со своими агентами и роями
 * параллельных инстансов. Переходы между этапами детерминированы и проверяются в коде; артефакты
 * сохраняются на диск, поэтому работу можно прервать на любом этапе и продолжить позже.
 */
class CodingAgent(
    client: ChatClient,
    private val profiles: ProfileStore = ProfileStore(),
    private val tasks: TaskStore = TaskStore(),
    private val pipeline: CodingPipeline = CodingPipeline(client),
) {
    var profile: DevProfile = profiles.load() ?: DevProfile()
        private set

    val task = TaskStateMachine()

    init {
        tasks.load()?.let { task.restore(it) }
    }

    val hasProfile: Boolean get() = profiles.exists()

    fun setProfile(p: DevProfile) {
        profile = p
        profiles.save(p)
    }

    // ---------- Управление автоматом ----------

    /** Новая задача: автомат стартует на planning и сразу прогоняет этап планирования. */
    fun startTask(request: String): StageOutcome {
        task.start(request)
        val result = runStage(TaskStage.PLANNING, feedback = "")
        return StageOutcome.Ran(TaskStage.PLANNING, result, transition = null)
    }

    /** Принять текущий этап и перейти к следующему, прогнав его агентов. */
    fun advance(): StageOutcome = transitionAndRun(task.next())

    /** Вернуться на предыдущий этап и перегенерировать его артефакт. */
    fun back(): StageOutcome = transitionAndRun(task.back())

    /** Перейти на конкретный этап (валидируется); при успехе — прогнать его. */
    fun gotoStage(stageId: String): StageOutcome {
        val target = TaskStage.fromId(stageId)
            ?: return StageOutcome.Blocked(
                TransitionResult.Rejected("неизвестный этап «$stageId»", task.allowedNext),
            )
        return transitionAndRun(task.moveTo(target))
    }

    /** Замечания пользователя: перегенерировать ТЕКУЩИЙ этап с учётом фидбэка (без перехода). */
    fun refineCurrentStage(feedback: String): StageOutcome {
        if (task.isEmpty()) return StageOutcome.NoActiveTask
        val result = runStage(task.stage, feedback)
        return StageOutcome.Ran(task.stage, result, transition = null)
    }

    private fun transitionAndRun(t: TransitionResult): StageOutcome = when (t) {
        is TransitionResult.Rejected -> StageOutcome.Blocked(t)
        is TransitionResult.Moved -> {
            // Возврат validation → execution = цикл исправления: передаём вердикт ревью кодеру.
            val feedback = if (t.to == TaskStage.EXECUTION && t.from == TaskStage.VALIDATION) {
                task.artifact(TaskStage.VALIDATION)?.let { "Исправь код по итогам ревью:\n$it" } ?: ""
            } else {
                ""
            }
            val result = runStage(t.to, feedback)
            StageOutcome.Ran(t.to, result, transition = t)
        }
    }

    /** Прогон одного этапа соответствующими агентами; артефакт сохраняется и персистится. */
    private fun runStage(stage: TaskStage, feedback: String): StageResult {
        val req = task.request ?: ""
        val pb = profile.render()
        val result = when (stage) {
            TaskStage.PLANNING -> pipeline.runPlanning(req, pb, feedback)
            TaskStage.EXECUTION ->
                pipeline.runExecution(req, task.artifact(TaskStage.PLANNING) ?: "", pb, feedback)
            TaskStage.VALIDATION ->
                pipeline.runValidation(
                    req,
                    task.artifact(TaskStage.PLANNING) ?: "",
                    task.artifact(TaskStage.EXECUTION) ?: "",
                    pb,
                    feedback,
                )
            TaskStage.DONE -> pipeline.runDone(req, task.artifacts(), pb)
        }
        task.putArtifact(stage, result.artifact)
        persist()
        return result
    }

    private fun persist() = tasks.save(task.snapshot())

    // ---------- Сервис ----------

    /** Бросить текущую задачу (состояние стирается, профиль остаётся). */
    fun clearTask() {
        task.clear()
        tasks.delete()
    }

    /** Полный сброс: профиль и задача удаляются — как при первом запуске. */
    fun reset() {
        task.clear()
        tasks.delete()
        profiles.wipe()
        profile = DevProfile()
    }

    /** Снимок/восстановление состояния задачи (для /demo — вернуть как было). */
    fun snapshotTask(): TaskSnapshot = task.snapshot()
    fun restoreTask(snapshot: TaskSnapshot) {
        task.restore(snapshot)
        persist()
    }

    fun statePath(): String = tasks.file.toString()
    fun profilePath(): String = profiles.path.toString()
}
