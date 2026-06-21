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
    private val dotEnv = mutableMapOf<String, String>()

    private val dotEnvCandidates = listOf(
        Path.of(".env"),
        Path.of("../day 14/.env"),
        Path.of("day 14/.env"),
        Path.of("../day 13/.env"),
        Path.of("day 13/.env"),
        Path.of("../day 12/.env"),
        Path.of("day 12/.env"),
        Path.of("../day 11/.env"),
        Path.of("day 11/.env"),
        Path.of("../day 1/.env"),
        Path.of("day 1/.env"),
    )

    const val API_BASE = "https://api.deepseek.com"
    const val MODEL_DEFAULT = "deepseek-chat"

    /** Корень хранилища задач: state/<…>. Состояние задачи переживает перезапуск (пауза/возобновление). */
    const val STATE_DIR = "state"

    /** Хранилище профиля разработчика. */
    const val PROFILES_DIR = "profiles"

    /** Хранилище инвариантов (жёстких ограничений) — отдельно от диалога и задачи. */
    const val INVARIANTS_DIR = "invariants"

    /**
     * Общая «шапка» для каждого агента: единая роль продукта (кодинг-агент). Конкретную
     * специализацию задаёт system prompt самого агента (см. [AgentSpecs]).
     */
    const val PRODUCT_PROMPT =
        "Ты — часть автономного кодинг-агента для ANDROID-приложений (Kotlin/Jetpack). Агент ведёт " +
            "задачу по конечному автомату planning → execution → validation → done. У каждого этапа " +
            "свои агенты со своим system prompt; результат этапа (артефакт) передаётся дальше по " +
            "пайплайну. Работай только в контексте Android-разработки. Отвечай по делу, на русском " +
            "языке, без воды. Код давай в markdown-блоках с указанием языка."

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
                "Нет API-ключа. Задайте $API_KEY_VAR в окружении, в .env здесь " +
                    "или скопируйте .env из папки «day 1» / «day 12».",
            )

    fun model(): String = envValue(MODEL_VAR) ?: MODEL_DEFAULT

    fun estimateCostUsd(promptTokens: Int, completionTokens: Int): Double =
        promptTokens * INPUT_PRICE_PER_M / 1_000_000.0 +
            completionTokens * OUTPUT_PRICE_PER_M / 1_000_000.0
}
