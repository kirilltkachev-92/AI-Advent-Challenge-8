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
        Path.of("../day 12/.env"),
        Path.of("day 12/.env"),
        Path.of("../day 11/.env"),
        Path.of("day 11/.env"),
        Path.of("../day 10/.env"),
        Path.of("day 10/.env"),
        Path.of("../day 1/.env"),
        Path.of("day 1/.env"),
    )

    const val API_BASE = "https://api.deepseek.com"
    const val MODEL_DEFAULT = "deepseek-chat"

    /** Последние N сообщений диалога (краткосрочная память), уходящие в API. */
    const val DEFAULT_WINDOW_SIZE = 6

    /** Корень хранилищ памяти. Память у каждого профиля своя: memory/<profileId>/… */
    const val MEMORY_DIR = "memory"

    /** Корень хранилища профилей. */
    const val PROFILES_DIR = "profiles"

    const val SYSTEM_PROMPT =
        "Ты персональный фитнес-коуч. Помогаешь с программами тренировок, подбором упражнений, " +
            "техникой и нагрузкой. " +
            "Тебе передан профиль пользователя — учитывай его в КАЖДОМ ответе автоматически, " +
            "не переспрашивая то, что в профиле уже есть: подстраивай стиль, формат и подробность " +
            "ответа под предпочтения, держись цели и уровня пользователя. " +
            "Ограничения из профиля и из памяти соблюдай неукоснительно: не предлагай упражнений, " +
            "которые им противоречат. Ты не врач — не ставишь диагнозы и при тревожных симптомах " +
            "советуешь обратиться к специалисту. " +
            "Кроме профиля используй слои памяти: текущую программу/цикл и его стадию (рабочая память) " +
            "и ход диалога (краткосрочная память). " +
            "Отвечай на языке пользователя. Если данных не хватает — уточни, не выдумывай."

    /**
     * Промпт роутера памяти. Роутер НЕ трогает профиль пользователя (профиль задаётся явно).
     * Он решает только, какие сведения из последнего сообщения вынести в рабочую или
     * долговременную память, и НЕ дублирует то, что уже есть в профиле.
     */
    const val ROUTER_PROMPT =
        "Ты — маршрутизатор памяти ассистента. На вход: последнее сообщение пользователя и контекст. " +
            "Реши, какие СТАБИЛЬНЫЕ сведения стоит сохранить и в какой слой памяти.\n" +
            "ВАЖНО: профиль пользователя (цель, уровень, инвентарь, стиль, формат, ограничения) " +
            "задаётся отдельно и тобой НЕ управляется — не дублируй эти поля.\n" +
            "Слои:\n" +
            "• long_term — новые устойчивые факты и решения, которых ещё нет в профиле: " +
            "выясненные травмы/запреты (kind=constraint), полезные факты о пользователе (kind=knowledge), " +
            "принятые решения по программе (kind=decision).\n" +
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
                    "в .env здесь или скопируйте .env из папки «day 1» / «day 11».",
            )

    fun model(): String = envValue(MODEL_VAR) ?: MODEL_DEFAULT

    fun windowSize(): Int =
        envValue(WINDOW_SIZE_VAR)?.toIntOrNull()?.coerceAtLeast(2)
            ?: DEFAULT_WINDOW_SIZE

    fun estimateCostUsd(promptTokens: Int, completionTokens: Int): Double =
        promptTokens * INPUT_PRICE_PER_M / 1_000_000.0 +
            completionTokens * OUTPUT_PRICE_PER_M / 1_000_000.0
}
