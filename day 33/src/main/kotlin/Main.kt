import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * День 33 — ассистент поддержки пользователей.
 *
 * Режимы:
 *   repl   (по умолчанию) — консоль оператора поддержки;
 *   index  — только построить индекс базы знаний;
 *   report — прогнать чеклист задания без интерактива → output/report.md.
 */
fun main(args: Array<String>) {
    Config.loadDotEnv()
    val mode = args.firstOrNull() ?: "repl"

    val embedder = OllamaEmbedder()
    val index = Indexer.buildOrLoad(embedder)
    if (mode == "index") return

    // Отчёт всегда стартует с исходной CRM — иначе заметки прошлых прогонов копятся.
    if (mode == "report") java.nio.file.Files.deleteIfExists(Config.crmStatePath())

    // Свой MCP-сервер с CRM-инструментами — и подключение к нему по-настоящему,
    // через HTTP-транспорт и рукопожатие initialize, как к любому чужому серверу.
    val store = CrmStore()
    val server = CrmMcp.server(store).start(Config.mcpPort(), Config.MCP_PATH)
    try {
        val mcp = McpClient(Config.mcpUrl())
        val info = mcp.connect()
        println("→ MCP: ${info.name} ${info.version} (протокол ${info.protocolVersion}) на ${Config.mcpUrl()}")
        val tools = mcp.listTools()
        println("→ Инструменты CRM: ${tools.joinToString(", ") { it.name }}")

        val apiKey = Config.deepSeekApiKey()
            ?: error("Нет DEEPSEEK_API_KEY: положите ключ в .env (ищется и в ../day 25/.env)")
        val agent = SupportAgent(mcp, tools, index, embedder, apiKey)

        when (mode) {
            "report" -> Report.run(agent, mcp, tools, index)
            "repl" -> repl(agent, mcp)
            else -> error("Неизвестный режим «$mode» (repl | index | report)")
        }
    } finally {
        server.stop()
    }
}

private fun repl(agent: SupportAgent, mcp: McpClient) {
    println()
    println("Поддержка «Планёрки» — консоль оператора. Команды:")
    println("  /user <email|имя>  — выбрать пользователя (контекст обращения из CRM)")
    println("  /ticket <id>       — сделать тикет активным (контекст тикета)")
    println("  /tickets           — тикеты выбранного пользователя")
    println("  /reset             — сбросить контекст")
    println("  <текст>            — вопрос пользователя ассистенту")
    println("  /quit              — выход")
    println()

    var userContext: String? = null
    var ticketContext: String? = null

    while (true) {
        print("support> ")
        val line = readLine()?.trim() ?: break
        if (System.console() == null) println(line) // вход из пайпа (demo.sh) — показать команду
        if (line.isEmpty()) continue
        val (command, rest) = line.split(" ", limit = 2).let { it[0] to (it.getOrNull(1)?.trim() ?: "") }

        try {
            when (command) {
                "/quit", "/exit" -> return
                "/reset" -> {
                    userContext = null
                    ticketContext = null
                    println("Контекст сброшен.")
                }
                "/user" -> {
                    if (rest.isEmpty()) { println("Кого ищем? /user marina@lumex.ru"); continue }
                    val result = mcp.callTool("find_user", buildJsonObject { put("query", rest) })
                    println(result.text)
                    if (!result.isError) {
                        userContext = result.text
                        ticketContext = null
                    }
                }
                "/ticket" -> {
                    if (rest.isEmpty()) { println("Какой тикет? /ticket T-1042"); continue }
                    val result = mcp.callTool("get_ticket", buildJsonObject { put("ticket_id", rest) })
                    println(result.text)
                    if (!result.isError) ticketContext = result.text
                }
                "/tickets" -> {
                    val userId = userContext?.lineSequence()?.first()?.substringBefore(" ·")?.trim()
                    if (userId == null) { println("Сначала выберите пользователя: /user <email|имя>"); continue }
                    println(mcp.callTool("list_tickets", buildJsonObject { put("user_id", userId) }).text)
                }
                else -> printAnswer(agent.answer(line, userContext, ticketContext))
            }
        } catch (e: Exception) {
            println("✗ ${e.message}")
        }
        println()
    }
}

private fun printAnswer(answer: SupportAnswer) {
    answer.toolCalls.forEach { println("  ⚙ MCP ${it.tool}(${it.arguments}) → ${it.result.lineSequence().first()}") }
    println(answer.text)
    val sources = answer.sources.map { it.chunk.source }.distinct()
    println("  📄 источники: ${sources.joinToString(", ").ifEmpty { "—" }}")
}
