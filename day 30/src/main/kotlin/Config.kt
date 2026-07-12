import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.readLines

/**
 * Конфигурация Дня 30 — приватный LLM-сервис.
 *
 * Всё, что относится к «базовым ограничениям» из задания, задаётся здесь
 * и отдаётся клиенту через GET /v1/limits: rate limit, максимальный контекст,
 * лимит длины сообщения, потолок одновременных генераций.
 */
object Config {
    private const val DEFAULT_OLLAMA_URL = "http://localhost:11434"
    private const val DEFAULT_CHAT_MODEL = "qwen2.5:1.5b" // влезает в дешёвый VPS без GPU
    private const val DEFAULT_EMBED_MODEL = "nomic-embed-text"

    private val dotEnv = mutableMapOf<String, String>()

    fun loadDotEnv() {
        listOf(Path.of(".env"), Path.of("day 30/.env")).forEach { path ->
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

    // --- Сеть ----------------------------------------------------------------
    fun bindHost(): String = envValue("BIND_HOST") ?: "0.0.0.0" // слушаем все интерфейсы: снаружи должно быть видно
    fun port(): Int = envInt("PORT", 8030)

    // --- Ollama и модели -------------------------------------------------------
    fun ollamaBaseUrl(): String = (envValue("OLLAMA_BASE_URL") ?: DEFAULT_OLLAMA_URL).trimEnd('/')
    fun chatModel(): String = envValue("CHAT_MODEL") ?: DEFAULT_CHAT_MODEL
    fun embedModel(): String = envValue("EMBED_MODEL") ?: DEFAULT_EMBED_MODEL
    fun temperature(): Double = envValue("TEMPERATURE")?.toDoubleOrNull() ?: 0.2

    // --- Авторизация -----------------------------------------------------------
    // Сервис публичный по сети, но приватный по доступу: без токена — 401.
    fun apiTokens(): Set<String> =
        (envValue("API_TOKENS") ?: "").split(",").map { it.trim() }.filter { it.isNotEmpty() }.toSet()

    // --- Базовые ограничения (задание: rate limit / max context) ---------------
    fun rateLimitPerMin(): Int = envInt("RATE_LIMIT_PER_MIN", 12)   // устоявшийся темп
    fun rateLimitBurst(): Int = envInt("RATE_LIMIT_BURST", 5)       // допустимый залп
    fun numCtx(): Int = envInt("NUM_CTX", 4096)                     // окно контекста модели
    fun maxAnswerTokens(): Int = envInt("MAX_ANSWER_TOKENS", 512)   // num_predict
    fun maxInputChars(): Int = envInt("MAX_INPUT_CHARS", 2000)      // одно сообщение, иначе 413
    fun historyCharBudget(): Int = envInt("HISTORY_CHAR_BUDGET", 6000) // история режется с хвоста
    fun fragmentsCharBudget(): Int = envInt("FRAGMENTS_CHAR_BUDGET", 6000) // RAG-фрагменты
    fun maxConcurrent(): Int = envInt("MAX_CONCURRENT", 2)          // одновременных генераций
    fun maxWaiting(): Int = envInt("MAX_WAITING", 8)                // очередь, дальше 503
    fun queueTimeoutMs(): Long = envInt("QUEUE_TIMEOUT_MS", 60_000).toLong()

    // --- RAG --------------------------------------------------------------------
    fun topK(): Int = envInt("TOP_K", 4)
    fun dataDir(): Path = Path.of(envValue("DATA_DIR") ?: "data")
    fun outputDir(): Path = Path.of(envValue("OUTPUT_DIR") ?: "output")

    // --- Проверка (режим verify) -------------------------------------------------
    fun verifyBaseUrl(): String =
        (envValue("VERIFY_BASE_URL") ?: "http://localhost:${port()}").trimEnd('/')
    fun verifyToken(): String? = envValue("VERIFY_TOKEN") ?: apiTokens().firstOrNull()
    fun verifyParallel(): Int = envInt("VERIFY_PARALLEL", 6)
    fun verifySequential(): Int = envInt("VERIFY_SEQUENTIAL", 4)
}
