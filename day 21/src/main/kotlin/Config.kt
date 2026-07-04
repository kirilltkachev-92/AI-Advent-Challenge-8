import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.readLines

/**
 * Конфигурация Дня 21 (индексация документов).
 *
 * Эмбеддинги считает локальная Ollama (nomic-embed-text) — ключей не нужно.
 * DEEPSEEK_API_KEY нужен только опциональному LLM-судье в сравнении стратегий;
 * без ключа сравнение просто пропускает этот шаг.
 */
object Config {
    private const val OLLAMA_URL_VAR = "OLLAMA_BASE_URL"
    private const val EMBED_MODEL_VAR = "EMBED_MODEL"
    private const val DOCS_VAR = "DOCS_DIR"
    private const val OUTPUT_VAR = "OUTPUT_DIR"
    private const val CHUNK_SIZE_VAR = "CHUNK_SIZE"
    private const val CHUNK_OVERLAP_VAR = "CHUNK_OVERLAP"
    private const val TOP_K_VAR = "TOP_K"
    private const val API_KEY_VAR = "DEEPSEEK_API_KEY"
    private const val MODEL_VAR = "DEEPSEEK_MODEL"

    const val DEEPSEEK_API_BASE = "https://api.deepseek.com"
    private const val DEEPSEEK_MODEL_DEFAULT = "deepseek-chat"

    private const val DEFAULT_OLLAMA_URL = "http://localhost:11434"
    private const val DEFAULT_EMBED_MODEL = "nomic-embed-text"
    private const val DEFAULT_DOCS = "docs"
    private const val DEFAULT_OUTPUT = "output"

    // Фиксированная стратегия: окно ~1200 символов с перекрытием 200,
    // чтобы мысль, разрезанная границей, попала целиком хотя бы в один чанк.
    private const val DEFAULT_CHUNK_SIZE = 1200
    private const val DEFAULT_CHUNK_OVERLAP = 200
    private const val DEFAULT_TOP_K = 3

    // Структурная стратегия: секции длиннее лимита дорезаются тем же окном —
    // контекст nomic-embed-text (2048 токенов) иначе молча обрежет хвост.
    const val MAX_SECTION_CHARS = 4000

    private val dotEnv = mutableMapOf<String, String>()

    private val dotEnvCandidates = listOf(
        Path.of(".env"),
        Path.of("day 21/.env"),
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

    fun ollamaBaseUrl(): String = (envValue(OLLAMA_URL_VAR) ?: DEFAULT_OLLAMA_URL).trimEnd('/')

    fun embedModel(): String = envValue(EMBED_MODEL_VAR) ?: DEFAULT_EMBED_MODEL

    fun docsDir(): Path = Path.of(envValue(DOCS_VAR) ?: DEFAULT_DOCS)

    fun outputDir(): Path = Path.of(envValue(OUTPUT_VAR) ?: DEFAULT_OUTPUT)

    fun chunkSize(): Int = envValue(CHUNK_SIZE_VAR)?.toIntOrNull() ?: DEFAULT_CHUNK_SIZE

    fun chunkOverlap(): Int = envValue(CHUNK_OVERLAP_VAR)?.toIntOrNull() ?: DEFAULT_CHUNK_OVERLAP

    fun topK(): Int = envValue(TOP_K_VAR)?.toIntOrNull() ?: DEFAULT_TOP_K

    /** Ключ DeepSeek — опционален (нужен только LLM-судье). */
    fun deepSeekKeyOrNull(): String? = envValue(API_KEY_VAR)

    fun deepSeekModel(): String = envValue(MODEL_VAR) ?: DEEPSEEK_MODEL_DEFAULT
}
