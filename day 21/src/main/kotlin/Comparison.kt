import java.nio.file.Path
import kotlin.io.path.writeText

/**
 * Сравнение двух стратегий chunking:
 *  1) статистика чанков (сколько, какого размера);
 *  2) retrieval на тестовых вопросах — top-k и косинусные скоры бок о бок;
 *  3) опционально LLM-судья (DeepSeek): какой контекст лучше отвечает на вопрос.
 * Результат — output/comparison.md.
 */
object Comparison {

    // Вопросы к корпусу (статья GPT-3): от общих определений до конкретных цифр и разделов.
    val QUERIES = listOf(
        "What is in-context learning and how does it differ from fine-tuning?",
        "What are the zero-shot, one-shot and few-shot evaluation settings?",
        "How many parameters does GPT-3 have and what data was it used to train on?",
        "How does GPT-3 perform on machine translation compared to prior unsupervised approaches?",
        "How did the authors measure and address data contamination between training and test sets?",
        "What limitations of GPT-3 do the authors acknowledge?",
    )

    fun run(
        indexA: DocumentIndex, // fixed
        indexB: DocumentIndex, // structure
        embedder: OllamaEmbedder,
        judge: DeepSeekJudge?,
        topK: Int,
        reportPath: Path,
    ) {
        val sb = StringBuilder()
        sb.appendLine("# День 21 — сравнение стратегий chunking")
        sb.appendLine()
        sb.appendLine("Эмбеддинги: `${indexA.embed_model}` (dim=${indexA.dim}, локальная Ollama). Поиск: косинусная близость, top-$topK.")
        sb.appendLine()

        sb.appendLine("## 1. Статистика чанков")
        sb.appendLine()
        sb.appendLine("| метрика | fixed (окно ${Config.chunkSize()}/${Config.chunkOverlap()}) | structure (разделы) |")
        sb.appendLine("|---|---|---|")
        statRows(indexA, indexB).forEach { (name, a, b) -> sb.appendLine("| $name | $a | $b |") }
        sb.appendLine()

        sb.appendLine("## 2. Retrieval на тестовых вопросах")
        var winsA = 0; var winsB = 0; var ties = 0
        QUERIES.forEachIndexed { qi, query ->
            println("  вопрос ${qi + 1}/${QUERIES.size}: $query")
            val qVec = embedder.embedQuery(query)
            val hitsA = Search.topK(indexA, qVec, topK)
            val hitsB = Search.topK(indexB, qVec, topK)

            sb.appendLine()
            sb.appendLine("### Q${qi + 1}. $query")
            sb.appendLine()
            sb.appendLine("| | fixed | structure |")
            sb.appendLine("|---|---|---|")
            sb.appendLine("| top-1 score | ${"%.4f".format(hitsA[0].score)} | ${"%.4f".format(hitsB[0].score)} |")
            sb.appendLine("| mean top-$topK | ${"%.4f".format(hitsA.map { it.score }.average())} | ${"%.4f".format(hitsB.map { it.score }.average())} |")
            sb.appendLine("| контекст, символов | ${hitsA.sumOf { it.chunk.text.length }} | ${hitsB.sumOf { it.chunk.text.length }} |")
            sb.appendLine()
            sb.appendLine("Найдено (fixed):")
            hitsA.forEach { sb.appendLine("- `${it.chunk.chunk_id}` (${"%.3f".format(it.score)}) — ${it.chunk.title}") }
            sb.appendLine()
            sb.appendLine("Найдено (structure):")
            hitsB.forEach { sb.appendLine("- `${it.chunk.chunk_id}` (${"%.3f".format(it.score)}) — ${it.chunk.title}, раздел «${it.chunk.section}»") }

            if (judge != null) {
                val verdict = judge.judge(
                    query,
                    hitsA.joinToString("\n---\n") { it.chunk.text },
                    hitsB.joinToString("\n---\n") { it.chunk.text },
                )
                when (verdict.winner.uppercase()) {
                    "A" -> winsA++
                    "B" -> winsB++
                    else -> ties++
                }
                sb.appendLine()
                sb.appendLine("**Судья (DeepSeek):** ${label(verdict.winner)} — ${verdict.reason}")
            }
        }

        if (judge != null) {
            sb.appendLine()
            sb.appendLine("## 3. Итог судьи")
            sb.appendLine()
            sb.appendLine("| fixed | structure | ничья |")
            sb.appendLine("|---|---|---|")
            sb.appendLine("| $winsA | $winsB | $ties |")
        }

        reportPath.writeText(sb.toString())
        println("Отчёт: $reportPath")
    }

    private fun label(winner: String) = when (winner.uppercase()) {
        "A" -> "победил **fixed**"
        "B" -> "победил **structure**"
        else -> "ничья"
    }

    private fun statRows(a: DocumentIndex, b: DocumentIndex): List<Triple<String, String, String>> {
        fun stats(idx: DocumentIndex): List<String> {
            val sizes = idx.chunks.map { it.text.length }.sorted()
            return listOf(
                idx.chunks.size.toString(),
                sizes.average().toInt().toString(),
                sizes[sizes.size / 2].toString(),
                "${sizes.first()} / ${sizes.last()}",
                idx.chunks.count { it.section != null }.toString(),
            )
        }
        val sa = stats(a); val sb = stats(b)
        val names = listOf("чанков всего", "средний размер, символов", "медиана, символов", "мин / макс", "чанков с метаданным section")
        return names.indices.map { Triple(names[it], sa[it], sb[it]) }
    }
}
