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
    const val TEMPERATURE = 0.3

    const val SYSTEM_BASE = "Ты помощник. Отвечай на русском языке."

    const val SAMPLE_TASK =
        "Постройте красно-чёрное дерево (RB-tree) при последовательной вставке ключей: " +
            "10, 20, 5, 15, 25, 3, 7. Для каждой вставки покажите: цвет нового узла, " +
            "нарушенные инварианты (если есть), выполненные операции (перекраска, левый/правый поворот) " +
            "и итоговое дерево. В конце проверьте все свойства RB-дерева."
}
