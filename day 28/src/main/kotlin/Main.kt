import java.security.MessageDigest
import java.time.LocalDateTime
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.readBytes

/**
 * День 28. Локальная LLM + RAG.
 *
 * RAG-пайплайн Недели 5 (чанки → nomic-embed-text → JSON-индекс → top-K поиск),
 * корпус — ВЕСЬ чат AI Advent Challenge #8 (31.05–08.07, ~3700 сообщений).
 * Retrieval локальный, генерация — локальная qwen2.5:14b; для сравнения тот же
 * промпт уходит облачному DeepSeek (если есть ключ). Оцениваем качество
 * (слепой судья), скорость и стабильность (N прогонов каждого вопроса).
 *
 * ASK="вопрос" ./run.sh — одиночный вопрос полностью локально, с источниками.
 */
fun main() {
    Config.loadDotEnv()
    val ollama = OllamaClient()
    val chatModel = Config.chatModel()
    val embedModel = Config.embedModel()

    // --- Локальная сторона на месте -----------------------------------------
    val version = ollama.version() ?: run {
        System.err.println(
            "Ollama не отвечает на ${Config.ollamaBaseUrl()}.\n" +
                "Запустите сервер: ollama serve (или brew services start ollama)",
        )
        return
    }
    val local = ollama.localModels()
    listOf(chatModel, embedModel).forEach { model ->
        if (local.none { it == model || it.startsWith("$model:") }) {
            System.err.println("Модель $model не скачана. Выполните: ollama pull $model")
            return
        }
    }
    println("✓ Ollama $version на ${Config.ollamaBaseUrl()} — генерация: $chatModel, эмбеддинги: $embedModel")

    // --- Корпус: весь чат челленджа ------------------------------------------
    val dataDir = Config.dataDir()
    val messages = TelegramExport.parseDir(dataDir)
    if (messages.isEmpty()) {
        System.err.println("В $dataDir не нашлось сообщений — нужны messages*.html из экспорта Telegram.")
        return
    }
    println(
        "✓ Корпус: ${dataDir.listDirectoryEntries("messages*.html").size} файлов экспорта — " +
            "${messages.size} сообщений, ${messages.map { it.author }.distinct().size} участников, " +
            "${messages.first().date} → ${messages.last().date}",
    )

    // --- Индекс в формате Недели 5 (пересчёт только при смене корпуса) --------
    val embedder = OllamaEmbedder()
    val indexPath = Config.outputDir().also { it.createDirectories() }.resolve("index-chat.json")
    val corpusSha = corpusSha(dataDir)
    val index = if (indexPath.exists()) {
        runCatching { IndexStore.load(indexPath) }.getOrNull()
            ?.takeIf { it.corpus_sha == corpusSha && it.embed_model == embedModel }
    } else {
        null
    } ?: buildIndex(messages, embedder, corpusSha, indexPath)
    println("✓ Индекс: ${index.chunks.size} чанков, dim=${index.dim}, стратегия `${index.strategy}` ($indexPath)")

    // --- Режим одиночного вопроса (полностью локально) ------------------------
    System.getenv("ASK")?.takeIf { it.isNotBlank() }?.let { question ->
        println("\nвопрос> $question")
        val hits = Search.topK(index, embedder.embedQuery(question), question, Config.topK())
        val fragments = Search.expand(hits, messages)
        val result = ollama.chat(chatModel, Comparison.systemPrompt(), Comparison.userPrompt(question, fragments))
        println("\n${result.answer}")
        println("\n· источники: ${hits.joinToString { "${it.chunk.chunk_id} (${it.chunk.section}, %.2f)".format(it.score) }}")
        println("· ${result.answerTokens} ткн за ${result.totalMs} мс (%.1f ток/с)".format(result.tokensPerSec))
        return
    }

    // --- Сравнение: локальная генерация против облачной ------------------------
    val key = Config.deepSeekKeyOrNull()
    val cloud = key?.let { DeepSeekClient(it) }
    val judge = key?.let { DeepSeekClient(it) }
    if (cloud == null) {
        println("! DEEPSEEK_API_KEY не найден — облачная сторона и судья пропущены, прогон полностью локальный.")
    }
    Comparison.run(
        index = index,
        messages = messages,
        embedder = embedder,
        local = ollama,
        localModel = chatModel,
        cloud = cloud,
        judge = judge,
        runs = Config.stabilityRuns(),
        reportPath = Config.outputDir().resolve("report.md"),
    )
}

private fun buildIndex(
    messages: List<TgMessage>,
    embedder: OllamaEmbedder,
    corpusSha: String,
    indexPath: java.nio.file.Path,
): DocumentIndex {
    val chunks = Chunking.perMessage(messages)
    println("  чанков: ${chunks.size} (message-level: одно сообщение = один вектор), эмбеддинг…")
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

private fun corpusSha(dataDir: java.nio.file.Path): String {
    val digest = MessageDigest.getInstance("SHA-256")
    dataDir.listDirectoryEntries("messages*.html").sortedBy { it.fileName.toString() }
        .forEach { digest.update(it.readBytes()) }
    return digest.digest().joinToString("") { "%02x".format(it) }
}
