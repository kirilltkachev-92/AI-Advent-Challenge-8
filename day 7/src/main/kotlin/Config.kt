import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.readLines

object Config {
    private const val API_KEY_VAR = "DEEPSEEK_API_KEY"
    private val dotEnv = mutableMapOf<String, String>()

    private val dotEnvCandidates = listOf(
        Path.of(".env"),
        Path.of("../day 7/.env"),
        Path.of("day 7/.env"),
        Path.of("../day 6/.env"),
        Path.of("day 6/.env"),
        Path.of("../day 5/.env"),
        Path.of("day 5/.env"),
        Path.of("../day 1/.env"),
        Path.of("day 1/.env"),
    )

    const val API_BASE = "https://api.deepseek.com"
    const val MODEL = "deepseek-chat"
    const val HISTORY_FILE_NAME = "chat_history.json"
    val HISTORY_FILE: Path = Path.of(HISTORY_FILE_NAME)

    const val SYSTEM_PROMPT =
        "Ты полезный ассистент. Отвечай на русском языке. " +
            "Учитывай предыдущие сообщения в диалоге."

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

    fun apiKey(): String =
        System.getenv(API_KEY_VAR)?.takeIf { it.isNotBlank() }
            ?: dotEnv[API_KEY_VAR]?.takeIf { it.isNotBlank() }
            ?: error(
                "Нет API-ключа. Задайте $API_KEY_VAR в окружении, " +
                    "в .env здесь или скопируйте .env из папки «day 1» / «day 5» / «day 6».",
            )
}
