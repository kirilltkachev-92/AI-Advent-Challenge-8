import kotlinx.serialization.json.Json
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Path
import kotlin.io.path.writeText

/**
 * Сравнение качества «с RAG / без RAG» на контрольном наборе:
 *  - оба режима отвечают на каждый из 10 вопросов;
 *  - судья (тот же DeepSeek, отдельный промпт) ставит каждому ответу 0–2 балла
 *    относительно зафиксированного ожидания, не зная, какой режим отвечал;
 *  - для RAG автоматически проверяется, попали ли ожидаемые разделы в извлечённые чанки.
 * Результат — output/evaluation.md.
 */
object Evaluation {
    private val json = Json { ignoreUnknownKeys = true }

    private data class Row(
        val q: ControlQuestion,
        val plain: AgentAnswer,
        val rag: AgentAnswer,
        val plainScore: Int,
        val plainWhy: String,
        val ragScore: Int,
        val ragWhy: String,
        val sourcesHit: List<String>,
    )

    fun run(agent: RagAgent, judgeLlm: DeepSeekClient, reportPath: Path) {
        val rows = ControlSet.QUESTIONS.mapIndexed { i, q ->
            println()
            println("━━━ Вопрос ${i + 1}/${ControlSet.QUESTIONS.size} ━━━")
            println("Q: ${q.question}")
            println("Ожидание: ${q.expectation}")
            val plain = agent.askPlain(q.question)
            println()
            println("--- Без RAG ---")
            println(plain.text.trim())
            val rag = agent.askRag(q.question)
            println()
            println("--- С RAG ---")
            rag.retrieved.forEach { println("  [${"%.3f".format(it.score)}] «${it.chunk.section}»") }
            println(rag.text.trim())
            val (ps, pw) = judge(judgeLlm, q, plain.text)
            val (rs, rw) = judge(judgeLlm, q, rag.text)
            println()
            println("Судья: без RAG = $ps ($pw)")
            println("Судья: с RAG  = $rs ($rw)")
            val retrievedSections = rag.retrieved.mapNotNull { it.chunk.section }.distinct()
            val hit = q.expectedSections.filter { exp -> retrievedSections.any { it.startsWith(exp.take(20)) || exp.startsWith(it.take(20)) } }
            println("Источники: ${hit.size}/${q.expectedSections.size} ожидаемых разделов в top-${Config.topK()}")
            Row(q, plain, rag, ps, pw, rs, rw, hit)
        }
        println()
        println("ИТОГ: без RAG ${rows.sumOf { it.plainScore }}/${rows.size * 2}, " +
            "с RAG ${rows.sumOf { it.ragScore }}/${rows.size * 2}, " +
            "источники ${rows.sumOf { it.sourcesHit.size }}/${rows.sumOf { it.q.expectedSections.size }}")
        reportPath.writeText(render(rows))
        println("Отчёт: $reportPath")
    }

    /** Судья оценивает один ответ вслепую (не знает, был ли RAG): 0 — мимо, 1 — частично, 2 — соответствует ожиданию. */
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
        sb.appendLine("# День 22 — сравнение качества: с RAG / без RAG")
        sb.appendLine()
        sb.appendLine("База: статья GPT-3 (arXiv 2005.14165), индекс `${Config.ragStrategy()}` из Дня 21, top-${Config.topK()}.")
        sb.appendLine("Модель в обоих режимах: `${Config.deepSeekModel()}`, temperature 0. Судья оценивает ответы вслепую по зафиксированному ожиданию: 0 — мимо, 1 — частично, 2 — соответствует.")
        sb.appendLine()

        sb.appendLine("## Итог")
        sb.appendLine()
        val plainTotal = rows.sumOf { it.plainScore }
        val ragTotal = rows.sumOf { it.ragScore }
        val max = rows.size * 2
        val srcExpected = rows.sumOf { it.q.expectedSections.size }
        val srcHit = rows.sumOf { it.sourcesHit.size }
        sb.appendLine("| | без RAG | с RAG |")
        sb.appendLine("|---|---|---|")
        sb.appendLine("| баллы судьи (макс $max) | **$plainTotal** | **$ragTotal** |")
        sb.appendLine("| ответов на 2 балла | ${rows.count { it.plainScore == 2 }} | ${rows.count { it.ragScore == 2 }} |")
        sb.appendLine("| ответов на 0 баллов | ${rows.count { it.plainScore == 0 }} | ${rows.count { it.ragScore == 0 }} |")
        sb.appendLine("| попадание в ожидаемые источники | — | $srcHit из $srcExpected |")
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
            sb.appendLine("**Извлечено (RAG):** " + r.rag.retrieved.joinToString("; ") {
                "«${it.chunk.section}» (${"%.3f".format(it.score)})"
            })
            sb.appendLine()
            sb.appendLine("**Попадание в источники:** ${r.sourcesHit.size}/${r.q.expectedSections.size}" +
                if (r.sourcesHit.isNotEmpty()) " (${r.sourcesHit.joinToString("; ") { "«$it»" }})" else "")
            sb.appendLine()
            sb.appendLine("| режим | балл | вердикт судьи |")
            sb.appendLine("|---|---|---|")
            sb.appendLine("| без RAG | ${r.plainScore} | ${r.plainWhy.replace("|", "\\|")} |")
            sb.appendLine("| с RAG | ${r.ragScore} | ${r.ragWhy.replace("|", "\\|")} |")
            sb.appendLine()
            sb.appendLine("<details><summary>Ответ без RAG</summary>")
            sb.appendLine()
            sb.appendLine(r.plain.text.trim())
            sb.appendLine()
            sb.appendLine("</details>")
            sb.appendLine()
            sb.appendLine("<details><summary>Ответ с RAG</summary>")
            sb.appendLine()
            sb.appendLine(r.rag.text.trim())
            sb.appendLine()
            sb.appendLine("</details>")
            sb.appendLine()
        }
        return sb.toString()
    }
}
