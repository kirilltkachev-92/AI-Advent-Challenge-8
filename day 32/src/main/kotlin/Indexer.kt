import java.nio.file.Path
import java.time.OffsetDateTime
import kotlin.io.path.exists

/**
 * Строит (или загружает из кеша) индекс документации и кода репозитория.
 * Кеш двухуровневый: локально — файл с SHA корпуса внутри; в GitHub Actions
 * тот же файл лежит в actions/cache с ключом от hashFiles репозитория,
 * поэтому «RAG заново на каждый прогон» не случается (замечание тьютора) —
 * пересчёт только когда документация или код реально поменялись.
 */
object Indexer {
    fun buildOrLoad(repoRoot: Path, embedder: OllamaEmbedder): DocumentIndex {
        val docs = RepoCorpus.collect(repoRoot)
        check(docs.isNotEmpty()) { "Не найдено ни документации, ни кода в $repoRoot" }
        val sha = RepoCorpus.corpusSha(docs)
        val indexPath = Config.indexPath()

        if (indexPath.exists()) {
            val cached = runCatching { IndexStore.load(indexPath) }.getOrNull()
            if (cached != null && cached.corpus_sha == sha && cached.embed_model == Config.embedModel()) {
                println("→ Индекс актуален: ${cached.chunks.size} чанков из ${docs.size} файлов (кеш по SHA корпуса)")
                return cached
            }
        }

        val chunks = docs.flatMap { RepoCorpus.chunk(it, Config.maxChunkChars()) }
        val docsCount = docs.count { it.kind == "docs" }
        println(
            "→ Строю индекс: ${docs.size} файлов ($docsCount документации, ${docs.size - docsCount} кода) " +
                "→ ${chunks.size} чанков, модель ${Config.embedModel()}",
        )
        val vectors = embedder.embedDocuments(chunks.map { it.text }) { done, total ->
            print("\r  эмбеддинги: $done/$total")
        }
        println()

        val index = DocumentIndex(
            created_at = OffsetDateTime.now().toString(),
            embed_model = Config.embedModel(),
            dim = vectors.firstOrNull()?.size ?: 0,
            strategy = RepoCorpus.STRATEGY,
            corpus_sha = sha,
            chunks = chunks.mapIndexed { i, c ->
                IndexedChunk(
                    chunk_id = c.chunkId,
                    source = c.source,
                    title = c.title,
                    section = c.section,
                    strategy = RepoCorpus.STRATEGY,
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
