import kotlinx.serialization.json.Json
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Path
import kotlin.io.path.writeText

/**
 * Проверка Дня 25: два длинных сценария по 12–13 сообщений через тот же ChatSession,
 * что и интерактивный чат. Проверяется:
 *  - что ответы на вопросы по базе ВСЕГДА идут с источниками (кодом);
 *  - что ассистент не теряет цель диалога (судья сравнивает цель сценария
 *    с памятью задачи в конце и с финальным ответом-резюме);
 *  - что «не знаю» и мета-вопросы (цель/резюме) обрабатываются корректно.
 * Результат — output/scenarios.md с полными транскриптами и эволюцией памяти задачи.
 */
object Scenarios {
    private val json = Json { ignoreUnknownKeys = true }

    data class Scenario(val title: String, val goal: String, val messages: List<String>)

    // Сценарий 1: доклад про few-shot (EN-вопросы, follow-up'ы с местоимениями,
    // смена ограничений на ходу, мета-вопрос про цель, финальное резюме).
    private val SCENARIO_1 = Scenario(
        title = "Готовлю доклад о few-shot возможностях GPT-3",
        goal = "Подготовить доклад о few-shot способностях GPT-3 с точными цифрами из статьи",
        messages = listOf(
            "Привет! Я готовлю доклад о few-shot возможностях GPT-3 по оригинальной статье. Отвечай кратко и всегда с точными цифрами.",
            "What do zero-shot, one-shot and few-shot mean in the paper?",
            "And how many examples K do they typically fit into the context?",
            "Какой размер контекстного окна это ограничивает?",
            "Now the results: how good is few-shot GPT-3 on LAMBADA?",
            "А на переводах?",
            "Wait, which of those translation directions were strongest and why?",
            "Зафиксируй ограничение: в докладе я сравниваю только few-shot с fine-tuned SOTA, zero-shot не нужен.",
            "Ок. Как few-shot GPT-3 соотносится с fine-tuned SOTA на SuperGLUE?",
            "What about arithmetic — how well does few-shot GPT-3 add numbers?",
            "Напомни, какая у нас цель и что мы уже зафиксировали?",
            "Суммируй в 5 пунктов всё, что мы собрали для доклада, с цифрами.",
        ),
    )

    // Сценарий 2: блог-пост о рисках и ограничениях (RU-вопросы, ловушка мимо базы,
    // возврат к теме после отвлечения, вопрос про ограничения из памяти, резюме).
    private val SCENARIO_2 = Scenario(
        title = "Пишу блог-пост о рисках и ограничениях GPT-3",
        goal = "Собрать материал для блог-поста о рисках и ограничениях GPT-3 по статье",
        messages = listOf(
            "Привет! Пишу блог-пост о рисках и ограничениях GPT-3. Мне нужны факты строго из статьи, с указанием разделов.",
            "Какие ограничения GPT-3 признают сами авторы?",
            "Что там с 'common sense physics' — в чём именно трудность?",
            "Какие сценарии злоупотребления (misuse) обсуждаются в статье?",
            "А что известно про гендерные предубеждения модели?",
            "Какая столица Франции?",
            "Ладно, вернёмся к теме. Что в статье говорится про энергопотребление?",
            "Сколько людей отличают тексты GPT-3 от человеческих? Это же тоже риск.",
            "Зафиксируй термин: 'данные-загрязнение' = data contamination, буду использовать его в посте.",
            "Как авторы боролись с этим данные-загрязнением?",
            "Какие ограничения и термины мы уже зафиксировали для поста?",
            "Составь короткий план блог-поста из 4 разделов на основе всего, что мы обсудили.",
        ),
    )

    private data class Turn(
        val userMessage: String,
        val answer: StructuredAnswer,
        val sourcesOk: Boolean, // для вопросов по базе: источники есть и валидны
    )

