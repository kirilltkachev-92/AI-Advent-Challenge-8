import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.readLines

/**
 * Конфигурация Дня 35 — AI-конвейер подготовки релиза.
 *
 * Ключ DeepSeek ищется как в Днях 28–34: сначала окружение и локальный .env,
 * потом .env прошлых недель — чтобы не плодить копии ключа по репозиторию.
 */
object Config {
    const val DEEPSEEK_API_BASE = "https://api.deepseek.com"

    private val dotEnv = mutableMapOf<String, String>()

    fun loadDotEnv() {
        listOf(
            Path.of(".env"),
            Path.of("day 35/.env"),
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

    // --- LLM -------------------------------------------------------------------
    fun deepSeekApiKey(): String? = envValue("DEEPSEEK_API_KEY")
    fun deepSeekModel(): String = envValue("DEEPSEEK_MODEL") ?: "deepseek-chat"

    // --- Репозиторий, который релизим -------------------------------------------
    /** Корень репозитория челленджа (день лежит внутри него). */
    fun repoRoot(): Path = Path.of("..").toAbsolutePath().normalize()

    fun outputDir(): Path = Path.of("output")
    fun releaseNotesFile(): Path = outputDir().resolve("release-notes.md")
    fun reportFile(): Path = outputDir().resolve("report.md")
}
