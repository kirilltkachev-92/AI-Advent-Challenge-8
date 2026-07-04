import kotlinx.serialization.json.Json
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Path
import kotlin.io.path.writeText

/**
 * Сравнение качества «без фильтра/rewriting» (baseline, пайплайн Дня 22) и
 * «с фильтром» (improved: rewrite + порог + реранк) на тех же 10 контрольных
 * вопросах и с тем же слепым судьёй, что в Дне 22 — результаты сопоставимы.
 * Результат — output/evaluation.md.
 */
object Evaluation {
    private val json = Json { ignoreUnknownKeys = true }

    private data class Row(
        val q: ControlQuestion,
        val base: AgentAnswer,
        val impr: AgentAnswer,
        val baseScore: Int,
        val baseWhy: String,
        val imprScore: Int,
        val imprWhy: String,
        val baseHit: List<String>,
        val imprHit: List<String>,
    )

    fun run(agent: RagAgent, judgeLlm: DeepSeekClient, reportPath: Path) {
        val rows = ControlSet.QUESTIONS.mapIndexed { i, q ->
            println()
            println("━━━ Вопрос ${i + 1}/${ControlSet.QUESTIONS.size} ━━━")
            println("Q: ${q.question}")
            println("Ожидание: ${q.expectation}")

            val base = agent.askBaseline(q.question)
            println()
            println("--- Baseline (без rewrite/фильтра, top-${Config.finalK()} по косинусу) ---")
            base.trace.final.forEach { println("  [${"%.3f".format(it.hit.score)}] «${it.hit.chunk.section}»") }
            println(base.text.trim())

            val impr = agent.askImproved(q.question)
            println()
            println("--- Improved (rewrite → top-${Config.candidatesK()} → порог ${Config.simThreshold()} → реранк ≥${Config.rerankThreshold()} → top-${Config.finalK()}) ---")
            println("  rewrite: ${impr.trace.rewrittenQuery}")
            println("  кандидатов: ${impr.trace.candidatesBefore} → после порога косинуса: ${impr.trace.afterSimFilter} → после реранка: ${impr.trace.afterRerank}" +
                if (impr.trace.fallbackUsed) " (fallback: взят лучший по реранку)" else "")
            impr.trace.final.forEach { println("  [rerank ${it.rerankScore}, cos ${"%.3f".format(it.hit.score)}] «${it.hit.chunk.section}»") }
            println(impr.text.trim())

            val (bs, bw) = judge(judgeLlm, q, base.text)
            val (is_, iw) = judge(judgeLlm, q, impr.text)
            println()
            println("Судья: baseline = $bs ($bw)")
            println("Судья: improved = $is_ ($iw)")

            val baseHit = sourcesHit(q, base)
            val imprHit = sourcesHit(q, impr)
            println("Источники: baseline ${baseHit.size}/${q.expectedSections.size}, improved ${imprHit.size}/${q.expectedSections.size}")
            Row(q, base, impr, bs, bw, is_, iw, baseHit, imprHit)
        }
        println()
        println("ИТОГ: baseline ${rows.sumOf { it.baseScore }}/${rows.size * 2}, " +
            "improved ${rows.sumOf { it.imprScore }}/${rows.size * 2}; " +
            "источники: baseline ${rows.sumOf { it.baseHit.size }}, improved ${rows.sumOf { it.imprHit.size }} из ${rows.sumOf { it.q.expectedSections.size }}")
        reportPath.writeText(render(rows))
        println("Отчёт: $reportPath")
    }

    private fun sourcesHit(q: ControlQuestion, a: AgentAnswer): List<String> {
        val retrieved = a.trace.final.mapNotNull { it.hit.chunk.section }.distinct()
        return q.expectedSections.filter { exp -> retrieved.any { it.startsWith(exp.take(20)) || exp.startsWith(it.take(20)) } }
    }

    /** Судья Дня 22: оценивает ответ вслепую по ожиданию — 0 мимо, 1 частично, 2 соответствует. */
    private fun judge(llm: DeepSeekClient, q: ControlQuestion, answer: String): Pair<Int, String> {
        val system = "Ты строгий проверяющий. Дан вопрос, ЭТАЛОННОЕ ожидание ответа и ответ модели. " +
            "Оцени ответ: 2 — содержит ключевые факты из ожидания без ошибок; " +
            "1 — частично верен или неполон; 0 — неверен, выдуман или отказ там, где ответ ожидался. " +
            "Выдуманные цифры = 0. Ответь строго JSON: {\"score\": 0|1|2, \"why\": \"кратко по-русски\"}."
        val user = "Вопрос: ${q.question}\n\nОжидание: ${q.expectation}\n\nОтвет модели:\n$answer"
        val raw = llm.chat(system, user, jsonMode = true)
        val obj = json.parseToJsonElement(raw).jsonObject
        return (obj["score"]?.jsonPrimitive?.intOrNull ?: 0) to (obj["why"]?.jsonPrimitive?.content ?: "")
    }

