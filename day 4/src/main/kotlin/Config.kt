import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.readLines

object Config {
    private const val API_KEY_VAR = "DEEPSEEK_API_KEY"
    private val dotEnv = mutableMapOf<String, String>()

    private val dotEnvCandidates = listOf(
        Path.of(".env"),
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
            dotEnv[key] = value
        }
    }

    fun apiKey(): String =
        System.getenv(API_KEY_VAR)?.takeIf { it.isNotBlank() }
            ?: dotEnv[API_KEY_VAR]?.takeIf { it.isNotBlank() }
            ?: error(
                "Нет API-ключа. Задайте $API_KEY_VAR в окружении, в .env здесь " +
                    "или скопируйте .env из папки «day 1».",
            )

    const val API_BASE = "https://api.deepseek.com"
    const val MODEL = "deepseek-chat"

    const val SYSTEM_BASE = "Ты помощник. Отвечай на русском языке."

    const val TEMPERATURE_MIN = 0.0
    const val TEMPERATURE_MAX = 2.0
    const val TEMPERATURE_STEP = 0.3

    /** Дополнительные значения между шагами (например, 0.7 между 0.6 и 0.9). */
    private val EXTRA_TEMPERATURES = listOf(0.7)

    /** Значения от 0 до 2 с шагом 0.3, плюс дополнительные точки (конечная 2.0 включена). */
    val TEMPERATURE_STEPS: List<Double> = buildList {
        var t = TEMPERATURE_MIN
        while (t <= TEMPERATURE_MAX + 1e-9) {
            add(roundTemperature(t))
            t += TEMPERATURE_STEP
        }
        if (isEmpty() || last() < TEMPERATURE_MAX - 1e-9) {
            add(TEMPERATURE_MAX)
        }
        addAll(EXTRA_TEMPERATURES.map(::roundTemperature))
    }.distinct().sorted()

    private fun roundTemperature(value: Double): Double =
        kotlin.math.round(value * 10.0) / 10.0

    const val SAMPLE_PROMPT =
        "Кратко объясни разницу между стеком (stack) и очередью (queue). " +
            "Приведи по одному примеру из реальной жизни для каждой структуры. " +
            "В конце предложи запоминающуюся аналогию."
}
