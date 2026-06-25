import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.readLines

/**
 * Конфигурация Дня 18 (планировщик + фоновые задачи).
 *
 * Источники значений (по приоритету): переменные окружения → .env → дефолт.
 * Open-Meteo бесплатный и без ключа; DEEPSEEK_API_KEY нужен только агенту-сводке
 * (без него планировщик всё равно собирает данные и печатает детерминированную сводку).
 */
object Config {
    private const val API_KEY_VAR = "DEEPSEEK_API_KEY"
    private const val MODEL_VAR = "DEEPSEEK_MODEL"
    private const val SERVER_URL_VAR = "MCP_SERVER_URL"
    private const val PORT_VAR = "MCP_PORT"
    private const val CITIES_VAR = "WATCH_CITIES"
    private const val COLLECT_VAR = "COLLECT_INTERVAL_SEC"
    private const val SUMMARY_VAR = "SUMMARY_INTERVAL_SEC"
    private const val DATA_FILE_VAR = "DATA_FILE"

    const val API_BASE = "https://api.deepseek.com"
    const val MODEL_DEFAULT = "deepseek-chat"

    const val MCP_PATH = "/mcp"
    private const val DEFAULT_PORT = 8765

    // Демо-значения: собираем замер раз в 20 c, сводку — раз в минуту.
    // Для «настоящего 24/7» поставьте, например, 300 и 3600.
    private const val DEFAULT_COLLECT_SEC = 20L
    private const val DEFAULT_SUMMARY_SEC = 60L
    private const val DEFAULT_DATA_FILE = "data/samples.json"
    private val DEFAULT_CITIES = listOf("Париж", "Токио")

    private val dotEnv = mutableMapOf<String, String>()

    private val dotEnvCandidates = listOf(
        Path.of(".env"),
        Path.of("day 18/.env"),
        Path.of("../day 18/.env"),
        Path.of("../day 17/.env"),
        Path.of("day 17/.env"),
        Path.of("../day 15/.env"),
        Path.of("day 15/.env"),
        Path.of("../day 1/.env"),
        Path.of("day 1/.env"),
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

    fun apiKey(): String =
        envValue(API_KEY_VAR)
            ?: error("Нет API-ключа. Задайте $API_KEY_VAR в окружении или .env (можно из «day 17»/«day 1»).")

    fun model(): String = envValue(MODEL_VAR) ?: MODEL_DEFAULT

    fun port(): Int = envValue(PORT_VAR)?.toIntOrNull() ?: DEFAULT_PORT

    fun serverUrl(): String =
        envValue(SERVER_URL_VAR) ?: "http://localhost:${port()}$MCP_PATH"

    fun hasExternalServer(): Boolean = envValue(SERVER_URL_VAR) != null

    /** Города, за которыми следит планировщик (через запятую в WATCH_CITIES). */
    fun watchCities(): List<String> =
        envValue(CITIES_VAR)
            ?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }
            ?.takeIf { it.isNotEmpty() }
            ?: DEFAULT_CITIES

    fun collectIntervalSec(): Long = envValue(COLLECT_VAR)?.toLongOrNull() ?: DEFAULT_COLLECT_SEC

    fun summaryIntervalSec(): Long = envValue(SUMMARY_VAR)?.toLongOrNull() ?: DEFAULT_SUMMARY_SEC

    fun dataFile(): Path = Path.of(envValue(DATA_FILE_VAR) ?: DEFAULT_DATA_FILE)
}
