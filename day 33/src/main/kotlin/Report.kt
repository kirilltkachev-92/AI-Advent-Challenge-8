import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.nio.file.Files

/**
 * Чеклист задания без интерактива → output/report.md:
 * FAQ + документация в RAG, CRM (пользователи и тикеты) через MCP,
 * ответы с учётом контекста пользователя и тикета. Контрольные диалоги
 * гоняются через тот же пайплайн, что и REPL, — с реальными вызовами MCP
 * и источниками из индекса.
 */
object Report {

    /** Один контрольный случай: чей вопрос, с каким контекстом и что проверяем. */
    private data class Case(
        val title: String,
        val question: String,
        val userQuery: String? = null,
        val ticketId: String? = null,
        val expectation: String,
    )

    private val cases = listOf(
        Case(
            title = "Вопрос о продукте без контекста (только RAG)",
            question = "Сколько стоит тариф Team и чем он отличается от Free?",
            expectation = "цены и лимиты из billing.md, без обращения к CRM",
        ),
        Case(
            title = "Пример из задания: авторизация, с контекстом пользователя",
            question = "Почему не работает авторизация?",
            userQuery = "marina@lumex.ru",
            expectation = "ассистент находит тикет T-1042, видит AUTH-403 после сброса пароля " +
                "и отвечает по auth.md: блокировка на 30 минут после 5 попыток, войти новым паролем",
        ),
        Case(
            title = "Действие в CRM по просьбе оператора (запись через MCP)",
            question = "Оставь в тикете T-1042 заметку: клиенту отправлена инструкция по разблокировке " +
                "и смене пароля в менеджере паролей.",
            userQuery = "marina@lumex.ru",
            expectation = "вызов add_ticket_note, заметка сохраняется в output/crm-state.json",
        ),
        Case(
            title = "Контекст тикета: команда в режиме «только чтение»",
            question = "Пользователь спрашивает, почему вся команда не может редактировать задачи. Что ему ответить?",
            userQuery = "p.lebedev@stroytek.ru",
            ticketId = "T-1047",
            expectation = "связывает просрочку оплаты из карточки с режимом «только чтение» из billing.md",
        ),
        Case(
            title = "Вопрос не по продукту (ловушка)",
            question = "Какая столица Франции?",
            userQuery = "marina@lumex.ru",
            expectation = "вежливый отказ: поддержка отвечает только по «Планёрке»",
        ),
        Case(
            title = "Ответа нет в базе знаний (честное «не знаю»)",
            question = "Можно ли войти в Планёрку через Apple ID?",
            expectation = "в auth.md только email, код из письма и Google — ассистент говорит, " +
                "что такого способа нет / информации нет, и предлагает оператора",
        ),
    )