    fun run(makeSession: () -> ChatSession, judgeLlm: DeepSeekClient, reportPath: Path) {
        val results = listOf(SCENARIO_1, SCENARIO_2).map { sc -> runScenario(sc, makeSession()) }

        val sb = StringBuilder()
        sb.appendLine("# День 25 — мини-чат с RAG + памятью: два длинных сценария")
        sb.appendLine()
        sb.appendLine("Оба сценария прогоняются через тот же `ChatSession`, что и интерактивный чат: " +
            "история диалога, RAG на каждый вопрос, обязательные источники, память задачи, «не знаю» при слабом контексте.")
        sb.appendLine()

        results.forEachIndexed { i, (sc, turns, finalState) ->
            val knowledge = turns.filter { it.answer.trace.needsRetrieval && it.answer.canAnswer }
            val refusals = turns.filter { !it.answer.canAnswer }
            val metas = turns.filter { it.answer.meta }
            val (goalScore, goalWhy) = judgeGoal(judgeLlm, sc, finalState, turns.last())

            println()
            println("ИТОГ сценария ${i + 1}: ответов по базе ${knowledge.size}, все с источниками: " +
                "${knowledge.count { it.sourcesOk }}/${knowledge.size}, мета-ответов ${metas.size}, отказов ${refusals.size}, " +
                "цель удержана: $goalScore/2 ($goalWhy)")

            sb.appendLine("## Сценарий ${i + 1}. ${sc.title} (${sc.messages.size} сообщений)")
            sb.appendLine()
            sb.appendLine("**Цель сценария:** ${sc.goal}")
            sb.appendLine()
            sb.appendLine("| проверка | результат |")
            sb.appendLine("|---|---|")
            sb.appendLine("| ответы по базе с источниками | ${knowledge.count { it.sourcesOk }}/${knowledge.size} |")
            sb.appendLine("| мета-ответы (из памяти диалога) | ${metas.size} |")
            sb.appendLine("| отказы «не знаю» | ${refusals.size} |")
            sb.appendLine("| цель удержана к концу диалога (судья) | $goalScore/2 — $goalWhy |")
            sb.appendLine()
            sb.appendLine("**Память задачи в конце диалога:**")
            sb.appendLine()
            sb.appendLine("```")
            sb.appendLine(finalState.render())
            sb.appendLine("```")
            sb.appendLine()
            sb.appendLine("### Транскрипт")
            turns.forEachIndexed { t, turn ->
                sb.appendLine()
                sb.appendLine("**${t + 1}. Пользователь:** ${turn.userMessage}")
                sb.appendLine()
                val a = turn.answer
                if (!a.canAnswer) {
                    sb.appendLine("**Ассистент (отказ):** ${a.clarification}")
                } else {
                    sb.appendLine("**Ассистент${if (a.meta) " (мета, из памяти диалога)" else ""}:** ${a.answer.trim()}")
                    if (a.sources.isNotEmpty()) {
                        sb.appendLine()
                        sb.appendLine("*Источники:* " + a.sources.joinToString("; ") { "`${it.chunkId}` («${it.section}»)" })
                    }
                }
            }
            sb.appendLine()
        }
        reportPath.writeText(sb.toString())
        println()
        println("Отчёт: $reportPath")
    }

    private fun runScenario(sc: Scenario, session: ChatSession): Triple<Scenario, List<Turn>, TaskState> {
        println()
        println("━━━━━━ Сценарий: ${sc.title} ━━━━━━")
        val turns = sc.messages.mapIndexed { i, msg ->
            println()
            println("[${i + 1}/${sc.messages.size}] Пользователь: $msg")
            val a = session.ask(msg)
            if (!a.canAnswer) {
                println("Ассистент (ОТКАЗ): ${a.clarification}")
            } else {
                println("Ассистент${if (a.meta) " (мета)" else ""}: ${a.answer.trim()}")
                if (a.sources.isNotEmpty()) {
                    println("Источники: " + a.sources.joinToString("; ") { "«${it.section}»" })
                }
            }
            val sourcesOk = !a.trace.needsRetrieval || !a.canAnswer ||
                (a.sources.isNotEmpty() && Validator.validate(a).sourcesValid)
            Turn(msg, a, sourcesOk)
        }
        println()
        println("Память задачи в конце:")
        println(session.state.render())
        return Triple(sc, turns, session.state)
    }

    /** Судья: удержал ли ассистент цель диалога (по памяти задачи и финальному резюме). */
    private fun judgeGoal(llm: DeepSeekClient, sc: Scenario, state: TaskState, lastTurn: Turn): Pair<Int, String> = try {
        val system = "Ты проверяешь диалогового ассистента. Дана ЦЕЛЬ сценария, память задачи ассистента " +
            "в конце диалога и его финальный ответ. Оцени: 2 — цель сохранена (память отражает цель, " +
            "финальный ответ работает на неё); 1 — частично; 0 — цель потеряна. " +
            "Ответь строго JSON: {\"score\": 0|1|2, \"why\": \"кратко по-русски\"}."
        val user = "Цель сценария: ${sc.goal}\n\nПамять задачи в конце:\n${state.render()}\n\n" +
            "Финальный ответ ассистента:\n${(if (lastTurn.answer.canAnswer) lastTurn.answer.answer else lastTurn.answer.clarification).take(1200)}"
        val obj = json.parseToJsonElement(llm.chat(system, user, jsonMode = true)).jsonObject
        (obj["score"]?.jsonPrimitive?.intOrNull ?: 0) to (obj["why"]?.jsonPrimitive?.content ?: "")
    } catch (e: Exception) {
        0 to "судья недоступен: ${e.message}"
    }
}
