import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

@Serializable
private data class ChatHistorySnapshot(
    val messages: List<ChatMessage>,
)

/**
 * Сохраняет историю диалога в JSON-файл и восстанавливает её при следующем запуске.
 */
class ChatHistoryStore(
    private val file: Path = Config.HISTORY_FILE,
) {
    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
        encodeDefaults = true
    }

    fun load(): List<ChatMessage>? {
        if (!file.exists()) return null

        return try {
            json.decodeFromString<ChatHistorySnapshot>(file.readText()).messages
        } catch (_: Exception) {
            null
        }
    }

    fun save(messages: List<ChatMessage>) {
        file.parent?.let { Files.createDirectories(it) }
        file.writeText(json.encodeToString(ChatHistorySnapshot(messages)))
    }

    fun clear() {
        if (file.exists()) {
            Files.deleteIfExists(file)
        }
    }
}
