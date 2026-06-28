import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.nio.file.Files
import kotlin.io.path.createTempDirectory
import kotlin.io.path.readText
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Интеграционные тесты ОРКЕСТРАЦИИ: поднимаем все четыре MCP-сервера на тестовых портах
 * с заглушками вместо сети, подключаем к ним [McpRouter] по реальному HTTP/JSON-RPC и
 * проверяем (1) сведение инструментов со всех серверов, (2) корректную МАРШРУТИЗАЦИЮ вызова
 * на сервер-владельца, (3) длинный КРОСС-СЕРВЕРНЫЙ флоу с передачей данных без искажения.
 */
class McpOrchestrationTest {
    private val basePort = 8911
    private val outDir = createTempDirectory("d20")
    private lateinit var servers: List<McpServer>
    private lateinit var router: McpRouter

    private val fakeSearch: (String, Int) -> List<WikiHit> = { _, limit ->
        listOf(
            WikiHit("Санкт-Петербург", "город на Неве"),
            WikiHit("Эрмитаж", "музей"),
        ).take(limit)
    }
    private val fakeExtract: (String) -> String = { title ->
        if (title == "Санкт-Петербург") "Санкт-Петербург — город федерального значения." else ""
    }
    private val fakeGeocode: (String) -> GeoPlace? = { city ->
        if (city.contains("Петербург")) GeoPlace("Санкт-Петербург", "Россия", 59.94, 30.31, "Europe/Moscow") else null
    }
    private val fakeWeather: (String) -> CurrentWeather = { city ->
        CurrentWeather(fakeGeocode(city)!!, 18.0, 3.0, 1, "преимущественно ясно", "2026-06-27T12:00")
    }

    @BeforeTest
    fun setUp() {
        val saver = NoteSaver(outDir)
        servers = listOf(
            ServerFactory.research(fakeSearch, fakeExtract).start(basePort, "/mcp"),
            ServerFactory.weather(fakeGeocode, fakeWeather).start(basePort + 1, "/mcp"),
            ServerFactory.report().start(basePort + 2, "/mcp"),
            ServerFactory.storage(saver).start(basePort + 3, "/mcp"),
        )
        router = McpRouter()
        (0..3).forEach { router.mount("http://localhost:${basePort + it}/mcp") }
    }

    @AfterTest
    fun tearDown() = servers.forEach { it.stop() }

    @Test
    fun `роутер сводит инструменты со всех четырёх серверов`() {
        val names = router.allTools().map { it.name }.toSet()
        assertEquals(
            setOf(
                "wiki_search", "wiki_extract", "geocode", "get_weather",
                "compose_report", "word_count", "save_to_file", "list_files", "read_file",
            ),
            names,
        )
        assertEquals(4, router.servers.size)
    }

    @Test
    fun `таблица маршрутизации указывает на правильный сервер`() {
        assertEquals("research-mcp", router.serverOf("wiki_search"))
        assertEquals("weather-mcp", router.serverOf("get_weather"))
        assertEquals("report-mcp", router.serverOf("compose_report"))
        assertEquals("storage-mcp", router.serverOf("save_to_file"))
    }

    @Test
    fun `вызов несуществующего инструмента — ошибка маршрутизации`() {
        val res = router.callTool("nonexistent", buildJsonObject {})
        assertTrue(res.isError)
        assertTrue(res.text.contains("Маршрут не найден"), res.text)
    }

    @Test
    fun `длинный кросс-серверный флоу проходит и данные передаются корректно`() {
        // (1) research-mcp: справка.
        val wiki = router.callTool("wiki_extract", buildJsonObject { put("title", "Санкт-Петербург") }).text
        assertTrue(wiki.contains("город федерального значения"), wiki)

        // (2) weather-mcp: погода.
        val weather = router.callTool("get_weather", buildJsonObject { put("city", "Санкт-Петербург") }).text
        assertTrue(weather.contains("18.0°C"), weather)

        // (3) report-mcp: собираем оба раздела (с РАЗНЫХ серверов) в один отчёт.
        val report = router.callTool("compose_report", buildJsonObject {
            put("title", "Досье: Санкт-Петербург")
            put("sections", buildJsonArray { add(wiki); add(weather) })
        }).text
        assertTrue(report.contains("# Досье: Санкт-Петербург"), report)
        assertTrue(report.contains("город федерального значения") && report.contains("18.0°C"), report)

        // (4) storage-mcp: сохраняем РОВНО результат compose_report.
        val saved = router.callTool("save_to_file", buildJsonObject {
            put("filename", "spb.md"); put("content", report)
        }).text
        assertTrue(saved.contains("Сохранено"), saved)

        // Корректность передачи данных через всю цепочку серверов: файл == отчёт.
        val file = outDir.resolve("spb.md")
        assertTrue(Files.exists(file), "файл должен быть создан")
        assertEquals(report, file.readText())

        // (5) storage-mcp: list_files видит сохранённый файл.
        val listed = router.callTool("list_files", buildJsonObject {}).text
        assertTrue(listed.contains("spb.md"), listed)
    }

    @Test
    fun `read_file через роутер возвращает ранее сохранённый контент`() {
        router.callTool("save_to_file", buildJsonObject { put("filename", "x.md"); put("content", "привет") })
        val back = router.callTool("read_file", buildJsonObject { put("filename", "x.md") }).text
        assertEquals("привет", back)
    }
}
