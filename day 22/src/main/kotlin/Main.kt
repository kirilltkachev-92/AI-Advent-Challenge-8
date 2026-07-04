import java.nio.file.Files
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/**
 * День 22 — первый RAG-запрос.
 *
 * Функция дня: вопрос → поиск релевантных чанков → объединение с вопросом → запрос к LLM.
 * Агент имеет ДВА режима — с RAG и без — и сравнивается на 10 контрольных вопросах.
 *
 * Команды:
 *   ./run.sh              — весь пайплайн: index (если индекса нет) + eval
 *   ./run.sh index        — построить индекс из docs/
 *   ./run.sh ask «вопрос» — оба режима бок о бок
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
    println("== Индексация (как в Дне 21, стратегия «${Config.ragStrategy()}») ==")
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

private fun makeAgent(): RagAgent =
    RagAgent(DeepSeekClient(), OllamaEmbedder(), IndexStore.load(indexPath()))

/** Оба режима бок о бок — видно, что меняет RAG. */
private fun ask(question: String) {
    require(question.isNotBlank()) { "Пустой вопрос: ./run.sh ask «текст вопроса»" }
    val agent = makeAgent()
    println("== Вопрос: $question ==\n")
    println("--- Без RAG ---")
    println(agent.askPlain(question).text.trim())
    println()
    println("--- С RAG ---")
    val rag = agent.askRag(question)
    rag.retrieved.forEach { println("  [${"%.3f".format(it.score)}] «${it.chunk.section}» (${it.chunk.chunk_id})") }
    println()
    println(rag.text.trim())
}

private fun evaluate() {
    println("== Сравнение с RAG / без RAG на ${ControlSet.QUESTIONS.size} контрольных вопросах ==")
    Evaluation.run(makeAgent(), DeepSeekClient(), Config.outputDir().resolve("evaluation.md"))
}
