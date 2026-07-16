import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * День 31 — ассистент разработчика.
 *
 * Режимы:
 *   repl   (по умолчанию) — интерактивная консоль с командой /help;
 *   index  — только построить индекс документации;
 *   report — прогнать чеклист задания без интерактива → output/report.md.
 */
fun main(args: Array<String>) {
    Config.loadDotEnv()
    val mode = args.firstOrNull() ?: "repl"

    val embedder = OllamaEmbedder()
    val repoRoot = GitMcp.repoRoot()
    val index = Indexer.buildOrLoad(repoRoot, embedder)
    if (mode == "index") return

    // Свой MCP-сервер с git-инструментами — и подключение к нему по-настоящему,
    // через HTTP-транспорт и рукопожатие initialize, как к любому чужому серверу.
    val server = GitMcp.server(repoRoot).start(Config.mcpPort(), Config.MCP_PATH)
    try {
        val mcp = McpClient(Config.mcpUrl())
        val info = mcp.connect()
        println("→ MCP: ${info.name} ${info.version} (протокол ${info.protocolVersion}) на ${Config.mcpUrl()}")
        val tools = mcp.listTools()
        println("→ Инструменты: ${tools.joinToString(", ") { it.name }}")

        val apiKey = Config.deepSeekApiKey()
            ?: error("Нет DEEPSEEK_API_KEY: положите ключ в .env (ищется и в ../day 25/.env)")
        val assistant = Assistant(mcp, tools, index, embedder, apiKey)

        when (mode) {
            "report" -> Report.run(assistant, mcp, tools, index, repoRoot)
            "repl" -> repl(assistant, mcp)
            else -> error("Неизвестный режим «$mode» (repl | index | report)")
        }
    } finally {
        server.stop()
    }
}

private fun repl(assistant: Assistant, mcp: McpClient) {
    println()
    println("Ассистент разработчика AI Advent Challenge. Команды:")
    println("  /help <вопрос>   — вопрос о проекте (RAG по документации + git через MCP)")
    println("  /branch /status /files [dir] /diff [путь] /log — git-инструменты напрямую")
    println("  /quit            — выход")
    println()

    while (true) {
        print("dev> ")
        val line = readLine()?.trim() ?: break
        if (System.console() == null) println(line) // вход из пайпа (demo.sh) — показать команду
        if (line.isEmpty()) continue
        val (command, rest) = line.split(" ", limit = 2).let { it[0] to (it.getOrNull(1)?.trim() ?: "") }

        try {
            when (command) {
                "/quit", "/exit" -> return
                "/branch" -> println(mcp.callTool("git_branch", buildJsonObject {}).text)
                "/status" -> println(mcp.callTool("git_status", buildJsonObject {}).text)
                "/log" -> println(mcp.callTool("git_log", buildJsonObject {}).text)
                "/files" -> println(
                    mcp.callTool("git_files", buildJsonObject { if (rest.isNotEmpty()) put("dir", rest) }).text,
                )
                "/diff" -> println(
                    mcp.callTool("git_diff", buildJsonObject { if (rest.isNotEmpty()) put("path", rest) }).text,
                )
                "/help" -> {
                    if (rest.isEmpty()) {
                        println("Задайте вопрос: /help как устроен день 30?")
                    } else {
                        printAnswer(assistant.help(rest))
                    }
                }
                else -> printAnswer(assistant.help(line)) // без команды — тоже вопрос о проекте
            }
        } catch (e: Exception) {
            println("✗ ${e.message}")
        }
        println()
    }
}

private fun printAnswer(answer: HelpAnswer) {
    answer.toolCalls.forEach { println("  ⚙ MCP ${it.tool}(${it.arguments}) → ${it.result.lineSequence().first()}") }
    println(answer.text)
    val sources = answer.sources.map { it.chunk.source }.distinct()
    println("  📄 источники: ${sources.joinToString(", ").ifEmpty { "—" }}")
}
