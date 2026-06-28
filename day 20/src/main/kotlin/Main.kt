/**
 * День 20 — ОРКЕСТРАЦИЯ нескольких MCP-серверов.
 *
 * Поднимаются ЧЕТЫРЕ MCP-сервера (research / weather / report / storage), каждый на своём порту.
 * Роутер ([McpRouter]) подключается ко всем сразу, сводит их инструменты в один список и хранит
 * таблицу маршрутизации tool → сервер. Агент на DeepSeek ([OrchestratorAgent]) из обычного
 * запроса САМ выбирает инструменты с РАЗНЫХ серверов и строит длинный флоу; роутер доставляет
 * каждый вызов нужному серверу.
 *
 * Команды REPL:
 *   <запрос>            напр.: Собери досье по Санкт-Петербургу (справка + погода) и сохрани в spb.md
 *   tool <name> <json>  прямой вызов инструмента через роутер (демо без LLM)
 *   list                инструменты, сгруппированные по серверам
 *   quit | exit         выход
 */
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

private val json = Json { ignoreUnknownKeys = true }

fun main() {
    Config.loadDotEnv()
    val specs = Config.servers()

    // 1) Поднимаем все MCP-серверы локально (внешний деплой не требуется — Алексей: локально ок).
    val embedded = Boot.startAllServers(specs)
    println("✓ Поднято MCP-серверов: ${embedded.size}")
    specs.forEach { println("   • ${it.name} → ${it.url}") }

    // 2) Роутер подключается ко ВСЕМ серверам и строит таблицу маршрутизации.
    val router = McpRouter()
    specs.forEach { spec ->
        try {
            val m = router.mount(spec.url)
            println("   ↳ подключён ${m.info.name} ${m.info.version}: инструменты ${m.tools.map { it.name }}")
        } catch (e: Exception) {
            System.err.println("✗ Не удалось подключить ${spec.name} (${spec.url}): ${e.message}")
        }
    }
    printTools(router)

    // 3) Агент-оркестратор на DeepSeek.
    val apiKey = runCatching { Config.apiKey() }.getOrNull()
    val agent = apiKey?.let { OrchestratorAgent(router, it) }
    if (agent == null) {
        println("\n⚠ DEEPSEEK_API_KEY не найден — агент выключен.")
        println("  Флоу можно прогнать вручную: tool wiki_search {\"query\":\"Санкт-Петербург\"} → … → tool save_to_file …")
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
            "list" -> printTools(router)
            "tool" -> directCall(router, parts.getOrElse(1) { "" }.trim())
            else -> {
                if (agent == null) println("Агент выключен. Используйте: tool <name> <json> (см. help).")
                else runAgent(router, agent, line)
            }
        }
    }

    embedded.forEach { it.stop() }
    println("Пока!")
}

/** Главный сценарий: агент сам строит длинный кросс-серверный флоу под запрос пользователя. */
private fun runAgent(router: McpRouter, agent: OrchestratorAgent, message: String) {
    println("→ Агент-оркестратор строит флоу…")
    val answer = try {
        agent.run(message)
    } catch (e: Exception) {
        println("✗ Ошибка агента: ${e.message}")
        return
    }
    if (answer.toolCalls.isEmpty()) {
        println("(агент ответил без вызова инструментов)")
    } else {
        val servers = answer.toolCalls.map { it.server }.distinct()
        println("Флоу вызовов MCP (серверов задействовано: ${servers.size} — ${servers.joinToString(", ")}):")
        answer.toolCalls.forEachIndexed { i, call ->
            println("  ${i + 1}. [${call.server}] ⚙ ${call.tool} ${call.arguments}")
            println("       ← ${oneLine(call.result)}")
        }
    }
    println("─".repeat(60))
    println(answer.text.ifBlank { "(пустой ответ)" })
    println("─".repeat(60))
}

/** Прямой вызов инструмента через роутер: tool <name> <json>. */
private fun directCall(router: McpRouter, rest: String) {
    val (tool, argsRaw) = rest.split(" ", limit = 2).let {
        it.getOrElse(0) { "" } to it.getOrElse(1) { "{}" }.trim()
    }
    if (tool.isEmpty()) {
        println("Использование: tool <name> <json>, напр.: tool wiki_search {\"query\":\"Санкт-Петербург\"}")
        return
    }
    val args = runCatching { json.parseToJsonElement(argsRaw.ifBlank { "{}" }).jsonObject }.getOrNull()
    if (args == null) {
        println("✗ Аргументы должны быть JSON-объектом.")
        return
    }
    val result = runCatching { router.callTool(tool, args) }.getOrElse {
        println("✗ Ошибка вызова: ${it.message}"); return
    }
    println("→ маршрут: [${router.serverOf(tool)}]")
    println("─".repeat(60))
    println(result.text.ifBlank { "(пустой ответ)" })
    println("─".repeat(60))
    if (result.isError) println("⚠ сервер пометил результат как ошибку (isError=true)")
}

private fun oneLine(s: String): String {
    val flat = s.replace(Regex("\\s+"), " ").trim()
    return if (flat.length <= 200) flat else flat.take(200) + "…"
}

/** Инструменты, сгруппированные по серверам — видно, что они с РАЗНЫХ MCP. */
private fun printTools(router: McpRouter) {
    val total = router.allTools().size
    println("\n✓ Доступно инструментов: $total (серверов: ${router.servers.size})")
    println("─".repeat(60))
    router.servers.forEach { mount ->
        println("[${mount.name}] ${mount.url}")
        mount.tools.forEach { tool -> println("   • ${tool.name} — ${tool.description}") }
    }
    println("─".repeat(60))
}

private fun printHelp(agentOn: Boolean) {
    val agentLine = if (agentOn) {
        "|  <запрос>                агент сам строит флоу из инструментов РАЗНЫХ серверов\n" +
            "|                          напр.: Собери досье по Санкт-Петербургу (справка+погода) и сохрани в spb.md"
    } else {
        "|  (агент выключен — нет DEEPSEEK_API_KEY)"
    }
    println(
        """
        |Команды:
        $agentLine
        |  tool <name> <json>       прямой вызов инструмента через роутер (демо без LLM)
        |  list                     инструменты по серверам
        |  quit | exit              выход
        """.trimMargin(),
    )
}
