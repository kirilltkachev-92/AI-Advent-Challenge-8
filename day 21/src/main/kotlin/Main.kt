import java.nio.file.Files
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/**
 * День 21 — индексация документов.
 *
 * Пайплайн: документы (pdf/md/kt) → текст → чанки (2 стратегии) → эмбеддинги
 * (локальная Ollama) → JSON-индекс с метаданными → сравнение стратегий.
 *
 * Команды:
 *   ./run.sh            — весь пайплайн: index + compare
 *   ./run.sh index      — только построить индексы
 *   ./run.sh compare    — только сравнение (по готовым индексам)
 *   ./run.sh search «запрос» — поиск по обоим индексам
 */
fun main(args: Array<String>) {
    Config.loadDotEnv()
    val command = args.firstOrNull() ?: "all"
    when (command) {
        "index" -> buildIndexes()
        "compare" -> compare()
        "search" -> search(args.drop(1).joinToString(" "))
        "all" -> { buildIndexes(); compare() }
        else -> println("Неизвестная команда «$command». Доступно: index | compare | search «запрос»")
    }
}

private fun buildIndexes() {
    val embedder = OllamaEmbedder()
    println("== Шаг 1: документы → текст ==")
    val corpus = DocumentLoader.loadCorpus(Config.docsDir())
    var totalPages = 0.0
    corpus.forEach {
        totalPages += it.pagesEquivalent
        println("  ${it.title} (${it.kind}) — ${it.text.length} символов ≈ ${"%.1f".format(it.pagesEquivalent)} стр.")
    }
    println("  итого: ${corpus.size} документов ≈ ${"%.0f".format(totalPages)} страниц текста")

    val chunkers = listOf(
        FixedSizeChunker(Config.chunkSize(), Config.chunkOverlap()),
        StructureChunker(),
    )
    chunkers.forEach { chunker ->
        println("== Шаг 2–4 (${chunker.strategy}): chunking → эмбеддинги → индекс ==")
        val chunks = corpus.flatMap { chunker.chunk(it) }
        println("  чанков: ${chunks.size}")
        val vectors = embedder.embedDocuments(chunks.map { it.text }) { done, total ->
            print("\r  эмбеддинги: $done/$total")
        }
        println()
        val index = DocumentIndex(
            created_at = ZonedDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
            embed_model = Config.embedModel(),
            dim = vectors.first().size,
            strategy = chunker.strategy,
            chunks = chunks.zip(vectors) { c, v ->
                IndexedChunk(
                    chunk_id = c.chunkId,
                    source = c.source,
                    title = c.title,
                    section = c.section,
                    strategy = c.strategy,
                    char_start = c.charStart,
                    char_end = c.charEnd,
                    text = c.text,
                    embedding = v.toList(),
                )
            },
        )
        val path = IndexStore.indexPath(Config.outputDir(), chunker.strategy)
        IndexStore.save(index, path)
        println("  индекс: $path (${Files.size(path) / 1024} КБ, dim=${index.dim})")
    }
}

private fun compare() {
    val outputDir = Config.outputDir()
    val indexA = IndexStore.load(IndexStore.indexPath(outputDir, "fixed"))
    val indexB = IndexStore.load(IndexStore.indexPath(outputDir, "structure"))
    val judge = Config.deepSeekKeyOrNull()?.let { DeepSeekJudge(it) }
    if (judge == null) println("DEEPSEEK_API_KEY не найден — сравнение без LLM-судьи.")
    println("== Шаг 5: сравнение стратегий ==")
    Comparison.run(indexA, indexB, OllamaEmbedder(), judge, Config.topK(), outputDir.resolve("comparison.md"))
}

private fun search(query: String) {
    require(query.isNotBlank()) { "Пустой запрос: ./run.sh search «текст запроса»" }
    val embedder = OllamaEmbedder()
    val qVec = embedder.embedQuery(query)
    listOf("fixed", "structure").forEach { strategy ->
        val index = IndexStore.load(IndexStore.indexPath(Config.outputDir(), strategy))
        println("\n=== $strategy ===")
        Search.topK(index, qVec, Config.topK()).forEach { hit ->
            val section = hit.chunk.section?.let { ", раздел «$it»" } ?: ""
            println("[%.3f] %s — %s%s".format(hit.score, hit.chunk.chunk_id, hit.chunk.title, section))
            println("  " + hit.chunk.text.replace('\n', ' ').take(180) + "…")
        }
    }
}
