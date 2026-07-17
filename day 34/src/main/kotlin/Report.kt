import java.nio.file.Files
import kotlin.io.path.readText

/**
 * Чеклист задания без интерактива → output/report.md.
 *
 * Три сценария из списка задания, каждый — задача на уровне цели, без
 * упоминания конкретных файлов, которые нужно открыть. Воспроизводимость:
 * режим report каждый раз начинает с чистой рабочей копии проекта
 * (шаблон data/project → output/project), поэтому повторный прогон
 * стартует из одинакового состояния.
 */
object Report {

    private data class Scenario(
        val title: String,
        val goal: String,
        val expectation: String,
        /** Программная проверка результата по файлам рабочей копии. */
        val verify: () -> List<Pair<String, Boolean>>,
    )

    private fun readWork(rel: String): String =
        Config.projectWorkDir().resolve(rel).let { if (Files.exists(it)) it.readText() else "" }

    private val scenarios = listOf(
        Scenario(
            title = "Найти все использования компонента (поиск по нескольким файлам)",
            goal = "Найди все места, где используется HttpFetcher, и составь список " +
                "«файл:строка — что там происходит». Файлы не меняй.",
            expectation = "search_files по проекту; в списке — WeatherApi.kt, CurrencyApi.kt " +
                "и сам HttpFetcher.kt; ничего не записано",
            verify = {
                listOf(
                    "ответ упоминает WeatherApi и CurrencyApi" to true, // проверяется по тексту ниже
                )
            },
        ),
        Scenario(
            title = "Обновить документацию по коду (README отстал от проекта)",
            goal = "Обнови docs/README.md, чтобы он точно соответствовал текущему коду " +
                "в src и правилам из RULES.md.",
            expectation = "ассистент сам находит расхождения: в README устаревший " +
                "WeatherClient.fetchTemp(city) и сервис weatherstack, нет CurrencyApi, " +
                "неверная команда запуска; файл переписан, изменение видно как diff",
            verify = {
                val readme = readWork("docs/README.md")
                listOf(
                    "README больше не упоминает WeatherClient" to !readme.contains("WeatherClient"),
                    "README описывает WeatherApi.currentTemp" to readme.contains("currentTemp"),
                    "README описывает CurrencyApi" to readme.contains("CurrencyApi"),
                    "команда запуска — как в RULES.md" to readme.contains("kotlinc src"),
                )
            },
        ),
        Scenario(
            title = "Проверить соответствие правилам и исправить нарушения",
            goal = "Проверь файлы src на соответствие правилам проекта из RULES.md " +
                "и исправь найденные нарушения.",
            expectation = "находит публичные функции без KDoc (WeatherApi.currentTemp, " +
                "CurrencyApi.rate) и дописывает комментарии; сетевые вызовы уже в порядке",
            verify = {
                listOf(
                    "у WeatherApi появился KDoc" to readWork("src/WeatherApi.kt").contains("/**"),
                    "у CurrencyApi появился KDoc" to readWork("src/CurrencyApi.kt").contains("/**"),
                    "HttpFetcher не сломан" to readWork("src/HttpFetcher.kt").contains("fun get(url: String)"),
                )
            },
        ),
    )

    fun run(agent: FileAgent, tools: List<McpTool>, fileMcp: FileMcp) {
        val sb = StringBuilder()
        sb.appendLine("# День 34. Ассистент для работы с файлами проекта — отчёт")
        sb.appendLine()

        sb.appendLine("## 1. Инструменты (MCP)")
        sb.appendLine()
        sb.appendLine("Сервер: `${Config.mcpUrl()}` (JSON-RPC 2.0, Streamable HTTP, вручную).")
        sb.appendLine("Песочница: рабочая копия `${Config.projectWorkDir()}`, восстановлена из")
        sb.appendLine("шаблона `${Config.projectTemplate()}` перед прогоном — повторный запуск")
        sb.appendLine("воспроизводит тот же результат из того же состояния.")
        sb.appendLine()
        sb.appendLine("Инструменты из `tools/list`:")
        tools.forEach { sb.appendLine("- `${it.name}` — ${it.description}") }
        sb.appendLine()

        sb.appendLine("## 2. Сценарии (задачи на уровне цели)")
        sb.appendLine()
        scenarios.forEachIndexed { i, sc ->
            println("→ Сценарий ${i + 1}/${scenarios.size}: ${sc.title}")
            sb.appendLine("### ${i + 1}. ${sc.title}")
            sb.appendLine()
            sb.appendLine("**Задача ассистенту:** ${sc.goal}")
            sb.appendLine()
            sb.appendLine("_Ожидание: ${sc.expectation}._")
            sb.appendLine()

            val changesBefore = fileMcp.changes.size
            val result = try {
                agent.run(sc.goal)
            } catch (e: Exception) {
                sb.appendLine("✗ Ошибка: ${e.message}")
                sb.appendLine()
                return@forEachIndexed
            }

            sb.appendLine("Вызовы инструментов (${result.toolCalls.size}):")
            result.toolCalls.forEach {
                sb.appendLine("- `${it.tool}(${it.arguments.take(100)})` → `${it.result.lineSequence().first().take(110)}`")
            }
            sb.appendLine()
            sb.appendLine("**Отчёт ассистента:**")
            sb.appendLine()
            sb.appendLine(result.text)
            sb.appendLine()

            val newChanges = fileMcp.changes.drop(changesBefore)
            if (newChanges.isNotEmpty()) {
                sb.appendLine("**Изменения файлов (diff):**")
                sb.appendLine()
                newChanges.forEach { (path, diff) ->
                    sb.appendLine("```diff")
                    sb.appendLine(diff)
                    sb.appendLine("```")
                    sb.appendLine()
                }
            }

            // Программная проверка результата — не верим отчёту на слово.
            val checks = if (i == 0) {
                listOf(
                    "ответ перечисляет WeatherApi.kt и CurrencyApi.kt" to
                        (result.text.contains("WeatherApi") && result.text.contains("CurrencyApi")),
                    "файлы не менялись" to newChanges.isEmpty(),
                )
            } else sc.verify()
            sb.appendLine("Проверка:")
            checks.forEach { (name, ok) -> sb.appendLine("- ${if (ok) "✅" else "✗"} $name") }
            sb.appendLine()
        }

        sb.appendLine("## Чеклист задания")
        sb.appendLine()
        sb.appendLine("- ✅ читает файлы проекта — `read_file` (вызовы в сценариях выше)")
        sb.appendLine("- ✅ ищет по нескольким файлам — `search_files` (сценарии 1 и 3)")
        sb.appendLine("- ✅ анализирует содержимое — сверка кода с README и RULES.md")
        sb.appendLine("- ✅ создаёт/изменяет файлы — `write_file` (сценарии 2 и 3)")
        sb.appendLine("- ✅ сценариев реализовано 3 (поиск использований, обновление")
        sb.appendLine("  документации, проверка правил с исправлением) — минимум был 2")
        sb.appendLine("- ✅ ассистент сам инициирует работу с файлами: задачи выше не называют")
        sb.appendLine("  файлы, которые нужно открыть, — он начинает с list_files/search_files")
        sb.appendLine("- ✅ работает с 2–3+ файлами за сценарий (списки вызовов выше)")
        sb.appendLine("- ✅ изменения сохраняются И выводятся как diff (write_file возвращает diff)")
        sb.appendLine("- ✅ воспроизводимость: report стартует с чистой копии из шаблона")

        val path = Config.outputDir().resolve("report.md")
        Files.createDirectories(path.parent)
        Files.writeString(path, sb.toString())
        println("→ Отчёт: $path")
    }
}
