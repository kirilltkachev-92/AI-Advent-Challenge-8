import Schema.int
import Schema.str
import Schema.strArray
import kotlinx.serialization.json.jsonPrimitive

/**
 * Сборка ЧЕТЫРЁХ тематических MCP-серверов Дня 20. Каждый — отдельный сервер своей «природы»:
 *
 *   research-MCP : источник знаний  → wiki_search, wiki_extract        (Википедия)
 *   weather-MCP  : источник погоды  → geocode, get_weather            (Open-Meteo)
 *   report-MCP   : обработка текста → compose_report, word_count      (детерминированно)
 *   storage-MCP  : файловое хранилище → save_to_file, list_files, read_file
 *
 * Зависимости (API/хранилище) инъектируются — в тестах подменяются заглушками без сети.
 * Оркестрацией между серверами занимается [McpRouter] + [OrchestratorAgent], не сами серверы.
 */
object ServerFactory {

    /** research-MCP — данные из Википедии. */
    fun research(
        search: (String, Int) -> List<WikiHit>,
        extract: (String) -> String,
    ): McpServer {
        val server = McpServer(
            "research-mcp",
            "Источник знаний: wiki_search ищет статьи по запросу, wiki_extract отдаёт вводный " +
                "текст статьи по точному заголовку. Передавайте заголовок из wiki_search в wiki_extract.",
        )

        server.register(
            McpToolDef(
                name = "wiki_search",
                description = "Ищет статьи в Википедии по запросу. Возвращает список заголовков " +
                    "с короткими сниппетами. Точный заголовок передавайте в wiki_extract.",
                inputSchema = Schema.obj(required = listOf("query")) {
                    str("query", "Поисковый запрос, например «Санкт-Петербург».")
                    int("limit", "Сколько статей вернуть (1–10, по умолчанию 5).")
                },
            ) { args ->
                val query = Schema.require(args, "query")
                val limit = (args["limit"]?.jsonPrimitive?.content?.toIntOrNull() ?: 5).coerceIn(1, 10)
                val hits = search(query, limit)
                if (hits.isEmpty()) "По запросу «$query» ничего не найдено."
                else buildString {
                    appendLine("Найдено статей: ${hits.size} (запрос «$query»).")
                    hits.forEach { appendLine("• ${it.title} — ${it.snippet}") }
                    append("Передайте нужный заголовок в wiki_extract (title).")
                }
            },
        )

        server.register(
            McpToolDef(
                name = "wiki_extract",
                description = "Возвращает вводный (краткий) текст статьи Википедии по ТОЧНОМУ заголовку " +
                    "(берётся из wiki_search). Подходит как раздел для compose_report.",
                inputSchema = Schema.obj(required = listOf("title")) {
                    str("title", "Точный заголовок статьи из wiki_search.")
                },
            ) { args ->
                val title = Schema.require(args, "title")
                val text = extract(title)
                if (text.isBlank()) "По статье «$title» не удалось получить текст." else "# $title\n\n$text"
            },
        )
        return server
    }

    /** weather-MCP — текущая погода из Open-Meteo. */
    fun weather(
        geocode: (String) -> GeoPlace?,
        currentWeather: (String) -> CurrentWeather,
    ): McpServer {
        val server = McpServer(
            "weather-mcp",
            "Источник погоды: geocode находит координаты города, get_weather отдаёт текущую погоду " +
                "по названию города. Эти данные хорошо ложатся отдельным разделом в compose_report.",
        )

        server.register(
            McpToolDef(
                name = "geocode",
                description = "Находит координаты города по названию (широта/долгота/страна/таймзона).",
                inputSchema = Schema.obj(required = listOf("city")) {
                    str("city", "Название города, например «Санкт-Петербург».")
                },
            ) { args ->
                val city = Schema.require(args, "city")
                val place = geocode(city) ?: return@McpToolDef "Город «$city» не найден."
                "${place.name}, ${place.country}: ${place.latitude}, ${place.longitude} (таймзона ${place.timezone})."
            },
        )

        server.register(
            McpToolDef(
                name = "get_weather",
                description = "Текущая погода по названию города: температура, ветер, описание. " +
                    "Готовый текст можно передать разделом в compose_report (report-MCP).",
                inputSchema = Schema.obj(required = listOf("city")) {
                    str("city", "Название города, например «Санкт-Петербург».")
                },
            ) { args ->
                val city = Schema.require(args, "city")
                val w = currentWeather(city)
                "Погода в ${w.place.name} (${w.place.country}): ${w.description}, " +
                    "${w.temperatureC}°C, ветер ${w.windSpeed} м/с (на ${w.time})."
            },
        )
        return server
    }

