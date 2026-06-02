import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.readLines

object Config {
    private const val API_KEY_VAR = "DEEPSEEK_API_KEY"
    private val dotEnv = mutableMapOf<String, String>()

    fun loadDotEnv(path: Path = Path.of(".env")) {
        if (!path.exists()) return
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
                "Missing API key. Set $API_KEY_VAR in your environment " +
                    "or add it to a .env file (see .env.example).",
            )

    const val API_BASE = "https://api.deepseek.com"
    const val MODEL = "deepseek-chat"
}
