import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.readLines

/**
 * Конфигурация Дня 17.
 *
 * Источники значений (в порядке приоритета): переменные окружения → .env → дефолт.
 * Нужен только DEEPSEEK_API_KEY (для агента). MCP-сервер вокруг Open-Meteo ключей
 * не требует — API бесплатный и без регистрации.
 */
object Config {
    private const val API_KEY_VAR = "DEEPSEEK_API_KEY"
    private const val MODEL_VAR = "DEEPSEEK_MODEL"
    private const val SERVER_URL_VAR = "MCP_SERVER_URL"
    private const val PORT_VAR = "MCP_PORT"

    const val API_BASE = "https://api.deepseek.com"
    const val MODEL_DEFAULT = "deepseek-chat"

    /** Путь, на котором MCP-сервер слушает JSON-RPC (единый эндпоинт Streamable HTTP). */
    const val MCP_PATH = "/mcp"
    private const val DEFAULT_PORT = 8765

    private const val INPUT_PRICE_PER_M = 0.14
    private const val OUTPUT_PRICE_PER_M = 0.28

    private val dotEnv = mutableMapOf<String, String>()

    // Ключ DeepSeek уже лежит в .env прошлых дней — переиспользуем, чтобы не дублировать.
    private val dotEnvCandidates = listOf(
        Path.of(".env"),
        Path.of("day 17/.env"),
        Path.of("../day 17/.env"),
        Path.of("../day 15/.env"),
        Path.of("day 15/.env"),
        Path.of("../day 1/.env"),
        Path.of("day 1/.env"),
    )

    fun loadDotEnv() {
        dotEnvCandidates.forEach { path ->
            if (path.exists()) loadDotEnvFile(path)
        }
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

    fun apiKey(): String =
        envValue(API_KEY_VAR)
            ?: error(
                "Нет API-ключа. Задайте $API_KEY_VAR в окружении, в .env здесь " +
                    "или скопируйте .env из папки «day 1» / «day 15».",
            )

    fun model(): String = envValue(MODEL_VAR) ?: MODEL_DEFAULT

    fun port(): Int = envValue(PORT_VAR)?.toIntOrNull() ?: DEFAULT_PORT

    /**
     * URL MCP-сервера для агента.
     *  - если задан MCP_SERVER_URL (например, адрес VPS) — берём его;
     *  - иначе localhost: приложение само поднимет встроенный сервер на этом порту.
     */
    fun serverUrl(): String =
        envValue(SERVER_URL_VAR) ?: "http://localhost:${port()}$MCP_PATH"

    /** true, если сервер внешний (его не нужно поднимать в этом процессе). */
    fun hasExternalServer(): Boolean = envValue(SERVER_URL_VAR) != null

    fun estimateCostUsd(promptTokens: Int, completionTokens: Int): Double =
        promptTokens * INPUT_PRICE_PER_M / 1_000_000.0 +
            completionTokens * OUTPUT_PRICE_PER_M / 1_000_000.0
}
