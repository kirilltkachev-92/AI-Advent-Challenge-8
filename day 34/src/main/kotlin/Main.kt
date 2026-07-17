/**
 * День 34 — ассистент для работы с файлами проекта.
 *
 * Режимы:
 *   repl   (по умолчанию) — консоль: задачи на уровне цели;
 *   reset  — только восстановить рабочую копию проекта из шаблона;
 *   report — прогнать сценарии задания без интерактива → output/report.md.
 */
fun main(args: Array<String>) {
    Config.loadDotEnv()
    val mode = args.firstOrNull() ?: "repl"

    // Рабочая копия проекта: отчёт всегда начинается с чистого состояния
    // (воспроизводимость), REPL создаёт её только если её ещё нет.
    val workDir = Config.projectWorkDir()
    if (mode == "report" || mode == "reset" || !java.nio.file.Files.exists(workDir)) {
        FileMcp.resetWorkDir(Config.projectTemplate(), workDir)
        println("→ Рабочая копия проекта восстановлена из шаблона: $workDir")
    }
    if (mode == "reset") return

    val fileMcp = FileMcp(workDir)
    val server = fileMcp.server().start(Config.mcpPort(), Config.MCP_PATH)
    try {
        val mcp = McpClient(Config.mcpUrl())
        val info = mcp.connect()
        println("→ MCP: ${info.name} ${info.version} (протокол ${info.protocolVersion}) на ${Config.mcpUrl()}")
        val tools = mcp.listTools()
        println("→ Инструменты: ${tools.joinToString(", ") { it.name }}")

        val apiKey = Config.deepSeekApiKey()
            ?: error("Нет DEEPSEEK_API_KEY: положите ключ в .env (ищется и в ../day 25/.env)")
        val agent = FileAgent(mcp, tools, apiKey)

        when (mode) {
            "report" -> Report.run(agent, tools, fileMcp)
            "repl" -> repl(agent, fileMcp)
            else -> error("Неизвестный режим «$mode» (repl | reset | report)")
        }
    } finally {
        server.stop()
    }
}

private fun repl(agent: FileAgent, fileMcp: FileMcp) {
    println()
    println("Ассистент по файлам проекта «Инфопанель» (${Config.projectWorkDir()}). Команды:")
    println("  <цель>   — задача ассистенту, например: обнови README под текущий код")
    println("  /diff    — все изменения файлов за сессию")
    println("  /reset   — восстановить проект из шаблона")
    println("  /quit    — выход")
    println()

    while (true) {
        print("files> ")
        val line = readLine()?.trim() ?: break
        if (System.console() == null) println(line) // вход из пайпа (demo.sh) — показать команду
        if (line.isEmpty()) continue

        try {
            when (line) {
                "/quit", "/exit" -> return
                "/reset" -> {
                    FileMcp.resetWorkDir(Config.projectTemplate(), Config.projectWorkDir())
                    fileMcp.changes.clear()
                    println("Проект восстановлен из шаблона.")
                }
                "/diff" -> {
                    if (fileMcp.changes.isEmpty()) println("Изменений за сессию не было.")
                    else fileMcp.changes.forEach { (path, diff) ->
                        println("### $path")
                        println(diff)
                        println()
                    }
                }
                else -> printResult(agent.run(line))
            }
        } catch (e: Exception) {
            println("✗ ${e.message}")
        }
        println()
    }
}

private fun printResult(result: TaskResult) {
    result.toolCalls.forEach {
        val firstLine = it.result.lineSequence().first().take(100)
        println("  ⚙ ${it.tool}(${it.arguments.take(120)}) → $firstLine")
    }
    println()
    println(result.text)
}
