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
    private const val CONTEXT_LIMIT_VAR = "CONTEXT_LIMIT_TOKENS"
    private val dotEnv = mutableMapOf<String, String>()

    private val dotEnvCandidates = listOf(
        Path.of(".env"),
        Path.of("../day 8/.env"),
        Path.of("day 8/.env"),
        Path.of("../day 7/.env"),
        Path.of("day 7/.env"),
        Path.of("../day 1/.env"),
        Path.of("day 1/.env"),
    )

    const val API_BASE = "https://api.deepseek.com"

    /** https://api-docs.deepseek.com/quick_start/pricing — deepseek-v4-flash */
    const val MODEL_DEFAULT = "deepseek-v4-flash"

    /** DeepSeek-V4-Flash: 1M context (официальная документация API). */
    const val MODEL_CONTEXT_LIMIT = 1_000_000

    /**
     * Запас поверх лимита: оценка chars/4 грубее токенизатора DeepSeek.
     * Для 1M контекста важен небольшой запас.
     */
    const val OVERFLOW_API_SAFETY_MARGIN = 1.05

    const val SYSTEM_PROMPT =
        "Ты полезный ассистент. Отвечай на русском языке. " +
            "Учитывай предыдущие сообщения в диалоге. Будь кратким."

    /** deepseek-v4-flash, cache miss (консервативная оценка стоимости). */
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

    fun contextLimitTokens(): Int =
        envValue(CONTEXT_LIMIT_VAR)?.toIntOrNull()?.coerceAtLeast(256)
            ?: MODEL_CONTEXT_LIMIT

    fun estimateCostUsd(promptTokens: Int, completionTokens: Int): Double =
        promptTokens * INPUT_PRICE_PER_M / 1_000_000.0 +
            completionTokens * OUTPUT_PRICE_PER_M / 1_000_000.0

    fun formatTokenCount(tokens: Int): String =
        when {
            tokens >= 1_000_000 -> String.format("%.2fM", tokens / 1_000_000.0)
            tokens >= 1_000 -> String.format("%.1fK", tokens / 1_000.0)
            else -> tokens.toString()
        }
}
