import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Тесты разбора транспорта — без сети. Проверяем, что клиент одинаково достаёт
 * JSON-RPC-сообщение и из обычного JSON-ответа, и из потока SSE (text/event-stream).
 */
class McpClientTest {

    @Test
    fun `извлекает JSON из обычного application-json ответа`() {
        val body = """{"jsonrpc":"2.0","id":1,"result":{"tools":[]}}"""
        assertEquals(body, McpClient.extractJsonRpc(body))
    }

    @Test
    fun `извлекает data из SSE-события`() {
        val sse = """
            event: message
            data: {"jsonrpc":"2.0","id":2,"result":{"ok":true}}

        """.trimIndent()
        assertEquals("""{"jsonrpc":"2.0","id":2,"result":{"ok":true}}""", McpClient.extractJsonRpc(sse))
    }

    @Test
    fun `пустое тело даёт null`() {
        assertNull(McpClient.extractJsonRpc("   "))
        assertNull(McpClient.extractJsonRpc(""))
    }

    @Test
    fun `SSE без полезных данных даёт null`() {
        val sse = "event: ping\ndata:"
        assertNull(McpClient.extractJsonRpc(sse))
    }
}
