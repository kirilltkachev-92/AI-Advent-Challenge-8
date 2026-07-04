import kotlinx.serialization.json.Json
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Path
import kotlin.io.path.writeText

/**
 * Проверка Дня 24 на контрольных вопросах:
 *  - есть ли источники в каждом ответе (и валидны ли они) — кодом;
 *  - есть ли цитаты и дословны ли они — кодом (Validator);
 *  - совпадает ли СМЫСЛ ответа с цитатами — слепой LLM-судья (groundedness);
 *  + 3 вопроса «мимо базы»: агент обязан сказать «не знаю» и попросить уточнение.
 * Результат — output/verification.md.
 */
object Verification {
    private val json = Json { ignoreUnknownKeys = true }

    /** Вопросы мимо базы — на них правильный ответ ровно один: «не знаю, уточните». */
    val OFF_BASE = listOf(
        "Какая столица Франции?",
        "How do I cook a perfect risotto?",
        "What score does GPT-4 achieve on the bar exam?",
    )

    private data class Row(
        val question: String,
        val answer: StructuredAnswer,
        val check: Validator.Result,
        val grounded: Int,
        val groundedWhy: String,
    )

    private data class OffRow(val question: String, val answer: StructuredAnswer, val refused: Boolean)

    fun run(agent: RagAgent, judgeLlm: DeepSeekClient, reportPath: Path) {
        val rows = ControlSet.QUESTIONS.mapIndexed { i, q ->
            println()
            println("━━━ Вопрос ${i + 1}/${ControlSet.QUESTIONS.size} ━━━")
            println("Q: ${q.question}")
            val a = agent.ask(q.question)
            printAnswer(a)
            val check = Validator.validate(a)
            val (g, gw) = if (a.canAnswer) judgeGrounded(judgeLlm, a) else 0 to "ответ-отказ, смысл не проверяется"
            println("Проверки: источники ${mark(check.hasSources && check.sourcesValid)}, " +
                "цитаты ${mark(check.hasQuotes)}, дословность ${check.quotesVerbatim}/${check.quoteChecks.size}, " +
                "смысл совпадает с цитатами: $g/2 ($gw)")
            Row(q.question, a, check, g, gw)
        }

        println()
        println("━━━ Вопросы мимо базы (режим «не знаю») ━━━")
        val offRows = OFF_BASE.map { q ->
            println()
            println("Q: $q")
            val a = agent.ask(q)
            printAnswer(a)
            val refused = !a.canAnswer && a.sources.isEmpty()
            println("Отказ с просьбой уточнить: ${mark(refused)}" +
                if (a.refusedByRetrieval) " (сработал порог релевантности, генератор не вызывался)" else "")
            OffRow(q, a, refused)
        }

        println()
        println("ИТОГ: источники ${rows.count { it.check.hasSources && it.check.sourcesValid }}/10, " +
            "цитаты ${rows.count { it.check.hasQuotes }}/10, " +
            "все цитаты дословны ${rows.count { it.check.allQuotesVerbatim }}/10, " +
            "смысл 2/2 у ${rows.count { it.grounded == 2 }}/10; " +
            "отказы на «мимо базы» ${offRows.count { it.refused }}/${offRows.size}")
        reportPath.writeText(render(rows, offRows))
        println("Отчёт: $reportPath")
    }

    private fun printAnswer(a: StructuredAnswer) {
        println("  rewrite: ${a.trace.rewrittenQuery}")
        println("  воронка: ${a.trace.candidatesBefore} → порог косинуса → ${a.trace.afterSimFilter} → реранк → ${a.trace.afterRerank} → в контекст ${a.trace.final.size}")
        if (!a.canAnswer) {
            println("  ОТКАЗ: ${a.clarification}")
            return
        }
        println("  Ответ: ${a.answer.trim()}")
        println("  Источники:")
        a.sources.forEach { println("    - ${it.chunkId} («${it.section}»)") }
        println("  Цитаты:")
        a.quotes.forEach { println("    - [${it.chunkId}] «${it.quote.take(160)}${if (it.quote.length > 160) "…" else ""}»") }
    }

    /** Судья groundedness: следует ли ответ ИЗ ЦИТАТ (2 — полностью, 1 — частично, 0 — нет). */
    private fun judgeGrounded(llm: DeepSeekClient, a: StructuredAnswer): Pair<Int, String> {
        val system = "Ты проверяешь groundedness ответа RAG-системы. Даны ЦИТАТЫ из документов и ОТВЕТ. " +
            "Оцени, следует ли смысл ответа из цитат: 2 — каждое утверждение ответа подкреплено цитатами; " +
            "1 — часть утверждений не подкреплена; 0 — ответ противоречит цитатам или не связан с ними. " +
            "Ответь строго JSON: {\"score\": 0|1|2, \"why\": \"кратко по-русски\"}."
        val user = "Цитаты:\n" + a.quotes.joinToString("\n") { "- «${it.quote}»" } + "\n\nОтвет:\n${a.answer}"
        val raw = llm.chat(system, user, jsonMode = true)
        val obj = json.parseToJsonElement(raw).jsonObject
        return (obj["score"]?.jsonPrimitive?.intOrNull ?: 0) to (obj["why"]?.jsonPrimitive?.content ?: "")
    }

