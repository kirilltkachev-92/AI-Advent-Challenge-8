import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/**
 * День 17 — приложение-агент, который вызывает СВОЙ MCP-инструмент.
 *
 * Что происходит при запуске:
 *   1) поднимаем встроенный MCP-сервер вокруг Open-Meteo (если не задан внешний MCP_SERVER_URL);
 *   2) подключаемся к нему как MCP-клиент (initialize → tools/list);
 *   3) поднимаем агента на DeepSeek и отдаём ему список инструментов как функции;
 *   4) в REPL пользователь спрашивает про погоду — агент САМ зовёт инструмент и
 *      использует результат в ответе.
 *
 * Команды REPL:
 *   <любой вопрос про погоду>   — агент решает, звать ли инструмент (главный сценарий)
 *   tool <name> <json>          — прямой вызов MCP-инструмента (демо без LLM)
 *   list                        — список инструментов сервера
 *   help                        — помощь
 *   quit | exit                 — выход
 */
private val json = Json { ignoreUnknownKeys = true }

fun main() {
    Config.loadDotEnv()

    // 1) MCP-сервер: внешний (VPS) или встроенный локальный.
    var embedded: McpServer? = null
    if (!Config.hasExternalServer()) {
        embedded = McpServer.weatherServer().start(Config.port(), Config.MCP_PATH)
        println("✓ Встроенный MCP-сервер поднят: ${Config.serverUrl()}")
    } else {
        println("→ Использую внешний MCP-сервер: ${Config.serverUrl()}")
    }

    // 2) Подключаемся к серверу как MCP-клиент.
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

    // 3) Агент на DeepSeek с этими инструментами.
    val apiKey = runCatching { Config.apiKey() }.getOrNull()
    val agent = apiKey?.let { WeatherAgent(client, tools, it) }
    if (agent == null) {
        println("\n⚠ DEEPSEEK_API_KEY не найден — агент-LLM выключен.")
        println("  Инструмент всё равно можно вызвать напрямую: tool get_weather {\"city\":\"Берлин\"}")
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
                if (agent == null) {
                    println("Агент-LLM выключен. Используйте: tool <name> <json> (см. help).")
                } else {
                    runAgent(agent, line)
                }
            }
        }
    }

    embedded?.stop()
    println("Пока!")
}

/** Главный сценарий: агент сам решает звать инструмент и использует результат. */
private fun runAgent(agent: WeatherAgent, message: String) {
    println("→ Агент думает…")
    val answer = try {
        agent.ask(message)
    } catch (e: Exception) {
        println("✗ Ошибка агента: ${e.message}")
        return
    }
    // Показываем, что агент реально ходил в MCP-инструмент.
    if (answer.toolCalls.isEmpty()) {
        println("(агент ответил без вызова инструментов)")
    } else {
        answer.toolCalls.forEach { call ->
            println("  ⚙ MCP-вызов: ${call.tool} ${call.arguments}")
            println("    ← ${call.result}")
        }
    }
    println("─".repeat(60))
    println(answer.text.ifBlank { "(пустой ответ)" })
    println("─".repeat(60))
}

/** Прямой вызов инструмента, как в Дне 16: tool <name> <json-аргументы>. */
private fun directCall(client: McpClient, rest: String) {
    val (tool, argsRaw) = rest.split(" ", limit = 2).let {
        it.getOrElse(0) { "" } to it.getOrElse(1) { "{}" }.trim()
    }
    if (tool.isEmpty()) {
        println("Использование: tool <name> <json>, напр.: tool get_weather {\"city\":\"Берлин\"}")
        return
    }
    val args = runCatching { json.parseToJsonElement(argsRaw.ifBlank { "{}" }).jsonObject }
        .getOrNull()
    if (args == null) {
        println("✗ Аргументы должны быть JSON-объектом, напр.: tool $tool {\"city\":\"Берлин\"}")
        return
    }
    println("→ Вызываю «$tool» $args")
    val result = runCatching { client.callTool(tool, args) }.getOrElse {
        println("✗ Ошибка вызова: ${it.message}")
        return
    }
    println("─".repeat(60))
    println(result.text.ifBlank { "(пустой ответ)" })
    println("─".repeat(60))
    if (result.isError) println("⚠ сервер пометил результат как ошибку (isError=true)")
}

private fun printTools(tools: List<McpTool>) {
    if (tools.isEmpty()) {
        println("Сервер не вернул ни одного инструмента.")
        return
    }
    println("\n✓ Зарегистрировано инструментов: ${tools.size}")
    println("─".repeat(60))
    tools.forEachIndexed { index, tool ->
        println("${index + 1}. ${tool.name} — ${tool.description}")
        val required = tool.inputSchema["required"]
        if (required != null) println("   обязательные параметры: $required")
    }
    println("─".repeat(60))
}

private fun printHelp(agentOn: Boolean) {
    val agentLine = if (agentOn) {
        "|  <вопрос про погоду>     агент сам решит, звать ли инструмент (главный сценарий)\n" +
            "|                          напр.: Какая погода в Берлине?"
    } else {
        "|  (агент-LLM выключен — нет DEEPSEEK_API_KEY)"
    }
    println(
        """
        |Команды:
        $agentLine
        |  tool <name> <json>       прямой вызов MCP-инструмента
        |                          напр.: tool get_weather {"city":"Токио"}
        |  list                     список инструментов
        |  help                     эта справка
        |  quit | exit              выход
        """.trimMargin(),
    )
}
