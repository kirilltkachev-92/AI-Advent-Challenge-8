import java.nio.file.Path
import java.security.MessageDigest
import java.time.LocalDateTime
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.readBytes

/**
 * Знание сервиса — весь чат AI Advent Challenge #8 (31.05–11.07).
 * Индекс тот же, что в Дне 28: одно сообщение = один вектор nomic-embed-text,
 * пересчитывается только при смене корпуса (SHA-256 всех messages*.html).
 */
object Indexer {

    fun loadOrBuild(messages: List<TgMessage>, embedder: OllamaEmbedder): DocumentIndex {
        val indexPath = Config.outputDir().also { it.createDirectories() }.resolve("index-chat.json")
        val sha = corpusSha(Config.dataDir())
        if (indexPath.exists()) {
            val cached = runCatching { IndexStore.load(indexPath) }.getOrNull()
            if (cached != null && cached.corpus_sha == sha && cached.embed_model == Config.embedModel()) {
                return cached
            }
        }
        return build(messages, embedder, sha, indexPath)
    }

    private fun build(
        messages: List<TgMessage>,
        embedder: OllamaEmbedder,
        corpusSha: String,
        indexPath: Path,
    ): DocumentIndex {
        val chunks = Chunking.perMessage(messages)
        println("  индекс устарел или отсутствует — эмбеддинг ${chunks.size} сообщений…")
        val vectors = embedder.embedDocuments(chunks.map { it.text }) { done, total ->
            if (done % 256 == 0 || done == total) print("\r  эмбеддинг: $done/$total")
        }
        println()
        val index = DocumentIndex(
            created_at = LocalDateTime.now().toString(),
            embed_model = Config.embedModel(),
            dim = vectors.first().size,
            strategy = "message",
            corpus_sha = corpusSha,
            chunks = chunks.zip(vectors) { c, v ->
                IndexedChunk(
                    chunk_id = c.chunkId,
                    source = c.source,
                    title = "AI Advent Challenge #8",
                    section = c.section,
                    strategy = "message",
                    char_start = c.msgIndex,
                    char_end = c.msgIndex,
                    text = c.text,
                    embedding = v.toList(),
                )
            },
        )
        IndexStore.save(index, indexPath)
        return index
    }

    private fun corpusSha(dataDir: Path): String {
        val digest = MessageDigest.getInstance("SHA-256")
        dataDir.listDirectoryEntries("messages*.html").sortedBy { it.fileName.toString() }
            .forEach { digest.update(it.readBytes()) }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
