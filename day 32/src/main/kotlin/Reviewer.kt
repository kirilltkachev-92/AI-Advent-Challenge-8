/** Итог ревью: markdown для комментария в PR + что было найдено retrieval-ом. */
data class ReviewResult(
    val markdown: String,
    val retrievedSources: List<String>,
    val promptTokens: Long,
    val answerTokens: Long,
)

/**
 * Сердце Дня 32: diff PR + RAG-контекст (документация И код репозитория) →
 * DeepSeek → текст ревью с тремя обязательными секциями из задания:
 * потенциальные баги, архитектурные проблемы, рекомендации.
 */
class Reviewer(
    private val index: DocumentIndex,
    private val embedder: OllamaEmbedder,
    private val deepSeek: DeepSeekClient,
) {
    private val systemPrompt = """
        Ты — строгий, но доброжелательный ревьюер кода репозитория AI Advent Challenge #8:
        31+ ежедневный Kotlin/Gradle-проект про работу с LLM. Конвенции марафона:
        протоколы (HTTP, MCP, парсеры) пишутся вручную без фреймворков и SDK,
        каждый день — самостоятельный проект в папке «day N» с README, run.sh
        и выводом в output/. Комментарии и документация — по-русски.

        Тебе дают diff pull request-а, список изменённых файлов и фрагменты
        документации и кода репозитория, найденные поиском (используй их, чтобы
        проверить соответствие конвенциям и поискать похожий код в других днях).

        Напиши ревью в markdown ровно с такими секциями:
        ## 🐛 Потенциальные баги
        ## 🏛 Архитектурные проблемы
        ## 💡 Рекомендации

        В каждой секции — маркированный список конкретных пунктов со ссылками на
        файл и строку из диффа (`файл:строка`). Если в секции пусто — так и напиши
        («не найдено»). Не выдумывай проблем ради количества: лучше три точных
        замечания, чем десять надуманных. В конце — одна строка «**Вердикт:** …»
        (например: можно мержить / нужны правки).
    """.trimIndent()

    fun review(diff: PrDiff): ReviewResult {
        val hits = retrieve(diff)
        val fragments = Search.renderFragments(index, hits)

        val user = buildString {
            appendLine("Изменённые файлы (${diff.files.size}):")
            diff.files.forEach { appendLine("- [${it.status}] ${it.path}") }
            appendLine()
            appendLine("Контекст из документации и кода репозитория (RAG):")
            appendLine(fragments.ifBlank { "(ничего не найдено)" })
            appendLine()
            appendLine("Diff PR:")
            appendLine("```diff")
            appendLine(diff.patch)
            appendLine("```")
        }

        val result = deepSeek.chat(systemPrompt, user)
        return ReviewResult(
            markdown = result.answer,
            retrievedSources = hits.map { it.chunk.source }.distinct(),
            promptTokens = result.promptTokens,
            answerTokens = result.answerTokens,
        )
    }

    /**
     * Retrieval по диффу: один запрос на каждый изменённый файл (путь + добавленные
     * строки — именно новый код должен найти похожие места и правила), плюс общий
     * запрос по всему списку файлов. Хиты объединяются и дедуплицируются по чанку.
     */
    private fun retrieve(diff: PrDiff): List<Hit> {
        val perFileAdded = mutableMapOf<String, StringBuilder>()
        var current: String? = null
        diff.patch.lines().forEach { line ->
            when {
                line.startsWith("+++ b/") -> current = line.removePrefix("+++ b/")
                line.startsWith("+") && !line.startsWith("+++") ->
                    current?.let { perFileAdded.getOrPut(it) { StringBuilder() }.appendLine(line.drop(1)) }
            }
        }

        val queries = diff.files.filter { it.status != "D" }.take(10).map { file ->
            val added = perFileAdded[file.path]?.toString()?.take(600) ?: ""
            "${file.path}\n$added"
        } + listOf("изменения PR: " + diff.files.joinToString(", ") { it.path })

        val seen = mutableSetOf<String>()
        val all = mutableListOf<Hit>()
        queries.forEach { query ->
            Search.topK(index, embedder.embedQuery(query), query, k = 3).forEach { hit ->
                if (seen.add(hit.chunk.chunk_id)) all += hit
            }
        }
        return all.sortedByDescending { it.score }.take(Config.topK() * 2)
    }
}
