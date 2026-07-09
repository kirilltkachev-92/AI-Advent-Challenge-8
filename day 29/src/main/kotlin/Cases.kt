import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.file.Path
import kotlin.io.path.readText

/**
 * Контрольный набор конкретной задачи: 10 объявлений «День N» из чата челленджа
 * (Дни 1–28, все темы марафона) с зафиксированным эталоном. Эталоны выведены
 * из самих объявлений и проверены руками — оценка полностью программная,
 * без облачного судьи: день оптимизации локальной модели остаётся локальным.
 */
@Serializable
data class GoldCard(
    val title: String,
    val theme: String,
    val result: String,
    val format: String,
)

@Serializable
data class Case(
    val day: Int,
    val text: String,
    val gold: GoldCard,
)

object Cases {
    val THEMES = listOf("api", "agents", "context", "memory", "state", "mcp", "rag", "local")

    private val json = Json { ignoreUnknownKeys = true }

    fun load(dataDir: Path): List<Case> =
        json.decodeFromString<List<Case>>(dataDir.resolve("cases.json").readText())
}
