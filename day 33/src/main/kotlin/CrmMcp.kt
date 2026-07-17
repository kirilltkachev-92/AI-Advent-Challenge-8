import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * MCP-инструменты над CRM: поиск пользователя, тикеты, полный тикет
 * и запись заметки. Это и есть «подключите CRM через MCP» из задания —
 * ассистент ходит к данным пользователей и тикетов только через эти
 * инструменты, напрямую JSON он не видит.
 */
object CrmMcp {

    fun server(store: CrmStore): McpServer = McpServer()
        .register(
            McpToolDef(
                name = "find_user",
                description = "Найти пользователя в CRM по email, имени (или его части) либо id. " +
                    "Возвращает карточку: команда, роль, тариф, платформы, статус подписки.",
                inputSchema = schema(
                    "query" to prop("string", "Email, имя или id пользователя (например «marina@lumex.ru» или «Марина»)"),
                    required = listOf("query"),
                ),
            ) { args ->
                val query = args.str("query")
                val user = store.findUser(query) ?: error("Пользователь по запросу «$query» не найден в CRM")
                store.renderUser(user)
            },
        )
        .register(
            McpToolDef(
                name = "list_tickets",
                description = "Список тикетов пользователя по его id (кратко: статус, приоритет, тема). " +
                    "Опционально фильтр по статусу open/closed.",
                inputSchema = schema(
                    "user_id" to prop("string", "Id пользователя из CRM, например «u-101»"),
                    "status" to prop("string", "Фильтр по статусу: open или closed (необязательно)"),
                    required = listOf("user_id"),
                ),
            ) { args ->
                val userId = args.str("user_id")
                val status = args.strOrNull("status")
                val tickets = store.ticketsOf(userId, status)
                if (tickets.isEmpty()) "У пользователя $userId нет тикетов" +
                    (status?.let { " со статусом $it" } ?: "")
                else tickets.joinToString("\n") { store.renderTicketBrief(it) }
            },
        )
        .register(
            McpToolDef(
                name = "get_ticket",
                description = "Полный тикет по id: статус, теги и вся переписка с поддержкой.",
                inputSchema = schema(
                    "ticket_id" to prop("string", "Id тикета, например «T-1042»"),
                    required = listOf("ticket_id"),
                ),
            ) { args ->
                val id = args.str("ticket_id")
                val ticket = store.ticket(id) ?: error("Тикет «$id» не найден")
                store.renderTicketFull(ticket)
            },
        )
        .register(
            McpToolDef(
                name = "add_ticket_note",
                description = "Дописать в тикет внутреннюю заметку ассистента (видна операторам). " +
                    "Использовать только по явной просьбе оператора.",
                inputSchema = schema(
                    "ticket_id" to prop("string", "Id тикета, например «T-1042»"),
                    "text" to prop("string", "Текст заметки"),
                    required = listOf("ticket_id", "text"),
                ),
            ) { args ->
                val updated = store.addNote(args.str("ticket_id"), args.str("text"))
                "Заметка добавлена в ${updated.id}. Последние записи:\n" +
                    store.renderTicketFull(updated).lines().takeLast(3).joinToString("\n")
            },
        )

    // --- мелкие помощники для JSON Schema и аргументов -------------------------

    private fun prop(type: String, description: String): JsonObject = buildJsonObject {
        put("type", type)
        put("description", description)
    }

    private fun schema(vararg props: Pair<String, JsonObject>, required: List<String>): JsonObject =
        buildJsonObject {
            put("type", "object")
            putJsonObject("properties") { props.forEach { (name, p) -> put(name, p) } }
            putJsonArray("required") { required.forEach { add(JsonPrimitive(it)) } }
        }

    private fun JsonObject.str(key: String): String =
        this[key]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
            ?: error("Не передан обязательный аргумент «$key»")

    private fun JsonObject.strOrNull(key: String): String? =
        this[key]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
}
