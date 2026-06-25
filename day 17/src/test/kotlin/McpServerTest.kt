import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Тесты ядра MCP-сервера — на уровне JSON-RPC, без сети и без реального Open-Meteo
 * (инструмент подменён заглушкой). Проверяем рукопожатие, список инструментов,
 * вызов инструмента и обработку ошибок.
 */
class McpServerTest {
    private val json = Json { ignoreUnknownKeys = true }

    /** Сервер с одним предсказуемым инструментом — без сетевых вызовов. */
    private fun stubServer(): McpServer = McpServer().register(
        McpToolDef(
            name = "echo_city",
            description = "Возвращает переданный город",
            inputSchema = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("city") { put("type", "string") }
                }
                putJsonArray("required") { add("city") }
            },
        ) { args ->
            val city = args["city"]?.jsonPrimitive?.content ?: error("нет city")
            "Город: $city"
        },
    )

    private fun rpc(method: String, id: Int? = 1, params: JsonObject = JsonObject(emptyMap())) =
        buildJsonObject {
            put("jsonrpc", "2.0")
            if (id != null) put("id", id)
            put("method", method)
            put("params", params)
        }

    @Test
    fun `initialize возвращает serverInfo и протокол`() {
        val res = stubServer().handleRpc(rpc("initialize"))!!
        val result = res["result"]!!.jsonObject
        assertEquals(McpServer.PROTOCOL_VERSION, result["protocolVersion"]!!.jsonPrimitive.content)
        assertTrue(result["serverInfo"]!!.jsonObject.containsKey("name"))
    }

    @Test
    fun `notifications-initialized не требует ответа`() {
        // Уведомление без id → null (отвечать не нужно).
        val res = stubServer().handleRpc(rpc("notifications/initialized", id = null))
        assertNull(res)
    }

    @Test
    fun `tools-list отдаёт зарегистрированный инструмент со схемой`() {
        val res = stubServer().handleRpc(rpc("tools/list"))!!
        val tools = res["result"]!!.jsonObject["tools"]!!.jsonArray
        assertEquals(1, tools.size)
        val tool = tools.first().jsonObject
        assertEquals("echo_city", tool["name"]!!.jsonPrimitive.content)
        val required = tool["inputSchema"]!!.jsonObject["required"]!!.jsonArray
        assertEquals("city", required.first().jsonPrimitive.content)
    }

    @Test
    fun `tools-call выполняет инструмент и возвращает текст`() {
        val params = buildJsonObject {
            put("name", "echo_city")
            putJsonObject("arguments") { put("city", "Берлин") }
        }
        val res = stubServer().handleRpc(rpc("tools/call", params = params))!!
        val result = res["result"]!!.jsonObject
        assertEquals(false, result["isError"]!!.jsonPrimitive.content.toBoolean())
        val text = result["content"]!!.jsonArray.first().jsonObject["text"]!!.jsonPrimitive.content
        assertEquals("Город: Берлин", text)
    }

    @Test
    fun `ошибка инструмента возвращается как isError=true`() {
        // Не передаём обязательный city → handler бросит, сервер обернёт в isError.
        val params = buildJsonObject {
            put("name", "echo_city")
            putJsonObject("arguments") {}
        }
        val res = stubServer().handleRpc(rpc("tools/call", params = params))!!
        val result = res["result"]!!.jsonObject
        assertTrue(result["isError"]!!.jsonPrimitive.content.toBoolean())
    }

    @Test
    fun `неизвестный метод даёт JSON-RPC error -32601`() {
        val res = stubServer().handleRpc(rpc("no/such/method"))!!
        assertEquals(-32601, res["error"]!!.jsonObject["code"]!!.jsonPrimitive.content.toInt())
    }

    @Test
    fun `extractJsonRpc разбирает SSE и обычный JSON`() {
        val plain = """{"jsonrpc":"2.0","id":1,"result":{}}"""
        assertEquals(plain, McpClient.extractJsonRpc(plain))
        val sse = "event: message\ndata: $plain\n\n"
        assertEquals(plain, McpClient.extractJsonRpc(sse))
    }
}
