import java.nio.file.Files
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/**
 * День 24 — цитаты, источники и анти-галлюцинации.
 *
 * Ответ агента ОБЯЗАТЕЛЬНО содержит: сам ответ, список источников (chunk_id + section)
 * и дословные цитаты из чанков. Дословность и валидность источников проверяются кодом,
 * совпадение смысла ответа с цитатами — слепым судьёй. При релевантности ниже порога
 * агент обязан сказать «не знаю» и попросить уточнение.
 *
 * Команды:
 *   ./run.sh              — весь пайплайн: index (если индекса нет) + verify
 *   ./run.sh index        — построить индекс из docs/
 *   ./run.sh ask «вопрос» — один вопрос: ответ + источники + цитаты + проверки
 *   ./run.sh verify       — 10 контрольных + 3 «мимо базы» → output/verification.md
 */
fun main(args: Array<String>) {
    Config.loadDotEnv()
    when (args.firstOrNull() ?: "all") {
        "index" -> buildIndex()
        "ask" -> ask(args.drop(1).joinToString(" "))
        "verify" -> verify()
        "all" -> { if (!Files.exists(indexPath())) buildIndex(); verify() }
        else -> println("Неизвестная команда. Доступно: index | ask «вопрос» | verify")
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

private fun ask(question: String) {
    require(question.isNotBlank()) { "Пустой вопрос: ./run.sh ask «текст вопроса»" }
    val agent = makeAgent()
    println("== Вопрос: $question ==\n")
    val a = agent.ask(question)
    println("rewrite: ${a.trace.rewrittenQuery}")
    println("воронка: ${a.trace.candidatesBefore} → порог косинуса → ${a.trace.afterSimFilter} → реранк → ${a.trace.afterRerank} → в контекст ${a.trace.final.size}")
    println()
    if (!a.canAnswer) {
        println("ОТКАЗ («не знаю»): ${a.clarification}")
        return
    }
    println("Ответ:")
    println(a.answer.trim())
    println()
    println("Источники (source + section/chunk_id):")
    a.sources.forEach { println("  - ${it.chunkId} — «${it.section}»") }
    println()
    val check = Validator.validate(a)
    println("Цитаты (✓ = дословно найдена в чанке):")
    check.quoteChecks.forEach { qc ->
        val m = if (qc.verbatim) "✓" else "✗"
        println("  $m [${qc.quote.chunkId}] «${qc.quote.quote}»")
    }
    println()
    println("Проверки кода: источники валидны: ${if (check.sourcesValid) "да" else "НЕТ"}, " +
        "дословных цитат ${check.quotesVerbatim}/${check.quoteChecks.size}")
}

private fun verify() {
    println("== Проверка на ${ControlSet.QUESTIONS.size} контрольных + ${Verification.OFF_BASE.size} вопросах мимо базы ==")
    Verification.run(makeAgent(), DeepSeekClient(), Config.outputDir().resolve("verification.md"))
}