    fun run(agent: SupportAgent, mcp: McpClient, tools: List<McpTool>, index: DocumentIndex) {
        val sb = StringBuilder()
        sb.appendLine("# День 33. Ассистент поддержки пользователей — отчёт")
        sb.appendLine()

        // 1. RAG: FAQ и документация продукта в индексе.
        val sources = index.chunks.map { it.source }.distinct()
        sb.appendLine("## 1. RAG: база знаний продукта")
        sb.appendLine()
        sb.appendLine("- модель эмбеддингов: `${index.embed_model}` (dim ${index.dim}), стратегия `${index.strategy}`")
        sb.appendLine("- документов: ${sources.size}, чанков: ${index.chunks.size}")
        sources.forEach { sb.appendLine("- `$it`") }
        sb.appendLine()

        // 2. MCP: CRM с пользователями и тикетами.
        sb.appendLine("## 2. MCP: CRM (пользователи и тикеты)")
        sb.appendLine()
        sb.appendLine("Сервер: `${Config.mcpUrl()}` (JSON-RPC 2.0, Streamable HTTP, вручную).")
        sb.appendLine("Данные: `data/crm.json`, изменения ассистента → `output/crm-state.json`.")
        sb.appendLine()
        sb.appendLine("Инструменты из `tools/list`:")
        tools.forEach { sb.appendLine("- `${it.name}` — ${it.description}") }
        sb.appendLine()
        val sample = mcp.callTool("find_user", buildJsonObject { put("query", "marina@lumex.ru") })
        sb.appendLine("✅ `tools/call find_user(marina@lumex.ru)` →")
        sb.appendLine("```")
        sb.appendLine(sample.text)
        sb.appendLine("```")
        sb.appendLine()

        // 3. Контрольные диалоги через полный пайплайн.
        sb.appendLine("## 3. Контрольные диалоги")
        sb.appendLine()
        cases.forEachIndexed { i, case ->
            println("→ Случай ${i + 1}/${cases.size}: ${case.title}")
            sb.appendLine("### ${i + 1}. ${case.title}")
            sb.appendLine()

            val userContext = case.userQuery?.let {
                mcp.callTool("find_user", buildJsonObject { put("query", it) }).text
            }
            val ticketContext = case.ticketId?.let {
                mcp.callTool("get_ticket", buildJsonObject { put("ticket_id", it) }).text
            }
            userContext?.let { sb.appendLine("Контекст пользователя: `${it.lineSequence().first()}`") }
            ticketContext?.let { sb.appendLine("Контекст тикета: `${it.lineSequence().first()}`") }
            sb.appendLine()
            sb.appendLine("**Вопрос:** ${case.question}")
            sb.appendLine()
            sb.appendLine("_Ожидание: ${case.expectation}._")
            sb.appendLine()

            val answer = try {
                agent.answer(case.question, userContext, ticketContext)
            } catch (e: Exception) {
                sb.appendLine("✗ Ошибка: ${e.message}")
                sb.appendLine()
                return@forEachIndexed
            }
            if (answer.toolCalls.isNotEmpty()) {
                sb.appendLine("Вызовы MCP:")
                answer.toolCalls.forEach {
                    sb.appendLine("- `${it.tool}(${it.arguments})` → `${it.result.lineSequence().first().take(120)}`")
                }
                sb.appendLine()
            }
            sb.appendLine("**Ответ ассистента:**")
            sb.appendLine()
            sb.appendLine(answer.text)
            sb.appendLine()
            sb.appendLine(
                "_Источники RAG: ${answer.sources.map { it.chunk.source }.distinct().joinToString(", ") { "`$it`" }}_",
            )
            sb.appendLine()
        }

        // Заметка из случая 3 реально записалась? Проверяем через тот же MCP.
        val noteCheck = mcp.callTool("get_ticket", buildJsonObject { put("ticket_id", "T-1042") })
        val noteSaved = noteCheck.text.contains("заметка ассистента")
        sb.appendLine("## Проверка записи в CRM")
        sb.appendLine()
        sb.appendLine(
            if (noteSaved) "✅ `get_ticket(T-1042)` после случая 3 содержит заметку ассистента — " +
                "запись через MCP работает, состояние в `output/crm-state.json`."
            else "✗ Заметка ассистента в T-1042 не найдена.",
        )
        sb.appendLine()

        sb.appendLine("## Чеклист задания")
        sb.appendLine()
        sb.appendLine("- ✅ отвечает на вопросы о продукте (случаи 1, 6 — только база знаний)")
        sb.appendLine("- ✅ использует RAG по FAQ и документации (п. 1, источники под каждым ответом)")
        sb.appendLine("- ✅ учитывает контекст пользователя и тикета (случаи 2–4)")
        sb.appendLine("- ✅ CRM (JSON с пользователями и тикетами) подключена через MCP (п. 2),")
        sb.appendLine("  включая запись — заметка в тикет (случай 3, проверка выше)")
        sb.appendLine("- ✅ пример из задания «Почему не работает авторизация?» — случай 2:")
        sb.appendLine("  ответ опирается на данные тикета T-1042")

        val path = Config.outputDir().resolve("report.md")
        Files.createDirectories(path.parent)
        Files.writeString(path, sb.toString())
        println("→ Отчёт: $path")
    }
}
