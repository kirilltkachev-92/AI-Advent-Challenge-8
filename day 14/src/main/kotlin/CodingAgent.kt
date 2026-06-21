/** Что произошло при запросе к автомату. */
sealed interface StageOutcome {
    data class Ran(
        val stage: TaskStage,
        val result: StageResult,
        val transition: TransitionResult.Moved?,
        /** Инварианты, которые код всё ещё нарушает после попыток исправления (предупреждение). */
        val violations: List<Violation> = emptyList(),
    ) : StageOutcome

    data class Blocked(val rejected: TransitionResult.Rejected) : StageOutcome

    /** Запрос конфликтует с инвариантом — задача не берётся, дано объяснение отказа. */
    data class Refused(val violations: List<Violation>, val explanation: String) : StageOutcome

    object NoActiveTask : StageOutcome
}

private data class StageRun(val result: StageResult, val violations: List<Violation>)

/**
 * Оркестратор кодинг-агента с инвариантами (день 14).
 *
 * Инварианты — жёсткие ограничения состояния — хранятся ОТДЕЛЬНО ([InvariantStore]), подмешиваются
 * в КАЖДОГО агента (явный учёт в рассуждениях) и проверяются детерминированно + LLM-контролёром
 * ([InvariantChecker]). Если запрос нарушает инвариант — задача не берётся (Refused) с объяснением.
 * Если сгенерированный код нарушает инвариант — этап выполнения переделывается с фидбэком, а если
 * нарушение остаётся, оно показывается как предупреждение (агент не выдаёт его за корректное).
 */
