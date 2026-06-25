import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.nio.file.Files
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Тесты планировочного MCP-сервера на уровне JSON-RPC, без сети: погода подменена
 * заглушкой, хранилище — во временном файле. Проверяем сбор+персист, агрегацию,
 * пустую сводку, ошибку и круговой обход хранилища на диске.
 */
class McpSchedulerTest {
    private val json = Json { ignoreUnknownKeys = true }

    private fun place(name: String) =
        GeoPlace(name = name, country = "Тест", latitude = 0.0, longitude = 0.0, timezone = "UTC")

    /** Заглушка погоды: температура растёт от вызова к вызову — удобно проверить агрегаты. */
    private fun risingWeather(): (String) -> CurrentWeather {
        var t = 10.0
        return { city ->
            t += 2.0
            CurrentWeather(
                place = place(city),
                temperatureC = t,
                windSpeed = 5.0,
                weatherCode = 1,
                description = "преимущественно ясно",
                time = "2026-06-25T12:00",
            )
        }
    }

    private fun newStore() = SampleStore(createTempDirectory("day18").resolve("samples.json"))

    private fun rpcCall(server: McpServer, name: String, args: JsonObject): JsonObject {
        val params = buildJsonObject {
            put("name", name)
            put("arguments", args)
        }
        val msg = buildJsonObject {
            put("jsonrpc", "2.0"); put("id", 1); put("method", "tools/call"); put("params", params)
        }
        return server.handleRpc(msg)!!["result"]!!.jsonObject
    }

    private fun text(result: JsonObject): String =
        result["content"]!!.jsonArray.first().jsonObject["text"]!!.jsonPrimitive.content

    private fun cityArgs(city: String) = buildJsonObject { put("city", city) }

    @Test
    fun `tools-list содержит три инструмента со схемой city`() {
        val server = McpServer.schedulerServer(newStore(), risingWeather())
        val msg = buildJsonObject { put("jsonrpc", "2.0"); put("id", 1); put("method", "tools/list") }
        val tools = server.handleRpc(msg)!!["result"]!!.jsonObject["tools"]!!.jsonArray
        val names = tools.map { it.jsonObject["name"]!!.jsonPrimitive.content }.toSet()
        assertEquals(setOf("get_weather", "record_weather_sample", "weather_summary"), names)
        val recordSchema = tools.first { it.jsonObject["name"]!!.jsonPrimitive.content == "record_weather_sample" }
            .jsonObject["inputSchema"]!!.jsonObject["required"]!!.jsonArray
        assertEquals("city", recordSchema.first().jsonPrimitive.content)
    }

    @Test
    fun `record собирает и сохраняет, summary агрегирует`() {
        val store = newStore()
        val server = McpServer.schedulerServer(store, risingWeather())

        repeat(3) { rpcCall(server, "record_weather_sample", cityArgs("Париж")) }
        assertEquals(3, store.samples("Париж").size)

        val summary = text(rpcCall(server, "weather_summary", cityArgs("Париж")))
        assertTrue(summary.contains("3 замер"), "должно быть 3 замера: $summary")
        // risingWeather даёт 12,14,16 → средняя 14.0, диапазон 12.0…16.0
        assertTrue(summary.contains("12.0…16.0"), "диапазон температур: $summary")
        assertTrue(summary.contains("теплеет"), "тренд роста: $summary")
    }

    @Test
    fun `summary без данных сообщает, что замеров нет`() {
        val server = McpServer.schedulerServer(newStore(), risingWeather())
        val summary = text(rpcCall(server, "weather_summary", cityArgs("Осло")))
        assertTrue(summary.contains("ещё нет ни одного замера"), summary)
    }

    @Test
    fun `пропущенный city даёт isError=true`() {
        val server = McpServer.schedulerServer(newStore(), risingWeather())
        val result = rpcCall(server, "record_weather_sample", buildJsonObject {})
        assertTrue(result["isError"]!!.jsonPrimitive.content.toBoolean())
    }

    @Test
    fun `хранилище переживает перезапуск (персист на диск)`() {
        val dir = createTempDirectory("day18-persist")
        val path = dir.resolve("samples.json")
        val sample = WeatherSample("2026-06-25T12:00", "2026-06-25T09:00:00Z", 20.0, 3.0, 1, "ясно")

        SampleStore(path).add("Берлин", sample)
        assertTrue(Files.exists(path), "файл хранилища должен быть создан")

        // Новый экземпляр читает те же данные с диска.
        val reopened = SampleStore(path)
        assertEquals(1, reopened.samples("Берлин").size)
        assertEquals(20.0, reopened.samples("Берлин").first().temperatureC)
    }

    @Test
    fun `WeatherSummary считает среднее и диапазон`() {
        val s = { t: Double -> WeatherSample("2026-06-25T12:00", "2026-06-25T09:00:00Z", t, 4.0, 1, "ясно") }
        val out = WeatherSummary.aggregate("Город", listOf(s(10.0), s(20.0), s(30.0)))
        assertTrue(out.contains("средняя 20.0"), out)
        assertTrue(out.contains("10.0…30.0"), out)
    }
}
