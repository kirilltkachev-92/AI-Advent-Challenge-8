import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.readLines

/**
 * Конфигурация Дня 27 (CLI-чат по истории Telegram на локальной LLM).
 * Всё берётся из окружения или .env; дефолты — локальная Ollama и qwen2.5.
 */
object Config {
    private const val DEFAULT_OLLAMA_URL = "http://localhost:11434"
    private const val DEFAULT_CHAT_MODEL = "qwen2.5:14b"
    private const val DEFAULT_EMBED_MODEL = "nomic-embed-text"

    private val dotEnv = mutableMapOf<String, String>()

    private val dotEnvCandidates = listOf(
        Path.of(".env"),
        Path.of("day 27/.env"),
    )

    fun loadDotEnv() {
        dotEnvCandidates.forEach { path -> if (path.exists()) loadDotEnvFile(path) }
    }

    private fun loadDotEnvFile(path: Path) {
        path.readLines().forEach { line ->
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#")) return@forEach
            val idx = trimmed.indexOf('=')
            if (idx <= 0) return@forEach
            val key = trimmed.substring(0, idx).trim()
            var value = trimmed.substring(idx + 1).trim()
            if ((value.startsWith('"') && value.endsWith('"')) ||
                (value.startsWith('\'') && value.endsWith('\''))
            ) {
                value = value.substring(1, value.length - 1)
            }
            dotEnv.putIfAbsent(key, value)
        }
    }

    private fun envValue(key: String): String? =
        System.getenv(key)?.takeIf { it.isNotBlank() }
            ?: dotEnv[key]?.takeIf { it.isNotBlank() }

    fun ollamaBaseUrl(): String = (envValue("OLLAMA_BASE_URL") ?: DEFAULT_OLLAMA_URL).trimEnd('/')

    fun chatModel(): String = envValue("CHAT_MODEL") ?: DEFAULT_CHAT_MODEL

    fun embedModel(): String = envValue("EMBED_MODEL") ?: DEFAULT_EMBED_MODEL

    /** Экспорт чата: EXPORT_PATH из окружения или копия рядом с проектом. */
    fun exportPath(): Path {
        envValue("EXPORT_PATH")?.let { return Path.of(it) }
        return listOf(Path.of("data-messages.html"), Path.of("day 27/data-messages.html"))
            .firstOrNull { it.exists() }
            ?: Path.of("data-messages.html")
    }

    fun outputDir(): Path = Path.of(envValue("OUTPUT_DIR") ?: "output")
}