    private fun mark(ok: Boolean) = if (ok) "✓" else "✗"

    private fun render(rows: List<Row>, offRows: List<OffRow>): String {
        val sb = StringBuilder()
        sb.appendLine("# День 24 — цитаты, источники и анти-галлюцинации: проверка")
        sb.appendLine()
        sb.appendLine("База: статья GPT-3 (arXiv 2005.14165). Пайплайн Дня 23 (rewrite → top-${Config.candidatesK()} → порог ${Config.simThreshold()} → реранк ≥${Config.rerankThreshold()} → top-${Config.finalK()}), fallback убран: слабый контекст ⇒ «не знаю».")
        sb.appendLine()
        sb.appendLine("Проверки: источники и дословность цитат — кодом (`Validator`), совпадение смысла ответа с цитатами — слепой судья (0–2).")
        sb.appendLine()
        sb.appendLine("## Итог по 10 контрольным вопросам")
        sb.appendLine()
        sb.appendLine("| проверка | результат |")
        sb.appendLine("|---|---|")
        sb.appendLine("| источники есть и валидны (chunk_id из контекста) | ${rows.count { it.check.hasSources && it.check.sourcesValid }}/10 |")
        sb.appendLine("| цитаты есть | ${rows.count { it.check.hasQuotes }}/10 |")
        sb.appendLine("| все цитаты дословны | ${rows.count { it.check.allQuotesVerbatim }}/10 |")
        sb.appendLine("| смысл ответа совпадает с цитатами (2/2) | ${rows.count { it.grounded == 2 }}/10 |")
        sb.appendLine("| отказ «не знаю» на вопросы мимо базы | ${offRows.count { it.refused }}/${offRows.size} |")
        sb.appendLine()

        rows.forEachIndexed { i, r ->
            sb.appendLine("---")
            sb.appendLine()
            sb.appendLine("### Q${i + 1}. ${r.question}")
            sb.appendLine()
            sb.appendLine("**Воронка:** ${r.answer.trace.candidatesBefore} → ${r.answer.trace.afterSimFilter} → ${r.answer.trace.afterRerank} → ${r.answer.trace.final.size} | **rewrite:** ${r.answer.trace.rewrittenQuery}")
            sb.appendLine()
            if (!r.answer.canAnswer) {
                sb.appendLine("**ОТКАЗ:** ${r.answer.clarification}")
                sb.appendLine()
                return@forEachIndexed
            }
            sb.appendLine("**Ответ:** ${r.answer.answer.trim()}")
            sb.appendLine()
            sb.appendLine("**Источники:** " + r.answer.sources.joinToString("; ") { "`${it.chunkId}` («${it.section}»)" })
            sb.appendLine()
            sb.appendLine("**Цитаты:**")
            r.check.quoteChecks.forEach { qc ->
                sb.appendLine("- ${mark(qc.verbatim)} [`${qc.quote.chunkId}`] «${qc.quote.quote}»" +
                    if (qc.verbatim && !qc.foundInClaimedChunk) " *(дословна, но найдена в другом чанке контекста)*" else "")
            }
            sb.appendLine()
            sb.appendLine("| источники | цитаты | дословность | смысл ↔ цитаты |")
            sb.appendLine("|---|---|---|---|")
            sb.appendLine("| ${mark(r.check.hasSources && r.check.sourcesValid)} | ${mark(r.check.hasQuotes)} | ${r.check.quotesVerbatim}/${r.check.quoteChecks.size} | ${r.grounded}/2 — ${r.groundedWhy.replace("|", "\\|")} |")
            sb.appendLine()
        }

        sb.appendLine("---")
        sb.appendLine()
        sb.appendLine("## Режим «не знаю» (вопросы мимо базы)")
        offRows.forEach { r ->
            sb.appendLine()
            sb.appendLine("### ${r.question}")
            sb.appendLine()
            sb.appendLine("**Отказ:** ${mark(r.refused)}" +
                if (r.answer.refusedByRetrieval) " — сработал порог релевантности (реранк не пропустил ни одного чанка), генератор не вызывался." else "")
            sb.appendLine()
            sb.appendLine("> ${(if (r.answer.canAnswer) r.answer.answer else r.answer.clarification).trim()}")
        }
        return sb.toString()
    }
}