    /** report-MCP — детерминированная обработка текста (без LLM). */
    fun report(): McpServer {
        val server = McpServer(
            "report-mcp",
            "Обработка текста: compose_report склеивает переданные блоки (разделы) в один markdown — " +
                "обычно это данные из research-MCP и weather-MCP. word_count считает статистику. " +
                "Результат compose_report передавайте в save_to_file (storage-MCP).",
        )

        server.register(
            McpToolDef(
                name = "compose_report",
                description = "Собирает итоговый markdown-отчёт из заголовка и массива текстовых блоков " +
                    "(каждый блок — отдельный раздел; блоки берутся из разных серверов). " +
                    "Результат нужно сохранить через save_to_file.",
                inputSchema = Schema.obj(required = listOf("title", "sections")) {
                    str("title", "Заголовок отчёта (обычно тема запроса пользователя).")
                    strArray("sections", "Текстовые блоки-разделы: справка, погода и т.п.")
                },
            ) { args ->
                val title = Schema.require(args, "title")
                val sections = Schema.stringList(args, "sections")
                if (sections.isEmpty()) error("список sections пуст")
                Composer.report(title, sections)
            },
        )

        server.register(
            McpToolDef(
                name = "word_count",
                description = "Считает статистику переданного текста: символы, слова, строки.",
                inputSchema = Schema.obj(required = listOf("text")) {
                    str("text", "Текст для подсчёта статистики.")
                },
            ) { args ->
                Composer.stats(Schema.require(args, "text"))
            },
        )
        return server
    }

    /** storage-MCP — файловое хранилище. */
    fun storage(saver: NoteSaver): McpServer {
        val server = McpServer(
            "storage-mcp",
            "Файловое хранилище: save_to_file сохраняет текст в файл, list_files показывает каталог, " +
                "read_file читает файл обратно. Обычно сюда сохраняют результат compose_report.",
        )

        server.register(
            McpToolDef(
                name = "save_to_file",
                description = "Сохраняет переданный текст (content) в файл filename. Возвращает путь. " +
                    "Финальный шаг флоу — сюда обычно кладут отчёт из compose_report.",
                inputSchema = Schema.obj(required = listOf("filename", "content")) {
                    str("filename", "Имя файла, например «spb.md».")
                    str("content", "Содержимое для сохранения (обычно отчёт из compose_report).")
                },
            ) { args ->
                val saved = saver.save(Schema.require(args, "filename"), Schema.require(args, "content"))
                "Сохранено в ${saved.path} (${saved.bytes} байт)."
            },
        )

        server.register(
            McpToolDef(
                name = "list_files",
                description = "Показывает список уже сохранённых файлов и их размеры.",
                inputSchema = Schema.obj { },
            ) { _ ->
                val files = saver.list()
                if (files.isEmpty()) "Сохранённых файлов пока нет."
                else buildString {
                    appendLine("Сохранённых файлов: ${files.size}.")
                    files.forEach { appendLine("• ${it.name} (${it.bytes} байт)") }
                }.trimEnd()
            },
        )

        server.register(
            McpToolDef(
                name = "read_file",
                description = "Читает содержимое ранее сохранённого файла по имени.",
                inputSchema = Schema.obj(required = listOf("filename")) {
                    str("filename", "Имя файла, например «spb.md».")
                },
            ) { args ->
                val filename = Schema.require(args, "filename")
                saver.read(filename) ?: "Файл «$filename» не найден."
            },
        )
        return server
    }
}
