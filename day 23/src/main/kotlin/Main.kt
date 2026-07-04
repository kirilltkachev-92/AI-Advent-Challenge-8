import java.nio.file.Files
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/**
 * День 23 — реранкинг и фильтрация.
 *
 * К RAG Дня 22 добавлен второй этап после поиска: query rewrite, порог косинусной
 * близости и LLM-реранкер (cross-encoder-паттерн) с порогом отсечения. Настраиваются
 * top-K до (CANDIDATES_K) и после (FINAL_K) фильтрации и оба порога.
 *
 * Команды:
 *   ./run.sh              — весь пайплайн: index (если индекса нет) + eval
 *   ./run.sh index        — построить индекс из docs/
 *   ./run.sh ask «вопрос» — baseline и improved бок о бок
 *   ./run.sh eval         — 10 контрольных вопросов + судья → output/evaluation.md
 */
fun main(args: Array<String>) {
    Config.loadDotEnv()
    when (args.firstOrNull() ?: "all") {
        "index" -> buildIndex()
        "ask" -> ask(args.drop(1).joinToString(" "))
        "eval" -> evaluate()
        "all" -> { if (!Files.exists(indexPath())) buildIndex(); evaluate() }
        else -> println("Неизвестная команда. Доступно: index | ask «вопрос» | eval")
    }
}

private fun indexPath() = IndexStore.indexPath(Config.outputDir(), Config.ragStrategy())

private fun buildIndex() {
    val embedder = OllamaEmbedder()
    println("== Индексация (пайплайн Дня 21, стратегия «${Config.ragStrategy()}») ==")
    val corpus = DocumentLoader.loadCorpus(Config.docsDir())
    corpus.forEach { println("  ${it.title} — ${it.text.length} символов ≈ ${"%.0f".format(it.pagesEquivalent)} стр.") }

    val chunker: Chunker = when (Config.ragStrategy()) {
        "fixed" -> FixedSizeChunker(Config.chunkSize(), Config.chunkOverlap())
        else -> StructureChunker()
    }
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
                chunk_id = c.chunkId, source = c.source, title = c.title, section = c.section,
                strategy = c.strategy, char_start = c.charStart, char_end = c.charEnd,
                text = c.text, embedding = v.toList(),
            )
        },
    )
    IndexStore.save(index, indexPath())
    println("  индекс: ${indexPath()} (${Files.size(indexPath()) / 1024} КБ)")
}

private fun makeAgent(): RagAgent {
    val llm = DeepSeekClient()
    return RagAgent(llm, OllamaEmbedder(), IndexStore.load(indexPath()), QueryRewriter(llm), Reranker(llm))
}

/** Оба режима бок о бок — видно вклад rewrite + фильтрации + реранка. */
private fun ask(question: String) {
    require(question.isNotBlank()) { "Пустой вопрос: ./run.sh ask «текст вопроса»" }
    val agent = makeAgent()
    println("== Вопрос: $question ==\n")

    println("--- Baseline (без rewrite/фильтра, top-${Config.finalK()} по косинусу) ---")
    val base = agent.askBaseline(question)
    base.trace.final.forEach { println("  [cos ${"%.3f".format(it.hit.score)}] «${it.hit.chunk.section}»") }
    println()
    println(base.text.trim())

    println()
    println("--- Improved (rewrite → top-${Config.candidatesK()} → порог ${Config.simThreshold()} → реранк ≥${Config.rerankThreshold()} → top-${Config.finalK()}) ---")
    val impr = agent.askImproved(question)
    println("  rewrite: ${impr.trace.rewrittenQuery}")
    println("  кандидатов: ${impr.trace.candidatesBefore} → после порога косинуса: ${impr.trace.afterSimFilter} → после реранка: ${impr.trace.afterRerank}" +
        if (impr.trace.fallbackUsed) " (fallback)" else "")
    impr.trace.final.forEach { println("  [rerank ${it.rerankScore}, cos ${"%.3f".format(it.hit.score)}] «${it.hit.chunk.section}»") }
    println()
    println(impr.text.trim())
}

private fun evaluate() {
    println("== Сравнение baseline / improved на ${ControlSet.QUESTIONS.size} контрольных вопросах ==")
    Evaluation.run(makeAgent(), DeepSeekClient(), Config.outputDir().resolve("evaluation.md"))
}
