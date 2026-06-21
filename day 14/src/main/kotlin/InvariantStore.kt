import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * Хранилище инвариантов — ОТДЕЛЬНО от диалога и состояния задачи
 * (`invariants/invariants.json`). Это и есть «инварианты хранятся отдельно от диалога».
 * При первом запуске подсевается набор примеров (стек/решения/бизнес-правила).
 */
class InvariantStore(rootDir: Path = Path.of(Config.INVARIANTS_DIR)) {
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true; encodeDefaults = true }
    private val file: Path = rootDir.resolve("invariants.json")

    init {
        runCatching { rootDir.createDirectories() }
    }

    fun exists(): Boolean = file.exists()

    fun load(): List<Invariant>? =
        file.takeIf { it.exists() }?.let { path ->
            runCatching { json.decodeFromString<List<Invariant>>(path.readText()) }.getOrNull()
        }

    fun save(invariants: List<Invariant>) {
        runCatching { file.writeText(json.encodeToString(invariants)) }
    }

    fun wipe() {
        runCatching { Files.deleteIfExists(file) }
    }

    /** Подсев примеров, если файла ещё нет. Возвращает итоговый список. */
    fun loadOrSeed(): List<Invariant> {
        load()?.let { return it }
        save(DEFAULTS)
        return DEFAULTS
    }

    val path: Path get() = file

    companion object {
        /** Примеры инвариантов из задания: домен, стек, решения, архитектура, бизнес-правила. */
        val DEFAULTS: List<Invariant> = listOf(
            Invariant(
                id = "domain-android-only",
                category = "домен",
                rule = "Агент работает ТОЛЬКО с Android-приложениями (Kotlin/Jetpack). Запросы вне " +
                    "Android (бэкенд, веб, десктоп, CLI, скрипты, ML-пайплайны и т.п.) не выполняются.",
            ),
            Invariant(
                id = "stack-no-rxjava",
                category = "стек",
                rule = "Реактивность только на Kotlin Coroutines/Flow. RxJava запрещена.",
                forbid = listOf("rxjava", "io.reactivex"),
            ),
            Invariant(
                id = "deps-oss-only",
                category = "решение",
                rule = "Только бесплатные open-source зависимости с разрешительной лицензией " +
                    "(MIT/Apache). GPL и платные SDK запрещены.",
                forbid = listOf("gpl"),
            ),
            Invariant(
                id = "arch-no-mvp",
                category = "архитектура",
                rule = "Архитектура презентации только MVVM/MVI (ViewModel + однонаправленный поток " +
                    "состояния). MVP запрещён.",
                forbid = listOf("mvp"),
            ),
            Invariant(
                id = "arch-no-logic-in-ui",
                category = "архитектура",
                rule = "Бизнес-логика не должна находиться в UI-слое; слои разделены " +
                    "(управление состоянием через ViewModel/состояние, без логики во вью).",
            ),
            Invariant(
                id = "biz-no-secrets-in-code",
                category = "бизнес-правило",
                rule = "Секреты (пароли, API-ключи, токены) нельзя хранить в коде или в открытом виде " +
                    "— только через переменные окружения/конфиг.",
            ),
        )
    }
}
