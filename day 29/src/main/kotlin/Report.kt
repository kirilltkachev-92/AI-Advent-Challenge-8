/** Markdown-отчёт: сводная матрица оптимизации + разбор по кейсам. */
object Report {
    fun render(results: List<ProfileResult>, caseCount: Int, runs: Int): String = buildString {
        val maxScore = caseCount * Score.MAX

        appendLine("# День 29 — оптимизация локальной LLM под конкретную задачу")
        appendLine()
        appendLine(
            "Кейс: объявление «🔥 День N» из чата челленджа → строгая JSON-карточка " +
                "`{day, title, theme, result, format}` (theme — классификация по 8 темам марафона). " +
                "$caseCount контрольных объявлений, по $runs прогона на профиль; качество меряется " +
                "программно против эталона (${Score.MAX} баллов на кейс: строгий JSON, day, title, theme, result, format). " +
                "Скорость — счётчики Ollama (`eval_count/eval_duration`), память — `/api/ps` после загрузки модели. " +
                "Никакого облака и никакого RAG (по уточнению тьютора); все настройки — параметрами запроса " +
                "на стороне нашего кода, не в GUI (тоже уточнение тьютора).",
        )
        appendLine()
        appendLine("## Матрица оптимизации")
        appendLine()
        appendLine("| профиль | модель | качество (из $maxScore) | строгий JSON | тема угадана | латентность ср | ток/с | RAM | диск | стабильность | ошибки |")
        appendLine("|---|---|---|---|---|---|---|---|---|---|---|")
        results.forEach { r ->
            val strict = r.caseRuns.count { it.firstScore?.strictJson == true }
            val theme = r.caseRuns.count { it.firstScore?.theme == true }
            val tps = r.caseRuns.flatMap { it.speeds }.filter { it > 0 }
            appendLine(
                "| **${r.profile.key}** — ${r.profile.label} | `${r.profile.model}` | **${r.quality}** " +
                    "| $strict/${r.caseRuns.size} | $theme/${r.caseRuns.size} " +
                    "| ${r.caseRuns.avgLatency()} мс | ${if (tps.isEmpty()) "—" else "%.1f".format(tps.average())} " +
                    "| ${r.memory?.sizeBytes.gb()} | ${r.diskBytes.gb()} " +
                    "| ${r.caseRuns.count { it.identical }}/${r.caseRuns.size} идентичны " +
                    "| ${r.caseRuns.sumOf { it.errors.size }} |",
            )
        }
        appendLine()
        appendLine("Что менялось между профилями:")
        appendLine()
        appendLine("- **baseline → params**: только опции запроса — `temperature 0`, `seed 7`, `num_ctx 2048` (вместо дефолтных 4096 — промпт короче), `num_predict 256`.")
        appendLine("- **params → prompt**: prompt-шаблон под кейс — точная схема полей, enum из 8 тем с определениями, правила («только JSON, без markdown»), few-shot пример и `format=json` на уровне API.")
        appendLine("- **prompt → quant**: те же веса Qwen 2.5 14B, но квант q3_K_M вместо q4_K_M — меньше диска и RAM, быстрее токены, вопрос в цене по качеству.")
        appendLine("- **prompt → mini**: qwen2.5:0.5b — крайняя точка оси «ресурсы против качества».")
        appendLine()

        appendLine("## Разбор по кейсам (баллы из ${Score.MAX}: JSON/day/title/theme/result/format)")
        appendLine()
        append("| день |")
        results.forEach { append(" ${it.profile.key} |") }
        appendLine()
        append("|---|")
        results.forEach { _ -> append("---|") }
        appendLine()
        val caseCountActual = results.first().caseRuns.size
        (0 until caseCountActual).forEach { i ->
            val case = results.first().caseRuns[i].case
            append("| День ${case.day} — ${case.gold.title} |")
            results.forEach { r ->
                val s = r.caseRuns[i].firstScore
                append(" ${s?.let { "${it.total} ${it.flags()}" } ?: "ошибка"} |")
            }
            appendLine()
        }
        appendLine()
        appendLine("Обозначения: J — строгий JSON, D — day, T — title, Θ — theme, R — result, F — format; точка — балл потерян.")
        appendLine()

        appendLine("## Что спрашивали и что ответили")
        appendLine()
        (0 until caseCountActual).forEach { i ->
            val case = results.first().caseRuns[i].case
            appendLine("### День ${case.day} — ${case.gold.title}")
            appendLine()
            appendLine("<details><summary>Вход — объявление из чата</summary>")
            appendLine()
            appendLine("```")
            appendLine(case.text)
            appendLine("```")
            appendLine()
            appendLine("</details>")
            appendLine()
            results.forEach { r ->
                val run = r.caseRuns[i]
                val score = run.firstScore
                appendLine("<details><summary>Ответ «${r.profile.key}» — ${score?.let { "${it.total}/${Score.MAX} [${it.flags()}]" } ?: "ошибка"}</summary>")
                appendLine()
                appendLine("```")
                appendLine(run.answers.firstOrNull() ?: "все запросы упали: ${run.errors.firstOrNull()}")
                appendLine("```")
                appendLine()
                appendLine("</details>")
                appendLine()
            }
        }
    }
}
