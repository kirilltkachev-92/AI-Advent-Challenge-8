import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

/**
 * День 26. Локальная LLM (Ollama) через HTTP API.
 *
 * 1. Проверяем, что сервер запущен и модели скачаны (/api/version, /api/tags).
 * 2. Гоним 4 запроса разной сложности к основной модели и, для сравнения,
 *    к маленькой — с программной проверкой каждого ответа.
 * 3. Пишем отчёт с метриками (токены, скорость, латентность) в output/report.md.
 */
fun main() {
    Config.loadDotEnv()
    val client = OllamaClient()
    val models = listOf(Config.chatModel(), Config.smallModel())

    // --- Шаг 1: сервер жив и модели на месте -------------------------------
    val version = client.version() ?: run {
        System.err.println(
            "Ollama не отвечает на ${Config.ollamaBaseUrl()}.\n" +
                "Запустите сервер: ollama serve (или brew services start ollama)",
        )
        return
    }
    println("✓ Ollama $version на ${Config.ollamaBaseUrl()}")

    val local = client.localModels()
    models.forEach { model ->
        if (local.none { it == model || it.startsWith("$model:") }) {
            System.err.println("Модель $model не скачана. Выполните: ollama pull $model")
            return
        }
    }
    println("✓ Модели на месте: ${models.joinToString()} (всего локально: ${local.size})")

    // --- Шаг 2: запросы разной сложности ------------------------------------
    data class Run(val task: Task, val model: String, val result: OllamaClient.ChatResult, val problem: String?)

    val runs = mutableListOf<Run>()
    models.forEach { model ->
        println("\n=== $model ===")
        TASKS.forEach { task ->
            println("\n[${task.level}]")
            println("Промпт: ${task.prompt}")
            val result = client.chat(model, task.prompt, task.system)
            val problem = task.check(result.answer)
            val mark = if (problem == null) "✓" else "✗ ($problem)"
            println(result.answer)
            println(
                "→ $mark | ${result.answerTokens} токенов за ${result.evalMs} мс " +
                    "(%.1f ток/с), весь запрос ${result.totalMs} мс".format(result.tokensPerSec),
            )
            runs += Run(task, model, result, problem)
        }
    }

    // --- Шаг 3: отчёт --------------------------------------------------------
    val report = buildString {
        appendLine("# День 26. Локальная LLM: отчёт прогона")
        appendLine()
        appendLine("- Сервер: Ollama $version, `${Config.ollamaBaseUrl()}` (HTTP API, протокол вручную)")
        appendLine("- Модели: `${models.joinToString("`, `")}`, temperature=0")
        appendLine()
        appendLine("## Сводка")
        appendLine()
        appendLine("| Запрос | Модель | Проверка | Токены ответа | Генерация, мс | Ток/с | Всего, мс |")
        appendLine("|---|---|---|---:|---:|---:|---:|")
        runs.forEach { run ->
            val mark = if (run.problem == null) "✓" else "✗ ${run.problem}"
            appendLine(
                "| ${run.task.level} | ${run.model} | $mark | ${run.result.answerTokens} " +
                    "| ${run.result.evalMs} | ${"%.1f".format(run.result.tokensPerSec)} | ${run.result.totalMs} |",
            )
        }
        appendLine()
        appendLine("## Полные ответы")
        runs.forEach { run ->
            appendLine()
            appendLine("### ${run.task.level} — ${run.model}")
            appendLine()
            appendLine("**Промпт:** ${run.task.prompt}")
            appendLine()
            appendLine("```")
            appendLine(run.result.answer)
            appendLine("```")
        }
    }
    val reportPath = Config.outputDir().also { it.createDirectories() }.resolve("report.md")
    reportPath.writeText(report)

    val passed = runs.count { it.problem == null }
    println("\nИтог: $passed/${runs.size} проверок пройдено, отчёт: $reportPath")
}
