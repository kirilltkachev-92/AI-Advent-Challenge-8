import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * День 16 — подключение к публичному MCP-серверу и вызов инструментов.
 *
 *   1) устанавливаем MCP-соединение (initialize + initialized);
 *   2) получаем список инструментов (tools/list);
 *   3) интерактивно вызываем инструменты (tools/call).
 *
 * Сервер по умолчанию — публичный MCP Microsoft Learn (документация Microsoft/Azure/.NET),
 * ключа не требует. Сменить сервер можно через .env (MCP_SERVER_URL / MCP_AUTH_TOKEN).
 *
 * Команды REPL:
 *   list                      — показать список инструментов
 *   docs <запрос>             — поиск по документации (microsoft_docs_search)
 *   call <tool> <json-args>   — вызвать любой инструмент напрямую
 *   help                      — помощь
 *   quit | exit               — выход
 *
 * Команда docs «заточена» под Microsoft Learn; list и call универсальны и работают
 * с любым MCP-сервером на Streamable HTTP.
 */
private val json = Json { ignoreUnknownKeys = true }

fun main() {
    Config.loadDotEnv()

    val url = Config.serverUrl()
    val client = McpClient(url, Config.authToken())

    println("→ Подключаюсь к MCP-серверу: $url")
    val server = try {
        client.connect()
    } catch (e: Exception) {
        System.err.println("✗ Не удалось установить соединение: ${e.message}")
        kotlin.system.exitProcess(1)
    }
    println("✓ Соединение установлено: ${server.name} ${server.version} (протокол ${server.protocolVersion})")

    val tools = try {
        client.listTools()
    } catch (e: Exception) {
        System.err.println("✗ Не удалось получить список инструментов: ${e.message}")
        kotlin.system.exitProcess(1)
    }
    printTools(tools)
    printHelp()

    // REPL: читаем команды, пока есть stdin и не получили quit/exit.
    while (true) {
        print("\nmcp> ")
        val line = readlnOrNull()?.trim() ?: break
        if (line.isEmpty()) continue

        val parts = line.split(" ", limit = 2)
        val command = parts[0].lowercase()
        val rest = parts.getOrElse(1) { "" }.trim()

        when (command) {
            "quit", "exit" -> break
            "help" -> printHelp()
            "list" -> printTools(client.listTools())
            "docs" -> {
                if (rest.isEmpty()) {
                    println("Использование: docs <запрос>   напр. docs azure functions scaling")
                } else {
                    searchDocs(client, rest)
                }
            }
            "call" -> {
                val (tool, argsRaw) = rest.split(" ", limit = 2).let {
                    it.getOrElse(0) { "" } to it.getOrElse(1) { "{}" }.trim()
                }
                if (tool.isEmpty()) {
                    println("Использование: call <tool> <json-аргументы>")
                } else {
                    val args = parseArgs(argsRaw)
                    if (args == null) {
                        println("✗ Аргументы должны быть JSON-объектом, например: call $tool {\"query\":\"...\"}")
                    } else {
                        invoke(client, tool, args)
                    }
                }
            }
            else -> println("Неизвестная команда «$command». Введите help.")
        }
    }
    println("Пока!")
}

/** Поиск по документации через microsoft_docs_search и аккуратный вывод результатов. */
private fun searchDocs(client: McpClient, query: String) {
    println("→ Ищу в документации: «$query»")
    val result = try {
        client.callTool("microsoft_docs_search", buildJsonObject { put("query", query) })
    } catch (e: Exception) {
        println("✗ Ошибка вызова: ${e.message}")
        return
    }
    if (result.isError) {
        println("⚠ сервер вернул ошибку:")
        println(result.text.ifBlank { "(пустой ответ)" })
        return
    }
    printDocResults(result.text)
}

/**
 * microsoft_docs_search кладёт в текст JSON вида {"results":[{title, content, contentUrl}]}.
 * Разбираем его и печатаем заголовок + ссылку + короткий фрагмент. Если формат другой —
 * просто печатаем как есть.
 */
private fun printDocResults(raw: String) {
    val results = try {
        json.parseToJsonElement(raw).jsonObject["results"]?.jsonArray
    } catch (_: Exception) {
        null
    }
    if (results.isNullOrEmpty()) {
        println(raw.ifBlank { "(пустой ответ)" })
        return
    }
    println("\n✓ Найдено результатов: ${results.size}")
    println("─".repeat(60))
    results.forEachIndexed { index, element ->
        val obj = element.jsonObject
        val title = obj["title"]?.jsonPrimitive?.content ?: "(без заголовка)"
        val link = obj["contentUrl"]?.jsonPrimitive?.content ?: obj["url"]?.jsonPrimitive?.content
        val snippet = obj["content"]?.jsonPrimitive?.content
            ?.replace("\n", " ")?.trim()?.take(220)
        println("${index + 1}. $title")
        if (!link.isNullOrEmpty()) println("   $link")
        if (!snippet.isNullOrEmpty()) println("   $snippet…")
    }
    println("─".repeat(60))
}

/** Вызывает произвольный инструмент (команда call) и печатает текстовый результат. */
private fun invoke(client: McpClient, tool: String, args: JsonObject) {
    println("→ Вызываю «$tool» $args")
    val result = try {
        client.callTool(tool, args)
    } catch (e: Exception) {
        println("✗ Ошибка вызова: ${e.message}")
        return
    }
    println("─".repeat(60))
    println(result.text.ifBlank { "(пустой ответ)" })
    println("─".repeat(60))
    if (result.isError) println("⚠ сервер пометил результат как ошибку (isError=true)")
}

/** Разбирает JSON-аргументы команды call; null, если это не JSON-объект. */
private fun parseArgs(raw: String): JsonObject? = try {
    json.parseToJsonElement(raw.ifBlank { "{}" }) as? JsonObject
} catch (_: Exception) {
    null
}

private fun printTools(tools: List<McpTool>) {
    if (tools.isEmpty()) {
        println("Сервер не вернул ни одного инструмента.")
        return
    }
    println("\n✓ Доступно инструментов: ${tools.size}")
    println("─".repeat(60))
    tools.forEachIndexed { index, tool ->
        println("${index + 1}. ${tool.name}")
        if (tool.description.isNotEmpty()) println("   ${tool.description}")
        if (tool.requiredParams.isNotEmpty()) {
            println("   обязательные параметры: ${tool.requiredParams.joinToString(", ")}")
        }
    }
    println("─".repeat(60))
}

private fun printHelp() {
    println(
        """
        |Команды:
        |  list                 список инструментов
        |  docs <запрос>        поиск по документации (microsoft_docs_search)
        |  call <tool> <json>   вызвать любой инструмент напрямую
        |  help                 эта справка
        |  quit | exit          выход
        |Пример: docs how do azure functions scale
        """.trimMargin(),
    )
}
