import kotlinx.serialization.json.JsonObject

/** Один подключённый MCP-сервер: его адрес, клиент, рукопожатие и список инструментов. */
data class MountedServer(
    val name: String,
    val url: String,
    val client: McpClient,
    val info: McpServerInfo,
    val tools: List<McpTool>,
)

/**
 * ОРКЕСТРАТОР маршрутизации (ядро Дня 20).
 *
 * Подключается сразу к НЕСКОЛЬКИМ MCP-серверам (по одному [McpClient] на сервер) и сводит их
 * инструменты в один плоский список для агента. Главное — таблица маршрутизации
 * tool → сервер: когда агент просит вызвать инструмент, роутер сам отправляет вызов НА ТОТ
 * сервер, которому инструмент принадлежит. Агент видит просто «инструменты», а корректную
 * доставку запроса нужному серверу обеспечивает роутер.
 *
 * Коллизии имён (один и тот же tool на двух серверах) разрешаются по принципу «первый выиграл»:
 * дубликат пропускается с предупреждением, чтобы маршрутизация оставалась однозначной.
 */
class McpRouter {
    private val mounts = mutableListOf<MountedServer>()
    private val routeOf = mutableMapOf<String, McpClient>() // tool -> клиент сервера-владельца
    private val serverOf = mutableMapOf<String, String>()   // tool -> имя сервера-владельца

    val servers: List<MountedServer> get() = mounts

    /** Подключает сервер по URL, делает рукопожатие, забирает tools/list и строит маршруты. */
    fun mount(url: String): MountedServer {
        val client = McpClient(url)
        val info = client.connect()
        val tools = client.listTools()

        val accepted = mutableListOf<McpTool>()
        tools.forEach { tool ->
            val owner = serverOf[tool.name]
            if (owner != null) {
                System.err.println(
                    "⚠ инструмент «${tool.name}» уже есть на сервере «$owner» — дубликат с «${info.name}» пропущен",
                )
            } else {
                routeOf[tool.name] = client
                serverOf[tool.name] = info.name
                accepted += tool
            }
        }

        val mounted = MountedServer(info.name, url, client, info, accepted)
        mounts += mounted
        return mounted
    }

    /** Плоский список ВСЕХ инструментов со всех серверов — его и видит агент (function calling). */
    fun allTools(): List<McpTool> = mounts.flatMap { it.tools }

    /** Имя сервера, которому принадлежит инструмент (для логов: видно, что флоу кросс-серверный). */
    fun serverOf(toolName: String): String = serverOf[toolName] ?: "?"

    /**
     * МАРШРУТИЗАЦИЯ вызова: находит сервер-владельца инструмента и шлёт вызов именно ему.
     * Если инструмент ничей — это ошибка маршрутизации (агент выдумал имя).
     */
    fun callTool(name: String, arguments: JsonObject): McpToolResult {
        val client = routeOf[name]
            ?: return McpToolResult(
                "Маршрут не найден: инструмент «$name» не зарегистрирован ни на одном сервере.",
                isError = true,
            )
        return client.callTool(name, arguments)
    }
}
