import java.nio.file.Path

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
        val profileName: String,
        val profileLabel: String,
        val personalizationOn: Boolean,
        val promptPreview: String,
        val mainTokens: Int,
        val routerTokens: Int,
        val costUsd: Double,
        val latencyMs: Long,
    ) : TurnOutcome

    data class Fail(val userText: String, val message: String) : TurnOutcome
}

/**
 * Stateful-ассистент с персонализацией поверх модели памяти.
 *
 * Над тремя слоями памяти (краткосрочная/рабочая/долговременная) стоит ПРОФИЛЬ
 * пользователя. Профиль выбирается явно и подмешивается в каждый запрос. У каждого
 * профиля своя память: переключение профиля меняет и личность, и историю.
 *
 * Поток одной реплики: роутер решает, что и куда сохранить → применяем записи в слои →
 * собираем промпт (профиль + включённые слои) → запрос в LLM → диалог в краткосрочную
 * память → сохраняем каждый слой в файлы профиля.
 */
class MemoryAgent(
    private val client: ChatClient,
    private val profiles: ProfileStore = ProfileStore(),
    private val router: MemoryRouter = MemoryRouter(client),
    private val windowSize: Int = Config.windowSize(),
    private val memoryRoot: Path = Path.of(Config.MEMORY_DIR),
) {
    lateinit var profile: UserProfile
        private set

    private lateinit var store: MemoryStore
    lateinit var shortTerm: ShortTermMemory
        private set
    lateinit var working: WorkingMemory
        private set
    lateinit var longTerm: LongTermMemory
        private set

    val enabledLayers: MutableSet<MemoryLayer> = MemoryLayer.entries.toMutableSet()

    /** Подмешивать ли профиль в промпт. Выключение — для демонстрации «с профилем / без». */
    var personalizationOn: Boolean = true

    init {
        bootstrap()
    }

    /** Подсев демо-профилей и подключение активного (или первого) профиля. */
    private fun bootstrap() {
        profiles.seedDemoProfiles()
        val startId = profiles.activeId()?.takeIf { profiles.load(it) != null }
            ?: profiles.list().firstOrNull()
        val startProfile = startId?.let { profiles.load(it) } ?: ProfileStore.DEMO_PROFILES.first()
        attachProfile(startProfile)
    }

    /** Делает профиль активным: подключает его память и помечает активным в хранилище. */
    private fun attachProfile(p: UserProfile) {
        profile = p
        profiles.save(p)
        profiles.setActive(p.id)
        store = MemoryStore(memoryRoot.resolve(p.id))
        shortTerm = ShortTermMemory(windowSize)
        working = WorkingMemory()
        longTerm = LongTermMemory()
        store.loadLongTerm()?.let { longTerm.restore(it) }
        store.loadWorking()?.let { working.restore(it) }
        store.loadShortTerm()?.let { shortTerm.restore(it) }
    }

    fun processUserMessage(userText: String): TurnOutcome {
        val trimmed = userText.trim()
        if (trimmed.isEmpty()) return TurnOutcome.Fail(userText, "Сообщение не может быть пустым")

        // 1. Явная маршрутизация: что и в какой слой сохранить (профиль роутер не трогает).
        val routed = router.route(trimmed, shortTerm.all())
        val applied = applyWrites(routed.writes)

        // 2. Сборка промпта: профиль + включённые слои памяти.
        val messages = PromptBuilder.build(
            userText = trimmed,
            profile = profile,
            personalizationOn = personalizationOn,
            enabledLayers = enabledLayers,
            longTerm = longTerm,
            working = working,
            shortTerm = shortTerm,
        )
        val preview = PromptBuilder.preview(messages)

        // 3. Запрос в LLM.
        val result = runCatching { client.chat(messages) }
            .getOrElse { return TurnOutcome.Fail(trimmed, it.message ?: it.toString()) }

        // 4. Диалог уходит в краткосрочную память.
        shortTerm.appendUser(trimmed)
        shortTerm.appendAssistant(result.content)

        // 5. Каждый слой сохраняется в свой файл (внутри папки профиля).
        persistAll()

        return TurnOutcome.Ok(
            userText = trimmed,
            assistant = result.content,
            applied = applied,
            enabledLayers = enabledLayers.toSet(),
            profileName = profile.name,
            profileLabel = profile.shortLabel(),
            personalizationOn = personalizationOn,
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

    // ---------- Профили ----------

    /** id всех профилей. */
    fun listProfiles(): List<UserProfile> = profiles.list().mapNotNull { profiles.load(it) }

    /** Переключиться на профиль по id. Сохраняет память текущего и подключает память нового. */
    fun switchProfile(id: String): Boolean {
        val target = profiles.load(id) ?: return false
        if (target.id == profile.id) return true
        persistAll()
        attachProfile(target)
        return true
    }

    /** Создать/перезаписать профиль и сразу сделать его активным (используется в интервью). */
    fun createProfile(newProfile: UserProfile) {
        persistAll()
        profiles.save(newProfile)
        attachProfile(newProfile)
    }

    fun hasProfile(id: String): Boolean = profiles.load(id) != null

    /** Новая сессия: чистим краткосрочную и рабочую память, профиль сохраняем. */
    fun newSession() {
        shortTerm.clear()
        working.clear()
        store.deleteShortTerm()
        store.deleteWorking()
    }

    /**
     * Полная очистка приложения: удаляет ВСЕ профили и ВСЮ их память, затем возвращает
     * состояние к первому запуску (демо-профили пересеваются, активным становится первый).
     * Личного профиля «you» после этого нет — интервью при первом запуске запустится снова.
     */
    fun resetAll() {
        profiles.wipeAll()
        runCatching { memoryRoot.toFile().takeIf { it.exists() }?.deleteRecursively() }
        bootstrap()
        enabledLayers.clear()
        enabledLayers.addAll(MemoryLayer.entries)
        personalizationOn = true
    }

    /** Полный сброс памяти текущего профиля (профиль и его предпочтения остаются). */
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
