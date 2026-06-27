/**
 * День 19 — агент с КОМПОЗИЦИЕЙ MCP-инструментов.
 *
 * Один MCP-сервер отдаёт три инструмента (search → summarize → save_to_file).
 * Пользователь пишет обычный запрос («найди … и сохрани в файл»), а агент на DeepSeek
 * САМ выстраивает из инструментов цепочку (последовательность не захардкожена) и
 * передаёт результат каждого инструмента в следующий.
 *
 * Команды REPL:
 *   <запрос>     напр.: Найди статьи про Эрмитаж и сохрани конспект в файл hermitage.md
 *   tool <name> <json>   прямой вызов инструмента (демо без LLM)
 *   list                 список MCP-инструментов
 *   quit | exit          выход
 */
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

private val json = Json { ignoreUnknownKeys = true }

fun main() {
    Config.loadDotEnv()

    // 1) MCP-сервер: внешний (VPS) или встроенный локальный.
    var embedded: McpServer? = null
    if (!Config.hasExternalServer()) {
        val wiki = WikiApi(Config.wikiLang(), Config.wikiDelayMs())
        embedded = McpServer.compositionServer(
            search = wiki::search,
            extract = wiki::extract,
            saver = NoteSaver(Config.outputDir()),
            article = wiki::article,
        ).start(Config.port(), Config.MCP_PATH)
        println("✓ Встроенный MCP-сервер композиции поднят: ${Config.serverUrl()}")
    } else {
        println("→ Использую внешний MCP-сервер: ${Config.serverUrl()}")
    }

    // 2) Подключаемся как MCP-клиент.
    val client = McpClient(Config.serverUrl())
    val server = try {
        client.connect()
    } catch (e: Exception) {
        System.err.println("✗ Не удалось подключиться к MCP-серверу: ${e.message}")
        embedded?.stop()
        kotlin.system.exitProcess(1)
    }
    println("✓ Соединение с MCP установлено: ${server.name} ${server.version} (протокол ${server.protocolVersion})")

    val tools = client.listTools()
    printTools(tools)

    // 3) Агент-пайплайн на DeepSeek.
    val apiKey = runCatching { Config.apiKey() }.getOrNull()
    val agent = apiKey?.let { PipelineAgent(client, tools, it) }
    if (agent == null) {
        println("\n⚠ DEEPSEEK_API_KEY не найден — агент выключен.")
        println("  Цепочку можно прогнать вручную: tool search {\"query\":\"Эрмитаж\"} → tool summarize … → tool save_to_file …")
    }

    printHelp(agent != null)

    // 4) REPL.
    while (true) {
        print("\nyou> ")
        val line = readlnOrNull()?.trim() ?: break
        if (line.isEmpty()) continue
        val parts = line.split(" ", limit = 2)
        when (parts[0].lowercase()) {
            "quit", "exit" -> break
            "help" -> printHelp(agent != null)
            "list" -> printTools(client.listTools())
            "tool" -> directCall(client, parts.getOrElse(1) { "" }.trim())
            else -> {
                if (agent == null) println("Агент выключен. Используйте: tool <name> <json> (см. help).")
                else runAgent(agent, line)
            }
        }
    }

    embedded?.stop()
    println("Пока!")
}

/** Главный сценарий: агент сам строит цепочку инструментов под запрос пользователя. */
private fun runAgent(agent: PipelineAgent, message: String) {
    println("→ Агент строит цепочку…")
    val answer = try {
        agent.run(message)
    } catch (e: Exception) {
        println("✗ Ошибка агента: ${e.message}")
        return
    }
    if (answer.toolCalls.isEmpty()) {
        println("(агент ответил без вызова инструментов)")
    } else {
        println("Цепочка вызовов MCP:")
        answer.toolCalls.forEachIndexed { i, call ->
            println("  ${i + 1}. ⚙ ${call.tool} ${call.arguments}")
            println("       ← ${oneLine(call.result)}")
        }
    }
    println("─".repeat(60))
    println(answer.text.ifBlank { "(пустой ответ)" })
    println("─".repeat(60))
}

/** Прямой вызов инструмента: tool <name> <json>. */
private fun directCall(client: McpClient, rest: String) {
    val (tool, argsRaw) = rest.split(" ", limit = 2).let {
        it.getOrElse(0) { "" } to it.getOrElse(1) { "{}" }.trim()
    }
    if (tool.isEmpty()) {
        println("Использование: tool <name> <json>, напр.: tool search {\"query\":\"Эрмитаж\"}")
        return
    }
    val args = runCatching { json.parseToJsonElement(argsRaw.ifBlank { "{}" }).jsonObject }.getOrNull()
    if (args == null) {
        println("✗ Аргументы должны быть JSON-объектом.")
        return
    }
    val result = runCatching { client.callTool(tool, args) }.getOrElse {
        println("✗ Ошибка вызова: ${it.message}"); return
    }
    println("─".repeat(60))
    println(result.text.ifBlank { "(пустой ответ)" })
    println("─".repeat(60))
    if (result.isError) println("⚠ сервер пометил результат как ошибку (isError=true)")
}

private fun oneLine(s: String): String {
    val flat = s.replace(Regex("\\s+"), " ").trim()
    return if (flat.length <= 200) flat else flat.take(200) + "…"
}

private fun printTools(tools: List<McpTool>) {
    if (tools.isEmpty()) {
        println("Сервер не вернул ни одного инструмента.")
        return
    }
    println("\n✓ Зарегистрировано инструментов: ${tools.size}")
    println("─".repeat(60))
    tools.forEachIndexed { i, tool ->
        println("${i + 1}. ${tool.name} — ${tool.description}")
        tool.inputSchema["required"]?.let { println("   обязательные параметры: $it") }
    }
    println("─".repeat(60))
}

private fun printHelp(agentOn: Boolean) {
    val agentLine = if (agentOn) {
        "|  <запрос>                агент сам строит цепочку search→summarize→save_to_file\n" +
            "|                          напр.: Найди про Эрмитаж и сохрани конспект в hermitage.md"
    } else {
        "|  (агент выключен — нет DEEPSEEK_API_KEY)"
    }
    println(
        """
        |Команды:
        $agentLine
        |  tool <name> <json>       прямой вызов инструмента (демо без LLM)
        |  list                     список инструментов
        |  quit | exit              выход
        """.trimMargin(),
    )
}
