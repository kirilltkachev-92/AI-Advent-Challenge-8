import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * Хранилище одного профиля разработчика (`profiles/dev.json`). Читаемый JSON, переживает
 * перезапуски — персонализация задаётся один раз в интервью и применяется ко всем агентам.
 */
class ProfileStore(rootDir: Path = Path.of(Config.PROFILES_DIR)) {
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true; encodeDefaults = true }
    private val file: Path = rootDir.resolve("dev.json")

    init {
        runCatching { rootDir.createDirectories() }
    }

    fun exists(): Boolean = file.exists()

    fun load(): DevProfile? =
        file.takeIf { it.exists() }?.let { path ->
            runCatching { json.decodeFromString<DevProfile>(path.readText()) }.getOrNull()
        }

    fun save(profile: DevProfile) {
        runCatching { file.writeText(json.encodeToString(profile)) }
    }

    fun wipe() {
        runCatching { Files.deleteIfExists(file) }
    }

    val path: Path get() = file
}
