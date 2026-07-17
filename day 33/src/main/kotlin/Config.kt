import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.readLines

/**
 * Конфигурация Дня 33 — ассистент поддержки пользователей.
 *
 * Ключ DeepSeek ищется как в Днях 28–32: сначала окружение и локальный .env,
 * потом .env прошлых недель — чтобы не плодить копии ключа по репозиторию.
 */
object Config {
    private const val DEFAULT_OLLAMA_URL = "http://localhost:11434"
    private const val DEFAULT_EMBED_MODEL = "nomic-embed-text"
    const val DEEPSEEK_API_BASE = "https://api.deepseek.com"

    private val dotEnv = mutableMapOf<String, String>()

    fun loadDotEnv() {
        listOf(
            Path.of(".env"),
            Path.of("day 33/.env"),
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

    // --- Эмбеддинги (локальная Ollama, как в Неделях 5–6) ------------------------
    fun ollamaBaseUrl(): String = (envValue("OLLAMA_BASE_URL") ?: DEFAULT_OLLAMA_URL).trimEnd('/')
    fun embedModel(): String = envValue("EMBED_MODEL") ?: DEFAULT_EMBED_MODEL

    // --- MCP ---------------------------------------------------------------------
    fun mcpPort(): Int = envInt("MCP_PORT", 8033)
    const val MCP_PATH = "/mcp"
    fun mcpUrl(): String = "http://localhost:${mcpPort()}$MCP_PATH"

    // --- Данные продукта и CRM ---------------------------------------------------
    fun docsDir(): Path = Path.of("data/docs")
    fun crmSourcePath(): Path = Path.of("data/crm.json")
    fun crmStatePath(): Path = outputDir().resolve("crm-state.json")

    // --- RAG -----------------------------------------------------------------------
    fun topK(): Int = envInt("TOP_K", 4)
    fun maxSectionChars(): Int = envInt("MAX_SECTION_CHARS", 1500)
    fun outputDir(): Path = Path.of(envValue("OUTPUT_DIR") ?: "output")
    fun indexPath(): Path = outputDir().resolve("index-kb.json")
}
