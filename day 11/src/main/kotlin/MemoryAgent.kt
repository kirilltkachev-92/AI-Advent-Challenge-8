data class AppliedWrite(
    val layer: MemoryLayer,
    val kind: String,
    val key: String,
    val value: String,
    val accepted: Boolean,
)

sealed interface TurnOutcome {
    data class Ok(
        val userText: String,
        val assistant: String,
        val applied: List<AppliedWrite>,
        val enabledLayers: Set<MemoryLayer>,
        val promptPreview: String,
        val mainTokens: Int,
        val routerTokens: Int,
        val costUsd: Double,
        val latencyMs: Long,
    ) : TurnOutcome

    data class Fail(val userText: String, val message: String) : TurnOutcome
}

/**
 * Stateful-ассистент с явной моделью памяти из трёх слоёв.
 * Поток одной реплики: роутер решает, что и куда сохранить → применяем записи в слои →
 * собираем промпт из включённых слоёв → запрос в LLM → диалог в краткосрочную память →
 * сохраняем каждый слой в свой файл.
 */
class MemoryAgent(
    private val client: ChatClient,
    private val store: MemoryStore = MemoryStore(),
    private val router: MemoryRouter = MemoryRouter(client),
    windowSize: Int = Config.windowSize(),
) {
    val shortTerm = ShortTermMemory(windowSize)
    val working = WorkingMemory()
    val longTerm = LongTermMemory()

    val enabledLayers: MutableSet<MemoryLayer> = MemoryLayer.entries.toMutableSet()

    init {
        store.loadLongTerm()?.let { longTerm.restore(it) }
        store.loadWorking()?.let { working.restore(it) }
        store.loadShortTerm()?.let { shortTerm.restore(it) }
    }

    fun processUserMessage(userText: String): TurnOutcome {
        val trimmed = userText.trim()
        if (trimmed.isEmpty()) return TurnOutcome.Fail(userText, "Сообщение не может быть пустым")

        // 1. Явная маршрутизация: что и в какой слой сохранить.
        val routed = router.route(trimmed, shortTerm.all())
        val applied = applyWrites(routed.writes)

        // 2. Сборка промпта из включённых слоёв.
        val messages = PromptBuilder.build(trimmed, enabledLayers, longTerm, working, shortTerm)
        val preview = PromptBuilder.preview(messages)

        // 3. Запрос в LLM.
        val result = runCatching { client.chat(messages) }
            .getOrElse { return TurnOutcome.Fail(trimmed, it.message ?: it.toString()) }

        // 4. Диалог уходит в краткосрочную память.
        shortTerm.appendUser(trimmed)
        shortTerm.appendAssistant(result.content)

        // 5. Каждый слой сохраняется в свой файл.
        persistAll()

        return TurnOutcome.Ok(
            userText = trimmed,
            assistant = result.content,
            applied = applied,
            enabledLayers = enabledLayers.toSet(),
            promptPreview = preview,
            mainTokens = result.totalTokens,
            routerTokens = routed.routerTokens,
            costUsd = result.estimatedCostUsd,
            latencyMs = result.latencyMs,
        )
    }

    private fun applyWrites(writes: List<MemoryWrite>): List<AppliedWrite> = writes.map { w ->
        val layer = MemoryLayer.entries.firstOrNull { it.id == w.layer.trim().lowercase() }
        val kind = w.kind.trim().lowercase()
        val accepted = when (layer) {
            MemoryLayer.LONG_TERM -> applyLongTerm(kind, w.key, w.value)
            MemoryLayer.WORKING -> applyWorking(kind, w.value)
            else -> false
        }
        AppliedWrite(
            layer = layer ?: MemoryLayer.SHORT_TERM,
            kind = w.kind,
            key = w.key,
            value = w.value,
            accepted = accepted,
        )
    }

    private fun applyLongTerm(kind: String, key: String, value: String): Boolean = when (kind) {
        "profile" -> { longTerm.putProfile(key, value); true }
        "constraint" -> { longTerm.putConstraint(key, value); true }
        "knowledge" -> { longTerm.addKnowledge(value); true }
        "decision" -> { longTerm.addDecision(value); true }
        else -> false
    }

    private fun applyWorking(kind: String, value: String): Boolean = when (kind) {
        "task" -> { working.setTask(value); true }
        "requirement" -> { working.addRequirement(value); true }
        "note" -> { working.addNote(value); true }
        "stage" -> TaskStage.fromId(value)?.let { working.setStage(it); true } ?: false
        else -> false
    }

    private fun persistAll() {
        store.saveLongTerm(longTerm.snapshot())
        store.saveWorking(working.snapshot())
        store.saveShortTerm(shortTerm.snapshot())
    }

    /** Стартовое интервью: пишем выбранные предпочтения прямо в профиль (минуя роутер) и сохраняем. */
    fun rememberProfile(entries: Map<String, String>) {
        entries.forEach { (key, value) -> longTerm.putProfile(key, value) }
        store.saveLongTerm(longTerm.snapshot())
    }

    /** Новая сессия: чистим краткосрочную и рабочую память, профиль (долговременную) сохраняем. */
    fun newSession() {
        shortTerm.clear()
        working.clear()
        store.deleteShortTerm()
        store.deleteWorking()
    }

    /** Полный сброс всех слоёв, включая профиль. */
    fun forgetEverything() {
        shortTerm.clear()
        working.clear()
        longTerm.clear()
        store.deleteShortTerm()
        store.deleteWorking()
        store.saveLongTerm(longTerm.snapshot())
    }

    fun storePaths(): Triple<String, String, String> =
        Triple(
            store.shortTermFile.toString(),
            store.workingFile.toString(),
            store.longTermFile.toString(),
        )
}
