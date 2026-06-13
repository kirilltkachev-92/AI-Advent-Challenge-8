import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.readLines

data class ChatResult(
    val content: String,
    val latencyMs: Long,
    val promptTokens: Int,
    val completionTokens: Int,
    val totalTokens: Int,
    val tokensFromApi: Boolean,
    val estimatedCostUsd: Double,
)

object Config {
    private const val API_KEY_VAR = "DEEPSEEK_API_KEY"
    private const val MODEL_VAR = "DEEPSEEK_MODEL"
    private const val RECENT_COUNT_VAR = "RECENT_MESSAGE_COUNT"
    private const val COMPRESS_BATCH_VAR = "COMPRESS_BATCH_SIZE"
    private val dotEnv = mutableMapOf<String, String>()

    private val dotEnvCandidates = listOf(
        Path.of(".env"),
        Path.of("../day 9/.env"),
        Path.of("day 9/.env"),
        Path.of("../day 8/.env"),
        Path.of("day 8/.env"),
        Path.of("../day 7/.env"),
        Path.of("day 7/.env"),
        Path.of("../day 1/.env"),
        Path.of("day 1/.env"),
    )

    const val API_BASE = "https://api.deepseek.com"
    const val MODEL_DEFAULT = "deepseek-chat"

    /** Последние N сообщений (user+assistant) отправляются в API без сжатия. */
    const val DEFAULT_RECENT_MESSAGE_COUNT = 6

    /** Каждые N сообщений вне окна сжимаются в summary. */
    const val DEFAULT_COMPRESS_BATCH_SIZE = 10

    const val SYSTEM_PROMPT =
        "Ты полезный ассистент. Отвечай на русском языке. " +
            "Учитывай предыдущие сообщения в диалоге. Будь кратким."

    const val SUMMARY_SYSTEM_PROMPT =
        "Ты сжимаешь историю диалога в краткое резюме на русском. " +
            "Сохраняй факты, имена, числа, решения и контекст, нужный для продолжения разговора. " +
            "Не добавляй ничего от себя."

    private const val INPUT_PRICE_PER_M = 0.14
    private const val OUTPUT_PRICE_PER_M = 0.28

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

    fun apiKey(): String =
        envValue(API_KEY_VAR)
            ?: error(
                "Нет API-ключа. Задайте $API_KEY_VAR в окружении, " +
                    "в .env здесь или скопируйте .env из папки «day 1» / «day 7».",
            )

    fun model(): String = envValue(MODEL_VAR) ?: MODEL_DEFAULT

    fun recentMessageCount(): Int =
        envValue(RECENT_COUNT_VAR)?.toIntOrNull()?.coerceAtLeast(2)
            ?: DEFAULT_RECENT_MESSAGE_COUNT

    fun compressBatchSize(): Int =
        envValue(COMPRESS_BATCH_VAR)?.toIntOrNull()?.coerceAtLeast(2)
            ?: DEFAULT_COMPRESS_BATCH_SIZE

    fun estimateCostUsd(promptTokens: Int, completionTokens: Int): Double =
        promptTokens * INPUT_PRICE_PER_M / 1_000_000.0 +
            completionTokens * OUTPUT_PRICE_PER_M / 1_000_000.0
}