    private fun render(rows: List<Row>): String {
        val sb = StringBuilder()
        sb.appendLine("# День 23 — реранкинг и фильтрация: сравнение режимов")
        sb.appendLine()
        sb.appendLine("База: статья GPT-3 (arXiv 2005.14165), индекс `${Config.ragStrategy()}`. Модель: `${Config.deepSeekModel()}`, temperature 0.")
        sb.appendLine()
        sb.appendLine("- **baseline** — пайплайн Дня 22: вопрос → top-${Config.finalK()} по косинусу → LLM;")
        sb.appendLine("- **improved** — query rewrite → top-${Config.candidatesK()} кандидатов → порог косинуса ${Config.simThreshold()} → LLM-реранкер (0–10, cross-encoder-паттерн) → порог ≥${Config.rerankThreshold()} → top-${Config.finalK()}.")
        sb.appendLine()
        sb.appendLine("Судья оценивает ответы вслепую по зафиксированному ожиданию: 0 — мимо, 1 — частично, 2 — соответствует.")
        sb.appendLine()

        sb.appendLine("## Итог")
        sb.appendLine()
        val max = rows.size * 2
        val srcExpected = rows.sumOf { it.q.expectedSections.size }
        sb.appendLine("| | baseline (без фильтра) | improved (rewrite + фильтр + реранк) |")
        sb.appendLine("|---|---|---|")
        sb.appendLine("| баллы судьи (макс $max) | **${rows.sumOf { it.baseScore }}** | **${rows.sumOf { it.imprScore }}** |")
        sb.appendLine("| ответов на 2 балла | ${rows.count { it.baseScore == 2 }} | ${rows.count { it.imprScore == 2 }} |")
        sb.appendLine("| ответов на 0 баллов | ${rows.count { it.baseScore == 0 }} | ${rows.count { it.imprScore == 0 }} |")
        sb.appendLine("| попадание в ожидаемые источники | ${rows.sumOf { it.baseHit.size }} из $srcExpected | ${rows.sumOf { it.imprHit.size }} из $srcExpected |")
        sb.appendLine("| чанков в контексте (сумма) | ${rows.sumOf { it.base.trace.final.size }} | ${rows.sumOf { it.impr.trace.final.size }} |")
        sb.appendLine()

        rows.forEachIndexed { i, r ->
            sb.appendLine("---")
            sb.appendLine()
            sb.appendLine("### Q${i + 1}. ${r.q.question}")
            sb.appendLine()
            sb.appendLine("**Ожидание:** ${r.q.expectation}")
            sb.appendLine()
            sb.appendLine("**Ожидаемые источники:** ${r.q.expectedSections.joinToString("; ") { "«$it»" }}")
            sb.appendLine()
            sb.appendLine("**Rewrite:** ${r.impr.trace.rewrittenQuery}")
            sb.appendLine()
            sb.appendLine("**Воронка improved:** топ-${r.impr.trace.candidatesBefore} кандидатов → порог косинуса → ${r.impr.trace.afterSimFilter} → реранк → ${r.impr.trace.afterRerank}" +
                (if (r.impr.trace.fallbackUsed) " → fallback (лучший по реранку)" else "") +
                " → в контекст ${r.impr.trace.final.size}")
            sb.appendLine()
            sb.appendLine("| контекст | baseline | improved |")
            sb.appendLine("|---|---|---|")
            val maxRows = maxOf(r.base.trace.final.size, r.impr.trace.final.size)
            (0 until maxRows).forEach { j ->
                val b = r.base.trace.final.getOrNull(j)?.let { "«${it.hit.chunk.section}» (cos ${"%.3f".format(it.hit.score)})" } ?: ""
                val im = r.impr.trace.final.getOrNull(j)?.let { "«${it.hit.chunk.section}» (rerank ${it.rerankScore}, cos ${"%.3f".format(it.hit.score)})" } ?: ""
                sb.appendLine("| ${j + 1} | $b | $im |")
            }
            sb.appendLine()
            sb.appendLine("| режим | балл | источники | вердикт судьи |")
            sb.appendLine("|---|---|---|---|")
            sb.appendLine("| baseline | ${r.baseScore} | ${r.baseHit.size}/${r.q.expectedSections.size} | ${r.baseWhy.replace("|", "\\|")} |")
            sb.appendLine("| improved | ${r.imprScore} | ${r.imprHit.size}/${r.q.expectedSections.size} | ${r.imprWhy.replace("|", "\\|")} |")
            sb.appendLine()
            sb.appendLine("<details><summary>Ответ baseline</summary>")
            sb.appendLine()
            sb.appendLine(r.base.text.trim())
            sb.appendLine()
            sb.appendLine("</details>")
            sb.appendLine()
            sb.appendLine("<details><summary>Ответ improved</summary>")
            sb.appendLine()
            sb.appendLine(r.impr.text.trim())
            sb.appendLine()
            sb.appendLine("</details>")
            sb.appendLine()
        }
        return sb.toString()
    }
}
