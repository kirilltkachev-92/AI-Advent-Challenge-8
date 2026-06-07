import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.readLines

enum class ModelTier { Weak, Medium, Strong }

enum class ModelProvider { HuggingFace, DeepSeek }

data class ModelProfile(
    val tier: ModelTier,
    val provider: ModelProvider,
    val modelId: String,
    val label: String,
    val description: String,
    val url: String,
)

data class ChatResult(
    val content: String,
    val latencyMs: Long,
    val promptTokens: Int,
    val completionTokens: Int,
    val totalTokens: Int,
    val tokensEstimated: Boolean,
    val estimatedCostUsd: Double,
    val modelId: String,
    val tier: ModelTier? = null,
)

object Config {
    private const val HF_TOKEN_VAR = "HF_TOKEN"
    private const val DEEPSEEK_KEY_VAR = "DEEPSEEK_API_KEY"
    private val dotEnv = mutableMapOf<String, String>()

    private val dotEnvCandidates = listOf(
        Path.of(".env"),
        Path.of("../day 5/.env"),
        Path.of("day 5/.env"),
        Path.of("../day 4/.env"),
        Path.of("day 4/.env"),
        Path.of("../day 1/.env"),
        Path.of("day 1/.env"),
    )

    const val HF_API_BASE = "https://router.huggingface.co"
    const val DEEPSEEK_API_BASE = "https://api.deepseek.com"

    const val HF_WEAK_MODEL_DEFAULT = "CohereLabs/tiny-aya-global"
    const val HF_MEDIUM_MODEL_DEFAULT = "meta-llama/Llama-3.1-8B-Instruct"
    const val DEEPSEEK_STRONG_MODEL_DEFAULT = "deepseek-v4-pro"

    const val SYSTEM_BASE = "Ты помощник. Отвечай на русском языке. Будь кратким и точным."

    const val SAMPLE_PROMPT =
        "5 станков за 5 минут изготавливают 5 деталей. " +
            "За сколько минут 100 станков изготовят 100 деталей? " +
            "Объясни ход рассуждений и дай числовой ответ."

    private const val DEEPSEEK_INPUT_PER_M = 1.74
    private const val DEEPSEEK_OUTPUT_PER_M = 3.48
    private const val HF_WEAK_PER_M = 0.05
    private const val HF_MEDIUM_PER_M = 0.15

    val MODEL_PROFILES: List<ModelProfile> = listOf(
        ModelProfile(
            tier = ModelTier.Weak,
            provider = ModelProvider.HuggingFace,
            modelId = HF_WEAK_MODEL_DEFAULT,
            label = "Слабая",
            description = "Tiny Aya Global — компактная мультиязычная модель (Cohere)",
            url = "https://huggingface.co/CohereLabs/tiny-aya-global",
        ),
        ModelProfile(
            tier = ModelTier.Medium,
            provider = ModelProvider.HuggingFace,
            modelId = HF_MEDIUM_MODEL_DEFAULT,
            label = "Средняя",
            description = "Llama 3.1 8B — популярная open-модель среднего размера",
            url = "https://huggingface.co/meta-llama/Llama-3.1-8B-Instruct",
        ),
        ModelProfile(
            tier = ModelTier.Strong,
            provider = ModelProvider.DeepSeek,
            modelId = DEEPSEEK_STRONG_MODEL_DEFAULT,
            label = "Сильная",
            description = "DeepSeek V4 Pro — флагманская модель",
            url = "https://api-docs.deepseek.com",
        ),
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

    fun hfToken(): String =
        envValue(HF_TOKEN_VAR)
            ?: error(
                "Нет HF-токена. Задайте $HF_TOKEN_VAR в окружении или в .env в папке «day 5».",
            )

    fun deepSeekApiKey(): String =
        envValue(DEEPSEEK_KEY_VAR)
            ?: error(
                "Нет API-ключа DeepSeek. Задайте $DEEPSEEK_KEY_VAR в окружении, " +
                    "в .env здесь или скопируйте .env из папки «day 4» / «day 1».",
            )

    fun profile(tier: ModelTier): ModelProfile =
        MODEL_PROFILES.first { it.tier == tier }

    fun modelId(tier: ModelTier): String = when (tier) {
        ModelTier.Weak -> envValue("HF_WEAK_MODEL") ?: HF_WEAK_MODEL_DEFAULT
        ModelTier.Medium -> envValue("HF_MEDIUM_MODEL") ?: HF_MEDIUM_MODEL_DEFAULT
        ModelTier.Strong -> envValue("DEEPSEEK_STRONG_MODEL") ?: DEEPSEEK_STRONG_MODEL_DEFAULT
    }

    fun estimateHuggingFaceCost(tier: ModelTier, promptTokens: Int, completionTokens: Int): Double {
        val perM = when (tier) {
            ModelTier.Weak -> HF_WEAK_PER_M
            ModelTier.Medium -> HF_MEDIUM_PER_M
            ModelTier.Strong -> 0.0
        }
        return (promptTokens + completionTokens) * perM / 1_000_000.0
    }

    fun estimateDeepSeekCost(promptTokens: Int, completionTokens: Int): Double =
        promptTokens * DEEPSEEK_INPUT_PER_M / 1_000_000.0 +
            completionTokens * DEEPSEEK_OUTPUT_PER_M / 1_000_000.0

    fun estimateTokensFromText(text: String): Int =
        (text.length / 4.0).toInt().coerceAtLeast(1)
}
