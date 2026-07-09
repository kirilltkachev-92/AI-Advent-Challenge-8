import kotlinx.serialization.json.Json
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Path
import kotlin.io.path.writeText

/**
 * Сравнение локальной и облачной генерации на ОДНОМ и том же локальном retrieval:
 *  - каждый вопрос контрольного набора → top-K сообщений (локально, один раз),
 *    развёрнутых в фрагменты с контекстом соседних реплик;
 *  - обе модели получают идентичный промпт; каждый вопрос гоняется N раз подряд —
 *    из повторов считаем стабильность (ошибки, идентичность ответов, разброс латентности);
 *  - качество: слепой судья (DeepSeek) ставит 0–2 против зафиксированного ожидания,
 *    не зная, какая модель отвечала (как в Неделе 5);
 *  - скорость: латентность и ток/с (у локальной — из счётчиков Ollama).
 */
object Comparison {
    private val json = Json { ignoreUnknownKeys = true }

    data class ModelRun(
        val name: String,
        val answers: List<String>,
        val latenciesMs: List<Long>,
        val tokensPerSec: List<Double>,
        val errors: List<String>,
    ) {
        val firstAnswer: String? get() = answers.firstOrNull()
        val identical: Boolean get() = answers.size > 1 && answers.map { normalize(it) }.distinct().size == 1
        val avgLatency: Long get() = if (latenciesMs.isEmpty()) 0 else latenciesMs.sum() / latenciesMs.size
        val avgTokPerSec: Double get() = tokensPerSec.filter { it > 0 }.let { if (it.isEmpty()) 0.0 else it.average() }

        private fun normalize(s: String) = s.trim().replace(Regex("\\s+"), " ")
    }

    data class Row(
        val q: ControlQuestion,
        val hits: List<Hit>,
        val local: ModelRun,
        val cloud: ModelRun?,
        val localScore: Int?, val localWhy: String?,
        val cloudScore: Int?, val cloudWhy: String?,
    )

    // Никаких фактов о чате в system — всё, что модель знает, приходит из retrieval.
    fun systemPrompt() =
        "Ты — ассистент по истории Telegram-чата «AI Advent Challenge #8» (ежедневный челлендж по ИИ). " +
            "Отвечай на вопрос ТОЛЬКО по приведённым фрагментам переписки. " +
            "Если ответа во фрагментах нет — так и скажи. Отвечай кратко, по-русски; " +
            "где уместно, указывай автора и дату сообщения."

    fun userPrompt(question: String, fragments: List<Fragment>) = buildString {
        appendLine("Фрагменты переписки:")
        fragments.forEach { fragment ->
            appendLine()
            appendLine("--- фрагмент (${fragment.header}) ---")
            appendLine(fragment.text)
        }
        appendLine()
        append("Вопрос: $question")
    }

    fun run(
        index: DocumentIndex,
        messages: List<TgMessage>,
        embedder: OllamaEmbedder,
        local: OllamaClient,
        localModel: String,
        cloud: DeepSeekClient?,
        judge: DeepSeekClient?,
        runs: Int,
        reportPath: Path,
    ) {
        val rows = ControlSet.QUESTIONS.mapIndexed { i, q ->
            println()
            println("━━━ Вопрос ${i + 1}/${ControlSet.QUESTIONS.size}: ${q.question} ━━━")
            val hits = Search.topK(index, embedder.embedQuery(q.question), q.question, Config.topK())
            val fragments = Search.expand(hits, messages)
            println("retrieval: " + hits.joinToString { "${it.chunk.chunk_id} (${it.chunk.section}, %.2f)".format(it.score) })
            val system = systemPrompt()
            val user = userPrompt(q.question, fragments)

            val localRun = runModel("локальная `$localModel`", runs) {
                val r = local.chat(localModel, system, user)
                Triple(r.answer, r.totalMs, r.tokensPerSec)
            }
            println("  локальная: ${localRun.avgLatency} мс в среднем, ошибок ${localRun.errors.size}")
            localRun.firstAnswer?.let { println("  → ${it.replace("\n", " ").take(160)}") }

            val cloudRun = cloud?.let { c ->
                runModel("облачная `${Config.deepSeekModel()}`", runs) {
                    val r = c.chat(system, user)
                    Triple(r.answer, r.totalMs, r.tokensPerSec)
                }.also { run ->
                    println("  облачная: ${run.avgLatency} мс в среднем, ошибок ${run.errors.size}")
                    run.firstAnswer?.let { println("  → ${it.replace("\n", " ").take(160)}") }
                }
            }

            val (ls, lw) = judgeAnswer(judge, q, localRun.firstAnswer)
            val (cs, cw) = judgeAnswer(judge, q, cloudRun?.firstAnswer)
            if (ls != null) println("  судья: локальная = $ls${cs?.let { ", облачная = $it" } ?: ""}")
            Row(q, hits, localRun, cloudRun, ls, lw, cs, cw)
        }

        reportPath.writeText(render(rows, localModel, runs))
        println()
        val maxScore = rows.size * 2
        rows.mapNotNull { it.localScore }.takeIf { it.isNotEmpty() }
            ?.let { println("ИТОГ качество: локальная ${it.sum()}/$maxScore, облачная ${rows.mapNotNull { r -> r.cloudScore }.sum()}/$maxScore") }
        println("Отчёт: $reportPath")
    }

