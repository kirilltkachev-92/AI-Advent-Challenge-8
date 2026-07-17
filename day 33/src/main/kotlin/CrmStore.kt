import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

/** Пользователь в CRM — карточка клиента без чувствительных полей. */
@Serializable
data class CrmUser(
    val id: String,
    val name: String,
    val email: String,
    val team: String,
    val role: String,
    val plan: String,
    val platforms: List<String>,
    val registered_at: String,
    val subscription_status: String,
)

/** Сообщение в тикете: от пользователя, оператора или заметка ассистента. */
@Serializable
data class TicketMessage(
    val author: String,
    val at: String,
    val text: String,
)

/** Тикет поддержки с полной перепиской. */
@Serializable
data class Ticket(
    val id: String,
    val user_id: String,
    val subject: String,
    val status: String,
    val priority: String,
    val created_at: String,
    val updated_at: String,
    val tags: List<String> = emptyList(),
    val messages: List<TicketMessage> = emptyList(),
)

/** Вся CRM целиком — то, что лежит в data/crm.json. */
@Serializable
data class CrmData(
    val product: String,
    val users: List<CrmUser>,
    val tickets: List<Ticket>,
)

/**
 * «CRM на коленке» — как Алексей и советовал в чате: JSON с пользователями
 * и тикетами. Исходные данные — data/crm.json (в git); всё, что ассистент
 * дописывает (заметки в тикетах), сохраняется в output/crm-state.json,
 * чтобы исходник оставался чистым. Удалить state-файл = сбросить CRM.
 */
class CrmStore(
    private val sourcePath: Path = Config.crmSourcePath(),
    private val statePath: Path = Config.crmStatePath(),
) {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    var data: CrmData = load()
        private set

    private fun load(): CrmData {
        val path = if (statePath.exists()) statePath else sourcePath
        return json.decodeFromString(CrmData.serializer(), path.readText())
    }

    /** Поиск пользователя по email, части имени или id. */
    fun findUser(query: String): CrmUser? {
        val q = query.trim().lowercase()
        return data.users.firstOrNull { it.email.lowercase() == q || it.id.lowercase() == q }
            ?: data.users.firstOrNull {
                it.name.lowercase().contains(q) || it.email.lowercase().contains(q)
            }
    }

    fun ticketsOf(userId: String, status: String? = null): List<Ticket> =
        data.tickets.filter { it.user_id == userId }
            .filter { status == null || it.status.equals(status, ignoreCase = true) }
            .sortedByDescending { it.updated_at }

    fun ticket(ticketId: String): Ticket? =
        data.tickets.firstOrNull { it.id.equals(ticketId.trim(), ignoreCase = true) }

    /** Дописывает в тикет внутреннюю заметку ассистента и сохраняет состояние CRM. */
    fun addNote(ticketId: String, text: String): Ticket {
        val target = ticket(ticketId) ?: error("Тикет «$ticketId» не найден")
        val now = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss"))
        val updated = target.copy(
            updated_at = now,
            messages = target.messages + TicketMessage(author = "assistant", at = now, text = text),
        )
        data = data.copy(tickets = data.tickets.map { if (it.id == updated.id) updated else it })
        Files.createDirectories(statePath.parent)
        statePath.writeText(json.encodeToString(CrmData.serializer(), data))
        return updated
    }

    // --- Текстовые представления для MCP-инструментов (результат — text) --------

    fun renderUser(u: CrmUser): String = buildString {
        appendLine("${u.id} · ${u.name} <${u.email}>")
        appendLine("Команда: ${u.team} (роль: ${u.role}) · тариф ${u.plan}")
        appendLine("Платформы: ${u.platforms.joinToString(", ")} · в продукте с ${u.registered_at}")
        append("Подписка: ${u.subscription_status}")
    }

    fun renderTicketBrief(t: Ticket): String =
        "${t.id} [${t.status}/${t.priority}] «${t.subject}» · обновлён ${t.updated_at} · теги: ${t.tags.joinToString(", ")}"

    fun renderTicketFull(t: Ticket): String = buildString {
        appendLine(renderTicketBrief(t))
        appendLine("Создан: ${t.created_at} · пользователь: ${t.user_id}")
        appendLine("Переписка:")
        t.messages.forEach { m ->
            val who = when (m.author) {
                "user" -> "пользователь"
                "support" -> "поддержка"
                "assistant" -> "заметка ассистента"
                else -> m.author
            }
            appendLine("  [${m.at}] $who: ${m.text}")
        }
    }.trimEnd()
}
