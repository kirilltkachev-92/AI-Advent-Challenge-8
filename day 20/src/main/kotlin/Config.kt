import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.readLines

/** Описание одного MCP-сервера для оркестратора: имя, порт встроенного сервера, путь. */
data class ServerSpec(val name: String, val port: Int, val path: String) {
    val url: String get() = "http://localhost:$port$path"
}

/**
 * Конфигурация Дня 20 (оркестрация нескольких MCP-серверов).
 *
 * Четыре сервера (research / weather / report / storage) поднимаются локально на разных портах
 * и сводятся роутером в один список инструментов. Внешние источники (Википедия, Open-Meteo)
 * бесплатны и без ключа; DEEPSEEK_API_KEY нужен агенту, который выбирает инструменты и строит флоу.
 */
object Config {
    private const val API_KEY_VAR = "DEEPSEEK_API_KEY"
    private const val MODEL_VAR = "DEEPSEEK_MODEL"
    private const val BASE_PORT_VAR = "MCP_BASE_PORT"
    private const val OUTPUT_VAR = "OUTPUT_DIR"
    private const val LANG_VAR = "WIKI_LANG"
    private const val WIKI_DELAY_VAR = "WIKI_DELAY_MS"

    const val API_BASE = "https://api.deepseek.com"
    const val MODEL_DEFAULT = "deepseek-chat"

    const val MCP_PATH = "/mcp"
    private const val DEFAULT_BASE_PORT = 8781
    private const val DEFAULT_OUTPUT = "output"
    private const val DEFAULT_LANG = "ru"
    private const val DEFAULT_WIKI_DELAY_MS = 500L

    private val dotEnv = mutableMapOf<String, String>()

    private val dotEnvCandidates = listOf(
        Path.of(".env"),
        Path.of("day 20/.env"),
        Path.of("../day 20/.env"),
        Path.of("../day 19/.env"),
        Path.of("day 19/.env"),
        Path.of("../day 18/.env"),
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
            ?: error("Нет API-ключа. Задайте $API_KEY_VAR в окружении или .env (можно из «day 19»/«day 1»).")

    fun model(): String = envValue(MODEL_VAR) ?: MODEL_DEFAULT

    /** Базовый порт; четыре сервера занимают base..base+3. */
    private fun basePort(): Int = envValue(BASE_PORT_VAR)?.toIntOrNull() ?: DEFAULT_BASE_PORT

    /** Четыре сервера на последовательных портах от базового. */
    fun servers(): List<ServerSpec> {
        val base = basePort()
        return listOf(
            ServerSpec("research-mcp", base, MCP_PATH),
            ServerSpec("weather-mcp", base + 1, MCP_PATH),
            ServerSpec("report-mcp", base + 2, MCP_PATH),
            ServerSpec("storage-mcp", base + 3, MCP_PATH),
        )
    }

    /** Каталог, куда storage-MCP пишет файлы. */
    fun outputDir(): Path = Path.of(envValue(OUTPUT_VAR) ?: DEFAULT_OUTPUT)

    /** Язык Википедии для поиска (ru/en/…). */
    fun wikiLang(): String = envValue(LANG_VAR) ?: DEFAULT_LANG

    /** Минимальная пауза между запросами к Википедии, мс (вежливый троттлинг от бана/429). */
    fun wikiDelayMs(): Long = envValue(WIKI_DELAY_VAR)?.toLongOrNull()?.coerceAtLeast(0) ?: DEFAULT_WIKI_DELAY_MS
}
