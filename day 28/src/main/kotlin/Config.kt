import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.readLines

/**
 * Конфигурация Дня 28 (локальный RAG + сравнение с облаком).
 *
 * Retrieval и генерация — локальная Ollama (nomic-embed-text + qwen2.5:14b).
 * DEEPSEEK_API_KEY опционален: он нужен только облачной стороне сравнения
 * и слепому судье; без ключа прогон честно останется полностью локальным.
 */
object Config {
    const val DEEPSEEK_API_BASE = "https://api.deepseek.com"

    private const val DEFAULT_OLLAMA_URL = "http://localhost:11434"
    private const val DEFAULT_CHAT_MODEL = "qwen2.5:14b"
    private const val DEFAULT_EMBED_MODEL = "nomic-embed-text"
    private const val DEFAULT_DEEPSEEK_MODEL = "deepseek-chat"

    private val dotEnv = mutableMapOf<String, String>()

    // .env ищем у себя и в днях Недели 5 — ключ DeepSeek уже лежит там.
    private val dotEnvCandidates = listOf(
        Path.of(".env"),
        Path.of("day 28/.env"),
        Path.of("../day 25/.env"),
        Path.of("../day 22/.env"),
        Path.of("day 25/.env"),
        Path.of("day 22/.env"),
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

    fun deepSeekKeyOrNull(): String? = envValue("DEEPSEEK_API_KEY")

    fun deepSeekModel(): String = envValue("DEEPSEEK_MODEL") ?: DEFAULT_DEEPSEEK_MODEL

    fun dataDir(): Path = Path.of(envValue("DATA_DIR") ?: "data")

    fun outputDir(): Path = Path.of(envValue("OUTPUT_DIR") ?: "output")

    fun topK(): Int = envValue("TOP_K")?.toIntOrNull() ?: 5

    /** Сколько раз каждый вопрос уходит каждой модели — для оценки стабильности. */
    fun stabilityRuns(): Int = envValue("STABILITY_RUNS")?.toIntOrNull() ?: 3
}
