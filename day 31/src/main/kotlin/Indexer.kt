import java.nio.file.Path
import java.time.OffsetDateTime
import kotlin.io.path.exists

/**
 * Строит (или загружает из кеша) индекс документации проекта.
 * Кеш по SHA-256 корпуса — как в Днях 28–30: пересчёт эмбеддингов только
 * когда README или docs реально поменялись.
 */
object Indexer {
    fun buildOrLoad(repoRoot: Path, embedder: OllamaEmbedder): DocumentIndex {
        val docs = DocsCorpus.collect(repoRoot)
        check(docs.isNotEmpty()) { "Не найдено ни одного README.md в $repoRoot" }
        val sha = DocsCorpus.corpusSha(docs)
        val indexPath = Config.indexPath()

        if (indexPath.exists()) {
            val cached = runCatching { IndexStore.load(indexPath) }.getOrNull()
            if (cached != null && cached.corpus_sha == sha && cached.embed_model == Config.embedModel()) {
                println("→ Индекс актуален: ${cached.chunks.size} чанков из ${docs.size} документов (кеш по SHA корпуса)")
                return cached
            }
        }

        val chunks = docs.flatMap { DocsCorpus.chunk(it, Config.maxSectionChars()) }
        println("→ Строю индекс: ${docs.size} документов → ${chunks.size} чанков, модель ${Config.embedModel()}")
        val vectors = embedder.embedDocuments(chunks.map { it.text }) { done, total ->
            print("\r  эмбеддинги: $done/$total")
        }
        println()

        val index = DocumentIndex(
            created_at = OffsetDateTime.now().toString(),
            embed_model = Config.embedModel(),
            dim = vectors.firstOrNull()?.size ?: 0,
            strategy = DocsCorpus.STRATEGY,
            corpus_sha = sha,
            chunks = chunks.mapIndexed { i, c ->
                IndexedChunk(
                    chunk_id = c.chunkId,
                    source = c.source,
                    title = c.title,
                    section = c.section,
                    strategy = DocsCorpus.STRATEGY,
                    char_start = c.charStart,
                    char_end = c.charEnd,
                    text = c.text,
                    embedding = vectors[i].toList(),
                )
            },
        )
        IndexStore.save(index, indexPath)
        println("→ Индекс сохранён: $indexPath")
        return index
    }
}
