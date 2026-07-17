import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * Формат индекса — тот же, что в Неделе 5 (День 21): чанк с метаданными
 * (source, title, section, chunk_id) + вектор, файл индекса — JSON.
 * Здесь source — файл базы знаний из data/docs, section — раздел markdown.
 */
@Serializable
data class IndexedChunk(
    val chunk_id: String,
    val source: String,
    val title: String,
    val section: String? = null,
    val strategy: String,
    val char_start: Int,
    val char_end: Int,
    val text: String,
    val embedding: List<Float>,
)

/** Файл индекса целиком; corpus_sha — чтобы пересчитывать только при смене корпуса. */
@Serializable
data class DocumentIndex(
    val created_at: String,
    val embed_model: String,
    val dim: Int,
    val strategy: String,
    val corpus_sha: String? = null,
    val chunks: List<IndexedChunk>,
)

object IndexStore {
    private val json = Json { prettyPrint = false; ignoreUnknownKeys = true }

    fun save(index: DocumentIndex, path: Path) {
        Files.createDirectories(path.parent)
        path.writeText(json.encodeToString(DocumentIndex.serializer(), index))
    }

    fun load(path: Path): DocumentIndex =
        json.decodeFromString(DocumentIndex.serializer(), path.readText())
}