    /** N прогонов одного промпта; ошибки не валят сравнение, а копятся в статистику стабильности. */
    private fun runModel(name: String, runs: Int, call: () -> Triple<String, Long, Double>): ModelRun {
        val answers = mutableListOf<String>()
        val latencies = mutableListOf<Long>()
        val speeds = mutableListOf<Double>()
        val errors = mutableListOf<String>()
        repeat(runs) {
            runCatching(call)
                .onSuccess { (answer, ms, tps) -> answers += answer; latencies += ms; speeds += tps }
                .onFailure { errors += (it.message ?: it.javaClass.simpleName).take(200) }
        }
        return ModelRun(name, answers, latencies, speeds, errors)
    }

    private fun judgeAnswer(judge: DeepSeekClient?, q: ControlQuestion, answer: String?): Pair<Int?, String?> {
        if (judge == null || answer == null) return null to null
        val system = "Ты строгий проверяющий. Дан вопрос, ЭТАЛОННОЕ ожидание ответа и ответ модели " +
            "(ты не знаешь, какой именно). Оцени: 2 — содержит ключевые факты из ожидания без ошибок; " +
            "1 — частично верен или неполон; 0 — неверен, выдуман или отказ там, где ответ ожидался. " +
            "Особые указания в тексте ожидания (например, как оценивать честное «не нашёл») имеют приоритет. " +
            "Выдуманные факты = 0. Ответь строго JSON: {\"score\": 0|1|2, \"why\": \"кратко по-русски\"}."
        val user = "Вопрос: ${q.question}\n\nОжидание: ${q.expectation}\n\nОтвет модели:\n$answer"
        return runCatching {
            val obj = json.parseToJsonElement(judge.chat(system, user, jsonMode = true).answer).jsonObject
            (obj["score"]?.jsonPrimitive?.intOrNull ?: 0) to (obj["why"]?.jsonPrimitive?.content ?: "")
        }.getOrElse { null to "судья упал: ${it.message?.take(120)}" }
    }

