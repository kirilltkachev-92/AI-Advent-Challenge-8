import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.readLines

/**
 * Конфигурация подключения к MCP-серверу.
 *
 * Источники значений (в порядке приоритета): переменные окружения → файл .env → дефолт.
 * По умолчанию используется публичный MCP Microsoft Learn (документация Microsoft/Azure/.NET) —
 * он не требует ключей, поэтому задание проверяется сразу, без настройки. Сменить сервер
 * можно через .env.
 */
object Config {
    private const val SERVER_URL_VAR = "MCP_SERVER_URL"
    private const val AUTH_TOKEN_VAR = "MCP_AUTH_TOKEN"

    /**
     * Дефолтный сервер — публичный MCP Microsoft Learn. Бесплатный, без ключа, размещён
     * самой Microsoft (надёжный). Инструмент microsoft_docs_search ищет по документации.
     */
    private const val DEFAULT_SERVER_URL = "https://learn.microsoft.com/api/mcp"

    private val dotEnv = mutableMapOf<String, String>()

    private val dotEnvCandidates = listOf(
        Path.of(".env"),
        Path.of("day 16/.env"),
        Path.of("../day 16/.env"),
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
            dotEnv[key] = value
        }
    }

    private fun envValue(key: String): String? =
        System.getenv(key)?.takeIf { it.isNotBlank() }
            ?: dotEnv[key]?.takeIf { it.isNotBlank() }

    fun serverUrl(): String = envValue(SERVER_URL_VAR) ?: DEFAULT_SERVER_URL

    /** Bearer-токен; null, если сервер не требует авторизации (как DeepWiki). */
    fun authToken(): String? = envValue(AUTH_TOKEN_VAR)
}