class CodingAgent(
    private val client: ChatClient,
    private val profiles: ProfileStore = ProfileStore(),
    private val tasks: TaskStore = TaskStore(),
    private val invariantStore: InvariantStore = InvariantStore(),
    private val pipeline: CodingPipeline = CodingPipeline(client),
    private val checker: InvariantChecker = InvariantChecker(client),
) {
    var profile: DevProfile = profiles.load() ?: DevProfile()
        private set

    val task = TaskStateMachine()
    private val invariants = InvariantSet(invariantStore.loadOrSeed())

    init {
        tasks.load()?.let { task.restore(it) }
    }

    val hasProfile: Boolean get() = profiles.exists()

    fun setProfile(p: DevProfile) {
        profile = p
        profiles.save(p)
    }

    // ---------- Управление автоматом ----------

    /** Новая задача: сначала проверяем запрос на конфликт с инвариантами, затем стартуем planning. */
    fun startTask(request: String): StageOutcome {
        val conflicts = checker.check(request, invariants, "запрос пользователя")
        if (conflicts.isNotEmpty()) {
            return StageOutcome.Refused(conflicts, explainRefusal(request, conflicts))
        }
        task.start(request)
        val run = runStage(TaskStage.PLANNING, feedback = "")
        return StageOutcome.Ran(TaskStage.PLANNING, run.result, transition = null, violations = run.violations)
    }

    fun advance(): StageOutcome = transitionAndRun(task.next())
    fun back(): StageOutcome = transitionAndRun(task.back())

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
        val run = runStage(task.stage, feedback)
        return StageOutcome.Ran(task.stage, run.result, transition = null, violations = run.violations)
    }

    private fun transitionAndRun(t: TransitionResult): StageOutcome = when (t) {
        is TransitionResult.Rejected -> StageOutcome.Blocked(t)
        is TransitionResult.Moved -> {
            val feedback = if (t.to == TaskStage.EXECUTION && t.from == TaskStage.VALIDATION) {
                task.artifact(TaskStage.VALIDATION)?.let { "Исправь код по итогам ревью:\n$it" } ?: ""
            } else {
                ""
            }
            val run = runStage(t.to, feedback)
            StageOutcome.Ran(t.to, run.result, transition = t, violations = run.violations)
        }
    }

    /**
     * Прогон одного этапа. На этапе execution сгенерированный код проверяется на инварианты:
     * при нарушении — до [MAX_FIX] повторов с явным фидбэком; оставшиеся нарушения возвращаются
     * как предупреждение.
     */
    private fun runStage(stage: TaskStage, feedback: String): StageRun {
        val req = task.request ?: ""
        val ctx = contextBlock()
        var result = produce(stage, req, ctx, feedback)
        var violations = emptyList<Violation>()

        if (stage == TaskStage.EXECUTION) {
            violations = checker.check(result.artifact, invariants, "код")
            var attempts = 0
            while (violations.isNotEmpty() && attempts < MAX_FIX) {
                attempts++
                val fixFeedback = fixFeedback(violations) +
                    if (feedback.isBlank()) "" else "\n\nТакже учти: $feedback"
                result = produce(stage, req, ctx, fixFeedback)
                violations = checker.check(result.artifact, invariants, "код")
            }
        }

        task.putArtifact(stage, result.artifact)
        persist()
        return StageRun(result, violations)
    }

    private fun produce(stage: TaskStage, req: String, ctx: String, feedback: String): StageResult =
        when (stage) {
            TaskStage.PLANNING -> pipeline.runPlanning(req, ctx, feedback)
            TaskStage.EXECUTION ->
                pipeline.runExecution(req, task.artifact(TaskStage.PLANNING) ?: "", ctx, feedback)
            TaskStage.VALIDATION ->
                pipeline.runValidation(
                    req,
                    task.artifact(TaskStage.PLANNING) ?: "",
                    task.artifact(TaskStage.EXECUTION) ?: "",
                    ctx,
                    feedback,
                )
            TaskStage.DONE -> pipeline.runDone(req, task.artifacts(), ctx)
        }

    /** Контекст для каждого агента: профиль + инварианты (явный учёт в рассуждениях). */
    private fun contextBlock(): String = buildString {
        append(profile.render())
        if (!invariants.isEmpty()) append("\n\n").append(invariants.render())
    }

    private fun fixFeedback(violations: List<Violation>): String = buildString {
        append("СТОП: код нарушает инварианты проекта — обязательно исправь, не нарушай их:\n")
        violations.forEach { append("• [${it.invariant.category}] ${it.invariant.rule} (${it.evidence})\n") }
    }.trimEnd()

    private fun explainRefusal(request: String, violations: List<Violation>): String {
        val vBlock = violations.joinToString("\n") {
            "• [${it.invariant.category}] ${it.invariant.rule} (нарушение: ${it.evidence}, источник: ${it.source})"
        }
        val messages = listOf(
            ChatMessage("system", REFUSAL_PROMPT),
            ChatMessage(
                "user",
                "Запрос пользователя: $request\n\nНарушенные инварианты:\n$vBlock",
            ),
        )
        val text = runCatching { client.chat(messages).content }.getOrNull()?.trim()
        return if (!text.isNullOrBlank()) text else
            "Не могу выполнить запрос — он нарушает инварианты проекта:\n$vBlock\n" +
                "Я работаю строго в их рамках. Предложите вариант без нарушения."
    }

    private fun persist() = tasks.save(task.snapshot())

    // ---------- Инварианты ----------

    fun invariants(): List<Invariant> = invariants.all()

    fun addInvariant(category: String, rule: String, forbid: List<String>): Invariant {
        val inv = Invariant(id = "user-${invariants.size + 1}", category = category, rule = rule, forbid = forbid)
        invariants.add(inv)
        invariantStore.save(invariants.all())
        return inv
    }

    fun removeInvariant(index: Int): Invariant? {
        val removed = invariants.removeAt(index)
        if (removed != null) invariantStore.save(invariants.all())
        return removed
    }

    fun clearInvariants() {
        invariants.clear()
        invariantStore.save(invariants.all())
    }

    /** Прямая проверка произвольного текста на инварианты (для команды /check). */
    fun checkText(text: String): List<Violation> = checker.check(text, invariants, "текст")

    fun invariantsPath(): String = invariantStore.path.toString()

    // ---------- Сервис ----------

    fun clearTask() {
        task.clear()
        tasks.delete()
    }

    /** Полный сброс: профиль, задача и инварианты — как при первом запуске (инварианты пересеются). */
    fun reset() {
        task.clear()
        tasks.delete()
        profiles.wipe()
        profile = DevProfile()
        invariantStore.wipe()
        invariants.replaceAll(invariantStore.loadOrSeed())
    }

    fun snapshotTask(): TaskSnapshot = task.snapshot()
    fun restoreTask(snapshot: TaskSnapshot) {
        task.restore(snapshot)
        persist()
    }

    fun statePath(): String = tasks.file.toString()

    private companion object {
        const val MAX_FIX = 2

        const val REFUSAL_PROMPT =
            "Ты кодинг-агент, который СТРОГО соблюдает инварианты проекта. Пользователь попросил то, " +
                "что их нарушает. Вежливо и по делу объясни, ПОЧЕМУ ты не можешь это сделать, со ссылкой " +
                "на конкретный нарушенный инвариант, и предложи допустимую альтернативу. Кратко, без воды, " +
                "на русском."
    }
}