    private fun render(rows: List<Row>, localModel: String, runs: Int): String = buildString {
        val hasCloud = rows.any { it.cloud != null }
        val hasJudge = rows.any { it.localScore != null }
        val maxScore = rows.size * 2

        appendLine("# День 28 — локальный RAG: локальная генерация против облачной")
        appendLine()
        appendLine(
            "База: весь чат AI Advent Challenge #8 (31.05–08.07). Индекс и retrieval — локальные " +
                "(формат Недели 5, `nomic-embed-text`, message-level + контекст соседних реплик), top-${Config.topK()}. " +
                "Обе модели получают идентичный промпт; каждый вопрос гонялся по $runs раза (temperature 0). " +
                "Качество: слепой судья против зафиксированного ожидания (0–2).",
        )
        appendLine()
        appendLine("## Итог")
        appendLine()
        append("| метрика | локальная `$localModel` |")
        appendLine(if (hasCloud) " облачная `${Config.deepSeekModel()}` |" else "")
        append("|---|---|")
        appendLine(if (hasCloud) "---|" else "")
        if (hasJudge) {
            val l = rows.sumOf { it.localScore ?: 0 }
            val c = rows.sumOf { it.cloudScore ?: 0 }
            appendLine("| качество (судья, макс $maxScore) | **$l** |" + if (hasCloud) " **$c** |" else "")
            appendLine("| ответов на 2 балла | ${rows.count { it.localScore == 2 }} |" + if (hasCloud) " ${rows.count { it.cloudScore == 2 }} |" else "")
            appendLine("| ответов на 0 баллов | ${rows.count { it.localScore == 0 }} |" + if (hasCloud) " ${rows.count { it.cloudScore == 0 }} |" else "")
        }
        val lLat = rows.flatMap { it.local.latenciesMs }
        val cLat = rows.mapNotNull { it.cloud }.flatMap { it.latenciesMs }
        appendLine("| латентность средняя | ${lLat.avgMs()} |" + if (hasCloud) " ${cLat.avgMs()} |" else "")
        appendLine("| латентность мин–макс | ${lLat.rangeMs()} |" + if (hasCloud) " ${cLat.rangeMs()} |" else "")
        appendLine(
            "| скорость генерации | ${"%.1f ток/с".format(rows.map { it.local.avgTokPerSec }.average())} |" +
                if (hasCloud) " ${"%.1f ток/с*".format(rows.mapNotNull { it.cloud }.map { it.avgTokPerSec }.average())} |" else "",
        )
        appendLine("| ошибок (из ${rows.size * runs} запросов) | ${rows.sumOf { it.local.errors.size }} |" + if (hasCloud) " ${rows.sumOf { it.cloud?.errors?.size ?: 0 }} |" else "")
        appendLine("| вопросов, где все $runs ответа идентичны | ${rows.count { it.local.identical }}/${rows.size} |" + if (hasCloud) " ${rows.count { it.cloud?.identical == true }}/${rows.size} |" else "")
        appendLine()
        if (hasCloud) appendLine("\\* у облачной скорость посчитана по времени всего HTTP-запроса (сеть внутри), у локальной — по счётчикам Ollama.")
        appendLine()

        rows.forEachIndexed { i, r ->
            appendLine("---")
            appendLine()
            appendLine("### Q${i + 1}. ${r.q.question}")
            appendLine()
            appendLine("**Ожидание:** ${r.q.expectation}")
            appendLine()
            appendLine("**Retrieval (локальный):** " + r.hits.joinToString("; ") { "`${it.chunk.chunk_id}` ${it.chunk.section} (${"%.2f".format(it.score)})" })
            appendLine()
            appendLine("| модель | судья | латентность ($runs прогона) | ток/с | стабильность |")
            appendLine("|---|---|---|---|---|")
            appendLine(modelRow(r.local, r.localScore, r.localWhy))
            r.cloud?.let { appendLine(modelRow(it, r.cloudScore, r.cloudWhy)) }
            appendLine()
            appendLine("<details><summary>Ответ локальной модели</summary>")
            appendLine()
            appendLine(r.local.firstAnswer ?: "_все $runs запросов упали: ${r.local.errors.firstOrNull()}_")
            appendLine()
            appendLine("</details>")
            r.cloud?.let {
                appendLine()
                appendLine("<details><summary>Ответ облачной модели</summary>")
                appendLine()
                appendLine(it.firstAnswer ?: "_все $runs запросов упали: ${it.errors.firstOrNull()}_")
                appendLine()
                appendLine("</details>")
            }
            appendLine()
        }
    }

    private fun modelRow(run: ModelRun, score: Int?, why: String?): String {
        val stability = when {
            run.errors.isNotEmpty() -> "${run.errors.size} ошибок"
            run.identical -> "все ответы идентичны"
            else -> "${run.answers.map { it.trim() }.distinct().size} разных ответа"
        }
        val judgeCell = score?.let { "$it — ${(why ?: "").replace("|", "\\|")}" } ?: "—"
        return "| ${run.name} | $judgeCell | ${run.latenciesMs.joinToString(" / ") { "$it мс" }} | ${"%.1f".format(run.avgTokPerSec)} | $stability |"
    }

    private fun List<Long>.avgMs() = if (isEmpty()) "—" else "${sum() / size} мс"
    private fun List<Long>.rangeMs() = if (isEmpty()) "—" else "${min()}–${max()} мс"
}
