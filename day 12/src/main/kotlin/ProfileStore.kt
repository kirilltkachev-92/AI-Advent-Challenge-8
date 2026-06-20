import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.nameWithoutExtension
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * Хранилище профилей пользователей. Каждый профиль — отдельный JSON-файл
 * (`profiles/<id>.json`), активный профиль помечен в `profiles/active.txt`.
 *
 * Несколько профилей нужны для главной проверки дня: один и тот же запрос на разных
 * профилях даёт разные ответы. Поэтому при первом запуске сюда подсевают набор
 * контрастных демо-персон.
 */
class ProfileStore(private val rootDir: Path = Path.of("profiles")) {
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true; encodeDefaults = true }
    private val activeFile: Path = rootDir.resolve("active.txt")

    init {
        runCatching { rootDir.createDirectories() }
    }

    /** Все id профилей в алфавитном порядке. */
    fun list(): List<String> =
        runCatching {
            rootDir.listDirectoryEntries("*.json").map { it.nameWithoutExtension }.sorted()
        }.getOrDefault(emptyList())

    fun load(id: String): UserProfile? =
        fileFor(id).takeIf { it.exists() }?.let { path ->
            runCatching { json.decodeFromString<UserProfile>(path.readText()) }.getOrNull()
        }

    fun save(profile: UserProfile) {
        runCatching { fileFor(profile.id).writeText(json.encodeToString(profile)) }
    }

    fun delete(id: String) {
        runCatching { Files.deleteIfExists(fileFor(id)) }
    }

    fun activeId(): String? =
        activeFile.takeIf { it.exists() }
            ?.let { runCatching { it.readText().trim() }.getOrNull() }
            ?.takeIf { it.isNotBlank() }

    fun setActive(id: String) {
        runCatching { activeFile.writeText(id) }
    }

    fun isEmpty(): Boolean = list().isEmpty()

    /** Полностью очищает хранилище профилей (все профили + отметку активного). */
    fun wipeAll() {
        runCatching { rootDir.listDirectoryEntries().forEach { Files.deleteIfExists(it) } }
    }

    /** Подсевает демо-персоны, если профилей ещё нет. Возвращает, что добавили. */
    fun seedDemoProfiles(): List<UserProfile> {
        if (!isEmpty()) return emptyList()
        DEMO_PROFILES.forEach { save(it) }
        return DEMO_PROFILES
    }

    private fun fileFor(id: String): Path = rootDir.resolve("$id.json")

    companion object {
        /**
         * Три заведомо разных пользователя. Один и тот же вопрос («составь тренировку на
         * сегодня») должен дать им ощутимо разные ответы — это и есть демонстрация
         * персонализации.
         */
        val DEMO_PROFILES: List<UserProfile> = listOf(
            UserProfile(
                id = "anna",
                name = "Анна",
                context = Context(
                    who = "32 года, офисная сидячая работа, тренируется впервые",
                    goal = "похудение и общий тонус",
                    level = "новичок",
                    equipment = "дома, без инвентаря",
                ),
                style = Style(
                    verbosity = "подробно",
                    tone = "дружелюбно и мотивирующе",
                    jargon = "избегать терминов",
                    emoji = true,
                ),
                format = Format(structure = "по шагам", length = "развёрнутый", numbers = true),
                constraints = listOf("беречь колени", "без прыжков", "не предлагать абонемент в зал"),
            ),
            UserProfile(
                id = "igor",
                name = "Игорь",
                context = Context(
                    who = "продвинутый любитель, тренируется 5 лет",
                    goal = "набор мышечной массы",
                    level = "продвинутый",
                    equipment = "тренажёрный зал, полный инвентарь",
                ),
                style = Style(
                    verbosity = "кратко",
                    tone = "строго по делу",
                    jargon = "можно термины",
                    emoji = false,
                ),
                format = Format(structure = "списком", length = "короткий", numbers = true),
                constraints = listOf("без воды и общих советов", "только конкретика: подходы×повторы, RPE, отдых"),
            ),
            UserProfile(
                id = "maria",
                name = "Мария",
                context = Context(
                    who = "бегунья-любитель, готовится к полумарафону",
                    goal = "выносливость",
                    level = "средний",
                    equipment = "улица и коврик, только вес тела",
                ),
                style = Style(
                    verbosity = "сбалансированно",
                    tone = "нейтрально",
                    jargon = "по ситуации",
                    emoji = false,
                ),
                format = Format(structure = "по шагам", length = "средний", numbers = true),
                constraints = listOf("беречь ахилл", "силовое — только в поддержку бега"),
            ),
        )
    }
}
