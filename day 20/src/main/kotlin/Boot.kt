/**
 * Поднимает все четыре тематических MCP-сервера на портах из [Config.servers].
 *
 * Используется и агентом ([main] в Main.kt), и режимом «только серверы» (McpServerMain.kt):
 * один и тот же набор серверов с продакшен-зависимостями (Википедия, Open-Meteo, файлы).
 */
object Boot {
    /** Стартует серверы и возвращает их (в порядке Config.servers) — чтобы можно было остановить. */
    fun startAllServers(specs: List<ServerSpec>): List<McpServer> {
        val wiki = WikiApi(Config.wikiLang(), Config.wikiDelayMs())
        val weather = WeatherApi()
        val saver = NoteSaver(Config.outputDir())

        val byName: Map<String, () -> McpServer> = mapOf(
            "research-mcp" to { ServerFactory.research(wiki::search, wiki::extract) },
            "weather-mcp" to { ServerFactory.weather(weather::geocode, weather::currentWeather) },
            "report-mcp" to { ServerFactory.report() },
            "storage-mcp" to { ServerFactory.storage(saver) },
        )

        return specs.map { spec ->
            val build = byName[spec.name] ?: error("Неизвестный сервер в конфиге: ${spec.name}")
            build().start(spec.port, spec.path)
        }
    }
}
