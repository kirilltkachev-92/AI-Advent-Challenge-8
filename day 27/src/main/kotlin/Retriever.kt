import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readBytes
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.math.sqrt

/**
 * Локальный семантический поиск по чату: сообщения режутся на перекрывающиеся
 * окна, каждое окно превращается в вектор через /api/embed (nomic-embed-text,
 * 137M — крутится на той же локальной Ollama), вопрос сравнивается с окнами
 * по косинусной близости. Индекс кешируется в output/index.json и пересчитывается
 * только если экспорт изменился (сверяем SHA-256 файла).
 *
 * nomic-embed-text обучена с префиксами задач: документы индексируются как
 * "search_document: …", вопрос — как "search_query: …" — это заметно улучшает поиск.
 */
class Retriever(
    private val client: OllamaClient,
    private val embedModel: String = Config.embedModel(),
) {
    @Serializable
    data class Chunk(val header: String, val text: String, val embedding: DoubleArray)

    @Serializable
    private data class Index(val exportSha256: String, val embedModel: String, val chunks: List<Chunk>)

    data class Hit(val chunk: Chunk, val score: Double)

    private val json = Json { ignoreUnknownKeys = true }
    private var chunks: List<Chunk> = emptyList()

    val size: Int get() = chunks.size

    /** Строит индекс (или поднимает из кеша). Возвращает true, если считали заново. */
    fun buildIndex(messages: List<TgMessage>, exportPath: Path, cachePath: Path): Boolean {
        val sha = sha256(exportPath)
        if (cachePath.exists()) {
            runCatching { json.decodeFromString<Index>(cachePath.readText()) }
                .getOrNull()
                ?.takeIf { it.exportSha256 == sha && it.embedModel == embedModel }
                ?.let { chunks = it.chunks; return false }
        }

        val windows = slidingWindows(messages, windowSize = 8, step = 5)
        val embeddings = client.embed(embedModel, windows.map { "search_document: ${it.second}" })
        chunks = windows.zip(embeddings) { (header, text), vector -> Chunk(header, text, vector) }

        cachePath.parent?.createDirectories()
        cachePath.writeText(json.encodeToString(Index(sha, embedModel, chunks)))
        return true
    }

    /**
     * Топ-K окон чата по гибридной оценке: косинусная близость векторов
     * плюс бонус за точные слова вопроса в тексте («день 27» найдётся дословно,
     * даже если многоязычные эмбеддинги русский вопрос поняли так себе).
     */
    fun search(question: String, k: Int = 5): List<Hit> {
        val queryVector = client.embed(embedModel, listOf("search_query: $question")).first()
        val words = question.lowercase()
            .split(Regex("[^\\p{L}\\p{N}]+"))
            .filter { it.length >= 3 || it.any(Char::isDigit) }
            .toSet()
        return chunks
            .map { chunk ->
                val lower = chunk.text.lowercase()
                val overlap = if (words.isEmpty()) 0.0 else words.count { lower.contains(it) }.toDouble() / words.size
                Hit(chunk, cosine(queryVector, chunk.embedding) + 0.2 * overlap)
            }
            .sortedByDescending { it.score }
            .take(k)
    }

    /** Окна по windowSize сообщений с перекрытием — контекст реплик не рвётся на границе. */
    private fun slidingWindows(messages: List<TgMessage>, windowSize: Int, step: Int): List<Pair<String, String>> =
        (messages.indices step step).map { start ->
            val window = messages.subList(start, minOf(start + windowSize, messages.size))
            val header = "${window.first().date} ${window.first().time}–${window.last().time}"
            header to window.joinToString("\n") { "[${it.date} ${it.time}] ${it.author}: ${it.text}" }
        }.distinctBy { it.second }

    private fun cosine(a: DoubleArray, b: DoubleArray): Double {
        var dot = 0.0
        var normA = 0.0
        var normB = 0.0
        for (i in a.indices) {
            dot += a[i] * b[i]
            normA += a[i] * a[i]
            normB += b[i] * b[i]
        }
        return dot / (sqrt(normA) * sqrt(normB))
    }

    private fun sha256(path: Path): String =
        MessageDigest.getInstance("SHA-256").digest(path.readBytes())
            .joinToString("") { "%02x".format(it) }
}
