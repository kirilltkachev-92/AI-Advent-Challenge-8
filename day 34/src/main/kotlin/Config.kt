import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.readLines

/**
 * Конфигурация Дня 34 — ассистент для работы с файлами проекта.
 *
 * Ключ DeepSeek ищется как в Днях 28–33: сначала окружение и локальный .env,
 * потом .env прошлых недель — чтобы не плодить копии ключа по репозиторию.
 */
object Config {
    const val DEEPSEEK_API_BASE = "https://api.deepseek.com"

    private val dotEnv = mutableMapOf<String, String>()

    fun loadDotEnv() {
        listOf(
            Path.of(".env"),
            Path.of("day 34/.env"),
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

    // --- MCP ---------------------------------------------------------------------
    fun mcpPort(): Int = envInt("MCP_PORT", 8034)
    const val MCP_PATH = "/mcp"
    fun mcpUrl(): String = "http://localhost:${mcpPort()}$MCP_PATH"

    // --- Проект, над которым работает ассистент ---------------------------------
    /** Нетронутый шаблон демо-проекта (в git). */
    fun projectTemplate(): Path = Path.of("data/project")

    /** Рабочая копия, которую ассистент читает и меняет (восстанавливается reset-ом). */
    fun projectWorkDir(): Path = Path.of("output/project")

    fun outputDir(): Path = Path.of("output")
}
