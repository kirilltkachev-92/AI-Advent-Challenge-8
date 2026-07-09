import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.readLines

/**
 * Конфигурация Дня 29 (оптимизация локальной LLM).
 *
 * Прогон полностью локальный: облачных ключей нет вообще.
 * Все настройки модели (параметры, промпт, квант) живут на нашей стороне —
 * в коде и опциях запроса к Ollama, а не в GUI типа LM Studio.
 */
object Config {
    private const val DEFAULT_OLLAMA_URL = "http://localhost:11434"
    private const val DEFAULT_BASE_MODEL = "qwen2.5:14b"
    private const val DEFAULT_QUANT_MODEL = "qwen2.5:14b-instruct-q3_K_M"
    private const val DEFAULT_MINI_MODEL = "qwen2.5:0.5b"

    private val dotEnv = mutableMapOf<String, String>()

    fun loadDotEnv() {
        listOf(Path.of(".env"), Path.of("day 29/.env")).forEach { path ->
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

    fun ollamaBaseUrl(): String = (envValue("OLLAMA_BASE_URL") ?: DEFAULT_OLLAMA_URL).trimEnd('/')

    /** Базовая модель Дней 26–28 (q4_K_M — квант по умолчанию у Ollama). */
    fun baseModel(): String = envValue("BASE_MODEL") ?: DEFAULT_BASE_MODEL

    /** Те же веса 14b, но квант q3_K_M — для сравнения квантований. */
    fun quantModel(): String = envValue("QUANT_MODEL") ?: DEFAULT_QUANT_MODEL

    /** Крошечная модель — крайняя точка оси «ресурсы против качества». */
    fun miniModel(): String = envValue("MINI_MODEL") ?: DEFAULT_MINI_MODEL

    fun dataDir(): Path = Path.of(envValue("DATA_DIR") ?: "data")

    fun outputDir(): Path = Path.of(envValue("OUTPUT_DIR") ?: "output")

    /** Сколько раз каждый кейс уходит каждому профилю — для оценки стабильности. */
    fun stabilityRuns(): Int = envValue("STABILITY_RUNS")?.toIntOrNull() ?: 2

    /** Ограничение числа кейсов (для быстрых прогонов при отладке). */
    fun caseLimit(): Int = envValue("CASE_LIMIT")?.toIntOrNull() ?: Int.MAX_VALUE
}
