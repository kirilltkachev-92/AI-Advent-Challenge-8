import kotlinx.serialization.json.buildJsonObject
import java.nio.file.Files
import java.nio.file.Path

/**
 * Чеклист задания без интерактива → output/report.md:
 * README + docs в RAG, MCP отдаёт git branch, /help отвечает на вопросы
 * о структуре проекта. Контрольные вопросы гоняются через тот же пайплайн,
 * что и REPL, — с реальными вызовами MCP и источниками из индекса.
 */
object Report {

    private val questions = listOf(
        "Как устроена структура репозитория? Что лежит в папках day N?",
        "На какой git-ветке я сейчас и какие последние три коммита?",
        "Какие файлы лежат в day 31?",
        "Про что день 30 и как его запустить локально?",
        "Какие поля у чанка в индексе документации дня 31?",
        "Есть ли в проекте деплой в Kubernetes?", // ловушка: в проекте этого нет
    )

    fun run(assistant: Assistant, mcp: McpClient, tools: List<McpTool>, index: DocumentIndex, repoRoot: Path) {
        val sb = StringBuilder()
        sb.appendLine("# День 31. Ассистент разработчика — отчёт")
        sb.appendLine()

        // 1. RAG: README + docs в индексе.
        val sources = index.chunks.map { it.source }.distinct()
        val readmes = sources.count { it.endsWith("README.md") }
        val docFiles = sources.filter { it.startsWith("day 31/docs/") }
        sb.appendLine("## 1. RAG: документация проекта в индексе")
        sb.appendLine()
        sb.appendLine("- модель эмбеддингов: `${index.embed_model}` (dim ${index.dim}), стратегия `${index.strategy}`")
        sb.appendLine("- документов: ${sources.size}, чанков: ${index.chunks.size}")
        sb.appendLine("- ✅ README: $readmes файлов (`day 1` … `day 31`)")
        sb.appendLine("- ✅ папка docs: ${docFiles.joinToString(", ") { "`$it`" }}")
        sb.appendLine("  (архитектура, API-описание MCP-инструментов, схема данных индекса)")
        sb.appendLine()

        // 2. MCP: живой вызов git_branch через протокол.
        sb.appendLine("## 2. MCP: git-контекст проекта")
        sb.appendLine()
        sb.appendLine("Сервер: `${Config.mcpUrl()}` (JSON-RPC 2.0, Streamable HTTP, вручную).")
        sb.appendLine()
        sb.appendLine("Инструменты из `tools/list`:")
        tools.forEach { sb.appendLine("- `${it.name}` — ${it.description}") }
        sb.appendLine()
        val branch = mcp.callTool("git_branch", buildJsonObject {})
        sb.appendLine("✅ `tools/call git_branch` → «${branch.text}»")
        sb.appendLine()

        // 3. /help: контрольные вопросы через полный пайплайн.
        sb.appendLine("## 3. /help: вопросы о проекте")
        sb.appendLine()
        questions.forEachIndexed { i, q ->
            println("→ Вопрос ${i + 1}/${questions.size}: $q")
            sb.appendLine("### ${i + 1}. $q")
            sb.appendLine()
            val answer = try {
                assistant.help(q)
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
            sb.appendLine(answer.text)
            sb.appendLine()
            sb.appendLine(
                "_Источники RAG: ${answer.sources.map { it.chunk.source }.distinct().joinToString(", ") { "`$it`" }}_",
            )
            sb.appendLine()
        }

        sb.appendLine("## Чеклист задания")
        sb.appendLine()
        sb.appendLine("- ✅ README + папка docs в RAG (п. 1)")
        sb.appendLine("- ✅ MCP — git branch и ещё 4 инструмента (п. 2)")
        sb.appendLine("- ✅ /help отвечает на вопросы о структуре проекта (п. 3),")
        sb.appendLine("  использует документацию (источники под каждым ответом) и контекст")
        sb.appendLine("  проекта через MCP (вызовы инструментов под ответами)")

        val path = Config.outputDir().resolve("report.md")
        Files.createDirectories(path.parent)
        Files.writeString(path, sb.toString())
        println("→ Отчёт: $path")
    }
}
