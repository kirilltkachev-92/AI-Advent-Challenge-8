import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

/**
 * День 27. Интеграция локальной LLM в приложение.
 *
 * CLI-утилита «чат по истории Telegram-чата»: разбирает HTML-экспорт чата
 * AI Advent Challenge #8, строит локальный семантический индекс (/api/embed)
 * и отвечает на вопросы о переписке через локальную qwen2.5:14b (/api/chat) —
 * с историей диалога, так что работают уточняющие вопросы. Всё на localhost,
 * без единого облачного запроса.
 */

private const val HISTORY_LIMIT = 12 // последних реплик диалога в промпте
private const val TOP_K = 5          // фрагментов чата в контексте ответа

private fun systemPrompt(hits: List<Retriever.Hit>): String = buildString {
    appendLine(
        "Ты — ассистент по истории Telegram-чата «AI Advent Challenge #8». " +
            "Это чат ежедневного челленджа по ИИ; тьютор челленджа — Алексей Гладков, " +
            "задания публикует Mobile Developer Manager.",
    )
    appendLine(
        "Отвечай на вопросы пользователя по фрагментам переписки ниже. Опирайся только на них: " +
            "если ответа во фрагментах нет — так и скажи. Отвечай кратко, по-русски, " +
            "при возможности указывай, кто и когда это писал.",
    )
    appendLine()
    appendLine("Фрагменты переписки, найденные по вопросу:")
    hits.forEach { hit ->
        appendLine()
        appendLine("--- фрагмент (${hit.chunk.header}) ---")
        appendLine(hit.chunk.text)
    }
}

fun main() {
    Config.loadDotEnv()
    val client = OllamaClient()
    val chatModel = Config.chatModel()
    val embedModel = Config.embedModel()

    // --- Локальная LLM на месте --------------------------------------------
    val version = client.version() ?: run {
        System.err.println(
            "Ollama не отвечает на ${Config.ollamaBaseUrl()}.\n" +
                "Запустите сервер: ollama serve (или brew services start ollama)",
        )
        return
    }
    val local = client.localModels()
    listOf(chatModel, embedModel).forEach { model ->
        if (local.none { it == model || it.startsWith("$model:") }) {
            System.err.println("Модель $model не скачана. Выполните: ollama pull $model")
            return
        }
    }
    println("✓ Ollama $version на ${Config.ollamaBaseUrl()} — чат: $chatModel, эмбеддинги: $embedModel")

    // --- Экспорт чата → сообщения → векторный индекс ------------------------
    val exportPath = Config.exportPath()
    val messages = TelegramExport.parse(exportPath)
    if (messages.isEmpty()) {
        System.err.println("В $exportPath не нашлось сообщений — это точно HTML-экспорт Telegram?")
        return
    }
    val days = messages.map { it.date }.distinct()
    println(
        "✓ Экспорт: $exportPath — ${messages.size} сообщений, " +
            "${messages.map { it.author }.distinct().size} участников, дни: ${days.joinToString()}",
    )

    val retriever = Retriever(client)
    val indexPath = Config.outputDir().also { it.createDirectories() }.resolve("index.json")
    val startedAt = System.currentTimeMillis()
    val rebuilt = retriever.buildIndex(messages, exportPath, indexPath)
    val indexNote = if (rebuilt) "построен за ${System.currentTimeMillis() - startedAt} мс" else "из кеша"
    println("✓ Индекс: ${retriever.size} фрагментов ($indexNote, $indexPath)")

    // --- Диалог с историей ---------------------------------------------------
    println()
    println("Спрашивайте про чат (пустая строка или /exit — выход, /clear — забыть диалог, /history — история):")

    val history = mutableListOf<ChatMessage>()          // передаётся модели
    val transcript = mutableListOf<Pair<String, String>>() // пишется в session.md

    while (true) {
        print("\nвы> ")
        val question = readLine()?.trim() ?: break
        // При запуске с перенаправленным вводом (demo.sh) терминал не эхо-ит
        // ввод — печатаем вопрос сами, чтобы в записи было видно, что спросили.
        if (System.console() == null && question.isNotEmpty()) println(question)
        when {
            question.isEmpty() || question == "/exit" -> break
            question == "/clear" -> {
                history.clear()
                println("История диалога очищена.")
                continue
            }
            question == "/history" -> {
                if (history.isEmpty()) println("История пуста.")
                history.forEach { println("[${it.role}] ${it.content}") }
                continue
            }
        }

        val hits = retriever.search(question, TOP_K)
        val prompt = listOf(ChatMessage("system", systemPrompt(hits))) +
            history.takeLast(HISTORY_LIMIT) +
            ChatMessage("user", question)
        val result = client.chat(chatModel, prompt)

        println()
        println(result.answer)
        println()
        println(
            "· фрагменты: ${hits.joinToString { "%s (%.2f)".format(it.chunk.header, it.score) }}\n" +
                "· ${result.promptTokens} ткн промпта, ${result.answerTokens} ткн ответа за " +
                "${result.totalMs} мс (%.1f ток/с), история: ${history.size / 2} обменов".format(result.tokensPerSec),
        )

        history += ChatMessage("user", question)
        history += ChatMessage("assistant", result.answer)
        transcript += question to result.answer
    }

    // --- Транскрипт сессии ----------------------------------------------------
    if (transcript.isNotEmpty()) {
        val sessionPath = Config.outputDir().resolve("session.md")
        sessionPath.writeText(
            buildString {
                appendLine("# День 27. Сессия чата по истории Telegram")
                appendLine()
                appendLine("- Модель: `$chatModel` + `$embedModel`, локально на `${Config.ollamaBaseUrl()}`")
                appendLine("- База: `$exportPath`, ${messages.size} сообщений, ${retriever.size} фрагментов")
                transcript.forEach { (q, a) ->
                    appendLine()
                    appendLine("## вы> $q")
                    appendLine()
                    appendLine(a)
                }
            },
        )
        println("\nТранскрипт сессии: $sessionPath")
    }
    println("Пока!")
}
