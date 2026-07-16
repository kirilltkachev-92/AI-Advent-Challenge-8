import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.readLines

/**
 * Конфигурация Дня 32 — AI-ревью кода.
 *
 * Локально ключ DeepSeek ищется в .env этой и прошлых недель;
 * в GitHub Actions всё приходит через окружение (secrets и GITHUB_*).
 */
object Config {
    private const val DEFAULT_OLLAMA_URL = "http://localhost:11434"
    private const val DEFAULT_EMBED_MODEL = "nomic-embed-text"
    const val DEEPSEEK_API_BASE = "https://api.deepseek.com"

    private val dotEnv = mutableMapOf<String, String>()

    fun loadDotEnv() {
        listOf(
            Path.of(".env"),
            Path.of("day 32/.env"),
            Path.of("../day 25/.env"),
            Path.of("../day 22/.env"),
            Path.of("../day 17/.env"),
        ).forEach { path ->
            if (path.exists()) {
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
        }
    }

    private fun envValue(key: String): String? =
        System.getenv(key)?.takeIf { it.isNotBlank() }
            ?: dotEnv[key]?.takeIf { it.isNotBlank() }

    private fun envInt(key: String, default: Int): Int = envValue(key)?.toIntOrNull() ?: default

    // --- LLM -------------------------------------------------------------------
    fun deepSeekApiKey(): String? = envValue("DEEPSEEK_API_KEY")
    fun deepSeekModel(): String = envValue("DEEPSEEK_MODEL") ?: "deepseek-chat"

    // --- Эмбеддинги (Ollama: локально и в runner-е Actions) ----------------------
    fun ollamaBaseUrl(): String = (envValue("OLLAMA_BASE_URL") ?: DEFAULT_OLLAMA_URL).trimEnd('/')
    fun embedModel(): String = envValue("EMBED_MODEL") ?: DEFAULT_EMBED_MODEL

    // --- RAG -----------------------------------------------------------------------
    fun topK(): Int = envInt("TOP_K", 6)
    fun maxChunkChars(): Int = envInt("MAX_CHUNK_CHARS", 1500)
    fun outputDir(): Path = Path.of(envValue("OUTPUT_DIR") ?: "output")
    fun indexPath(): Path = outputDir().resolve("index-repo.json")

    // --- Ревью -----------------------------------------------------------------------
    fun maxDiffChars(): Int = envInt("MAX_DIFF_CHARS", 40_000)

    // --- GitHub Actions ---------------------------------------------------------------
    fun githubToken(): String? = envValue("GITHUB_TOKEN")
    fun githubRepo(): String? = envValue("GITHUB_REPOSITORY") // owner/repo
    fun githubEventPath(): String? = envValue("GITHUB_EVENT_PATH")
    fun githubApiBase(): String = envValue("GITHUB_API_URL") ?: "https://api.github.com"
}
