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
        Path.of("../day 11/.env"),
        Path.of("day 11/.env"),
        Path.of("../day 10/.env"),
        Path.of("day 10/.env"),
        Path.of("../day 9/.env"),
        Path.of("day 9/.env"),
        Path.of("../day 1/.env"),
        Path.of("day 1/.env"),
    )

    const val API_BASE = "https://api.deepseek.com"
    const val MODEL_DEFAULT = "deepseek-chat"

    /** Последние N сообщений диалога (краткосрочная память), уходящие в API. */
    const val DEFAULT_WINDOW_SIZE = 6

    /** Куда складываются файлы слоёв памяти. */
    const val MEMORY_DIR = "memory"

    const val SYSTEM_PROMPT =
        "Ты персональный фитнес-коуч. Помогаешь с программами тренировок, подбором упражнений, " +
            "техникой и нагрузкой — строго под профиль пользователя: цель, уровень подготовки, " +
            "доступный инвентарь и ограничения по здоровью. " +
            "Ограничения по здоровью соблюдай неукоснительно: не предлагай упражнений, которые им " +
            "противоречат. Ты не врач — не ставишь диагнозы и при тревожных симптомах советуешь " +
            "обратиться к специалисту. " +
            "Используй слои памяти: профиль и ограничения (долговременная память), текущую " +
            "программу/цикл и его стадию (рабочая память), ход диалога (краткосрочная память). " +
            "Отвечай на языке пользователя. Если данных в памяти не хватает — уточни, не выдумывай."

    /**
     * Промпт роутера памяти. LLM решает, какие сведения из последнего сообщения
     * пользователя нужно сохранить и В КАКОЙ слой. Короткий диалог в роутер не пишется —
     * это и есть назначение краткосрочного слоя.
     */
    const val ROUTER_PROMPT =
        "Ты — маршрутизатор памяти ассистента. На вход: последнее сообщение пользователя и контекст. " +
            "Реши, какие СТАБИЛЬНЫЕ сведения стоит сохранить и в какой слой памяти.\n" +
            "Слои:\n" +
            "• long_term — профиль и знания, которые не меняются от задачи к задаче: " +
            "цель тренировок, уровень подготовки, доступный инвентарь, ограничения по здоровью/травмы, " +
            "предпочтения по нагрузке и устойчивые факты о пользователе. " +
            "kind = profile | constraint | knowledge | decision (травмы и запреты — constraint).\n" +
            "• working — данные ТЕКУЩЕЙ задачи: текущая программа/цель цикла, конкретные упражнения и " +
            "подходы, заметки, выбранная стадия. kind = task | requirement | note | stage. " +
            "Для смены стадии используй kind=stage и value из набора: clarify, planning, execution, validation, done.\n" +
            "НЕ дублируй то, что является просто репликой диалога (это и так в краткосрочной памяти). " +
            "Если сохранять нечего — верни пустой список writes.\n" +
            "Ответь ТОЛЬКО JSON без markdown в формате: " +
            "{\"writes\":[{\"layer\":\"long_term|working\",\"kind\":\"...\",\"key\":\"короткий ключ\",\"value\":\"значение\"}]}"

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
                    "в .env здесь или скопируйте .env из папки «day 1» / «day 10».",
            )

    fun model(): String = envValue(MODEL_VAR) ?: MODEL_DEFAULT

    fun windowSize(): Int =
        envValue(WINDOW_SIZE_VAR)?.toIntOrNull()?.coerceAtLeast(2)
            ?: DEFAULT_WINDOW_SIZE

    fun estimateCostUsd(promptTokens: Int, completionTokens: Int): Double =
        promptTokens * INPUT_PRICE_PER_M / 1_000_000.0 +
            completionTokens * OUTPUT_PRICE_PER_M / 1_000_000.0
}
