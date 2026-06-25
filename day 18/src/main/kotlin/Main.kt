import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Duration
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * День 18 — фоновый агент-планировщик погоды, работающий 24/7.
 *
 * Что происходит при запуске:
 *   1) поднимаем встроенный MCP-сервер планировщика (инструменты record_weather_sample / weather_summary);
 *   2) подключаемся к нему как MCP-клиент (initialize → tools/list);
 *   3) запускаем ПЛАНИРОВЩИК с двумя периодическими задачами:
 *        • сбор: каждые COLLECT_INTERVAL_SEC зовём record_weather_sample → замер СОХРАНЯЕТСЯ (JSON);
 *        • сводка: каждые SUMMARY_INTERVAL_SEC агент зовёт weather_summary и выдаёт АГРЕГИРОВАННУЮ сводку.
 *   4) параллельно доступен REPL для ручной проверки.
 *
 * Команды REPL:
 *   summary   — выдать сводку прямо сейчас (не дожидаясь расписания)
 *   status    — состояние планировщика: число запусков и время следующего тика
 *   samples   — сколько замеров сохранено по городам
 *   list      — список MCP-инструментов
 *   quit|exit — выход
 */

private val clock = DateTimeFormatter.ofPattern("HH:mm:ss")

private fun ts(): String = LocalTime.now().format(clock)

fun main() {
    Config.loadDotEnv()
    val cities = Config.watchCities()
    val store = SampleStore(Config.dataFile())

    // 1) MCP-сервер: внешний (VPS) или встроенный локальный.
    var embedded: McpServer? = null
    if (!Config.hasExternalServer()) {
        embedded = McpServer.schedulerServer(store).start(Config.port(), Config.MCP_PATH)
        println("✓ Встроенный MCP-сервер планировщика поднят: ${Config.serverUrl()}")
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

    // 3) Агент-сводка на DeepSeek (если есть ключ). Даём ему только weather_summary —
    //    сбор данных делает планировщик, а дело агента — агрегировать и сформулировать отчёт.
    val apiKey = runCatching { Config.apiKey() }.getOrNull()
    val summaryTools = tools.filter { it.name == "weather_summary" }
    val agent = apiKey?.let { SummaryAgent(client, summaryTools, it) }

    println(
        "\n⏱ Планировщик: сбор каждые ${Config.collectIntervalSec()} c, " +
            "сводка каждые ${Config.summaryIntervalSec()} c. Города: ${cities.joinToString(", ")}.",
    )
    if (agent == null) {
        println("⚠ DEEPSEEK_API_KEY не найден — сводку выдаём детерминированно (без LLM).")
    }
    println("Агент работает в фоне 24/7. Команды: summary | status | samples | list | quit\n")

    // 4) Планировщик с двумя периодическими задачами.
    val scheduler = Scheduler()
    scheduler.every("collect", Duration.ofSeconds(Config.collectIntervalSec())) {
        collectAll(client, cities)
    }
    scheduler.every("summary", Duration.ofSeconds(Config.summaryIntervalSec())) {
        runSummary(client, agent, cities)
    }

    // 5) REPL поверх работающего планировщика.
    repl(client, agent, scheduler, store, cities)

    scheduler.stop()
    embedded?.stop()
    println("Пока!")
}

/** Сбор: по каждому городу зовём record_weather_sample — замер СОХРАНЯЕТСЯ в хранилище. */
private fun collectAll(client: McpClient, cities: List<String>) {
    cities.forEach { city ->
        val res = runCatching {
            client.callTool("record_weather_sample", jsonCity(city))
        }.getOrElse { McpToolResult("ошибка: ${it.message}", isError = true) }
        println("[${ts()}] ⏱ collect ← ${res.text}")
    }
}

/** Сводка: агент сам зовёт weather_summary (или детерминированный фолбэк без LLM). */
private fun runSummary(client: McpClient, agent: SummaryAgent?, cities: List<String>) {
    println("\n[${ts()}] 📊 ───── ПЕРИОДИЧЕСКАЯ СВОДКА ─────")
    if (agent != null) {
        val answer = runCatching { agent.summarize(cities) }.getOrElse {
            println("✗ Ошибка агента: ${it.message}"); return
        }
        answer.toolCalls.forEach { println("    ⚙ MCP ${it.tool} ${it.arguments}") }
        println(answer.text.ifBlank { "(пустой ответ)" })
    } else {
        cities.forEach { city ->
            val res = client.callTool("weather_summary", jsonCity(city))
            println(res.text)
        }
    }
    println("[${ts()}] 📊 ──────────────────────────────\n")
}

private fun repl(
    client: McpClient,
    agent: SummaryAgent?,
    scheduler: Scheduler,
    store: SampleStore,
    cities: List<String>,
) {
    while (true) {
        val line = readlnOrNull()?.trim() ?: break
        if (line.isEmpty()) continue
        when (line.lowercase()) {
            "quit", "exit" -> break
            "summary" -> runSummary(client, agent, cities)
            "status" -> printStatus(scheduler)
            "samples" -> printSamples(store)
            "list" -> printTools(client.listTools())
            else -> println("Команды: summary | status | samples | list | quit")
        }
    }
}

private fun printStatus(scheduler: Scheduler) {
    val zone = ZoneId.systemDefault()
    println("Состояние планировщика:")
    scheduler.jobs().forEach { job ->
        val next = job.nextRunAt().atZone(zone).toLocalTime().format(clock)
        println("  • ${job.name}: запусков ${job.runs.get()}, следующий тик ~$next " +
            "(период ${job.period.seconds} c)")
    }
}

private fun printSamples(store: SampleStore) {
    val cities = store.cities()
    if (cities.isEmpty()) {
        println("Хранилище пусто — замеров ещё нет.")
        return
    }
    println("Сохранено замеров (файл ${Config.dataFile()}):")
    cities.forEach { city -> println("  • $city: ${store.samples(city).size}") }
    println("  итого: ${store.totalSamples()}")
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

private fun jsonCity(city: String) = buildJsonObject { put("city", city) }
