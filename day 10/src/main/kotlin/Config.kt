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
    private const val WINDOW_SIZE_VAR = "WINDOW_SIZE"
    private val dotEnv = mutableMapOf<String, String>()

    private val dotEnvCandidates = listOf(
        Path.of(".env"),
        Path.of("../day 10/.env"),
        Path.of("day 10/.env"),
        Path.of("../day 9/.env"),
        Path.of("day 9/.env"),
        Path.of("../day 8/.env"),
        Path.of("day 8/.env"),
        Path.of("../day 1/.env"),
        Path.of("day 1/.env"),
    )

    const val API_BASE = "https://api.deepseek.com"
    const val MODEL_DEFAULT = "deepseek-chat"

    /** Последние N сообщений (user+assistant) уходят в API. */
    const val DEFAULT_WINDOW_SIZE = 6

    const val SYSTEM_PROMPT =
        "Ты продюсер и помощник по запуску подкаста. " +
            "Отвечай на русском языке. Учитывай предыдущие сообщения. Будь кратким и точным."

    const val FACTS_EXTRACTOR_PROMPT =
        "Ты извлекаешь ключевые факты из диалога в формате ключ-значение. " +
            "Сохраняй: цель, ограничения, предпочтения, решения, договорённости, числа, имена, даты. " +
            "Обновляй существующие факты при новых данных. " +
            "Ответь ТОЛЬКО JSON-объектом {\"ключ\": \"значение\", ...} без markdown."

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
                    "в .env здесь или скопируйте .env из папки «day 1» / «day 9».",
            )

    fun model(): String = envValue(MODEL_VAR) ?: MODEL_DEFAULT

    fun windowSize(): Int =
        envValue(WINDOW_SIZE_VAR)?.toIntOrNull()?.coerceAtLeast(2)
            ?: DEFAULT_WINDOW_SIZE

    fun estimateCostUsd(promptTokens: Int, completionTokens: Int): Double =
        promptTokens * INPUT_PRICE_PER_M / 1_000_000.0 +
            completionTokens * OUTPUT_PRICE_PER_M / 1_000_000.0
}
