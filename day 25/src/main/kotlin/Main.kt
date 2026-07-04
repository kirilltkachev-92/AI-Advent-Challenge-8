import java.nio.file.Files
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/**
 * День 25 — мини-чат с RAG + памятью (production-like).
 *
 * CLI-чат: хранит историю диалога (с персистом на диск), на каждый вопрос ищет
 * контекст в базе через RAG (воронка Дня 23 + правила Дня 24), всегда выводит
 * источники, ведёт «память задачи» (цель, уточнения, ограничения, термины).
 *
 * Команды:
 *   ./run.sh              — интерактивный чат (index построится сам при отсутствии)
 *   ./run.sh chat         — то же самое
 *   ./run.sh scenarios    — 2 длинных сценария по 12 сообщений → output/scenarios.md
 *   ./run.sh index        — только построить индекс из docs/
 * Внутри чата: /state — память задачи, /history — история, /new — новая сессия, /exit — выход.
 */
fun main(args: Array<String>) {
    Config.loadDotEnv()
    when (args.firstOrNull() ?: "chat") {
        "index" -> buildIndex()
        "chat" -> { ensureIndex(); chat() }
        "scenarios" -> { ensureIndex(); scenarios() }
        else -> println("Неизвестная команда. Доступно: chat | scenarios | index")
    }
}

private fun indexPath() = IndexStore.indexPath(Config.outputDir(), Config.ragStrategy())

private fun ensureIndex() { if (!Files.exists(indexPath())) buildIndex() }

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

private fun makeSession(persist: Boolean): ChatSession {
    val llm = DeepSeekClient()
    val agent = RagAgent(llm, OllamaEmbedder(), IndexStore.load(indexPath()), Reranker(llm))
    return ChatSession(agent, TaskStateUpdater(llm), if (persist) Config.sessionFile() else null)
}

/** Интерактивный мини-чат (CLI). */
private fun chat() {
    val session = makeSession(persist = true)
    session.load()
    if (session.history.isNotEmpty()) {
        println("(продолжаем сессию: ${session.history.size / 2} ходов в истории; /new — начать заново)")
    }
    println("Мини-чат по статье GPT-3. Команды: /state, /history, /new, /exit")
    while (true) {
        print("\n> ")
        val line = readLine()?.trim() ?: break
        // Введённое сообщение печатаем явно: при вводе через пайп (demo.sh) терминал
        // его не эхо-отображает, и без этого в транскрипте не видно реплик пользователя.
        println("\nВы: $line")
        when {
            line.isEmpty() -> continue
            line == "/exit" -> break
            line == "/new" -> { session.reset(); println("(новая сессия)") ; continue }
            line == "/state" -> { println(session.state.render()); continue }
            line == "/history" -> {
                session.history.forEach { println("${if (it.role == "user") "Вы" else "Ассистент"}: ${it.text}") }
                continue
            }
        }
        val a = session.ask(line)
        println()
        if (!a.canAnswer) {
            println("Ассистент: ${a.clarification}")
            continue
        }
        println("Ассистент${if (a.meta) " (из памяти диалога)" else ""}: ${a.answer.trim()}")
        if (a.sources.isNotEmpty()) {
            println()
            println("Источники:")
            a.sources.forEach { println("  - ${it.chunkId} — «${it.section}»") }
        }
    }
    println("Пока! Сессия сохранена в ${Config.sessionFile()}.")
}

/** Два длинных сценария по 12 сообщений (проверка дня). */
private fun scenarios() {
    println("== Два длинных сценария через ChatSession ==")
    Scenarios.run(
        makeSession = { makeSession(persist = false) },
        judgeLlm = DeepSeekClient(),
        reportPath = Config.outputDir().resolve("scenarios.md"),
    )
}
