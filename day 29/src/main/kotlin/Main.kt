import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

/**
 * День 29. Оптимизация локальной LLM под конкретную задачу.
 *
 * Задача-кейс: превращать объявления «🔥 День N» из чата челленджа в строгие
 * JSON-карточки {day, title, theme, result, format}. Оптимизируем локальную
 * qwen2.5:14b тремя рычагами из задания — параметры (temperature, num_ctx,
 * num_predict, seed), квантование (q4_K_M против q3_K_M против 0.5b) и
 * prompt-шаблон (схема + enum тем + few-shot + format=json). Качество меряется
 * программно против эталона, скорость — счётчиками Ollama, память — /api/ps.
 * Всё локально; все настройки — на стороне нашего софта (комментарий тьютора),
 * не в GUI. RAG в этом дне не участвует (тоже комментарий тьютора).
 */
fun main() {
    Config.loadDotEnv()
    val ollama = OllamaClient()

    val version = ollama.version() ?: run {
        System.err.println(
            "Ollama не отвечает на ${Config.ollamaBaseUrl()}.\n" +
                "Запустите сервер: ollama serve (или brew services start ollama)",
        )
        return
    }
    println("✓ Ollama $version на ${Config.ollamaBaseUrl()}")

    val disk = ollama.localModels()
    fun diskSize(model: String): Long? =
        disk[model] ?: disk.entries.firstOrNull { it.key == "$model:latest" }?.value

    val profiles = Profiles.matrix().filter { profile ->
        (diskSize(profile.model) != null).also { present ->
            if (!present) System.err.println("! Модель ${profile.model} не скачана (ollama pull ${profile.model}) — профиль «${profile.key}» пропущен.")
        }
    }
    if (profiles.isEmpty()) {
        System.err.println("Ни одной модели матрицы нет локально — нечего оптимизировать.")
        return
    }

    val cases = Cases.load(Config.dataDir()).take(Config.caseLimit())
    val runs = Config.stabilityRuns()
    println("✓ Кейс: объявление дня → JSON-карточка; ${cases.size} контрольных объявлений (Дни ${cases.first().day}–${cases.last().day}), по $runs прогона")
    println("✓ Профили: ${profiles.joinToString { it.key }}")

    val results = profiles.map { profile ->
        println()
        println("━━━ Профиль «${profile.key}» — ${profile.label} [${profile.model}] ━━━")
        ollama.loadedModels().forEach { ollama.unload(it.name) }

        var memory: OllamaClient.LoadedModel? = null
        var firstLoadMs = 0L
        val caseRuns = cases.map { case ->
            val answers = mutableListOf<String>()
            val scores = mutableListOf<Score>()
            val latencies = mutableListOf<Long>()
            val speeds = mutableListOf<Double>()
            val errors = mutableListOf<String>()
            repeat(runs) {
                runCatching { ollama.chat(profile.model, profile.system, profile.user(case), profile.options, profile.jsonMode) }
                    .onSuccess { r ->
                        answers += r.answer
                        scores += Scoring.score(r.answer, case)
                        latencies += r.totalMs
                        speeds += r.tokensPerSec
                        if (memory == null) {
                            firstLoadMs = r.loadMs
                            memory = ollama.loadedModels().firstOrNull { it.name.startsWith(profile.model.substringBefore(':')) }
                        }
                    }
                    .onFailure { errors += (it.message ?: it.javaClass.simpleName).take(200) }
            }
            val first = scores.firstOrNull()
            println()
            println("  задание → ${case.text.lineSequence().first()}")
            when (val answer = answers.firstOrNull()) {
                null -> println("  ответ   → ошибка: ${errors.firstOrNull()}")
                else -> println("  ответ   → " + answer.trim().replace("\n", "\n            "))
            }
            first?.let {
                println("  оценка  → ${it.total}/${Score.MAX} [${it.flags()}], ${latencies.firstOrNull() ?: 0} мс")
            }
            CaseRun(case, answers, scores, latencies, speeds, errors)
        }
        val quality = caseRuns.sumOf { it.firstScore?.total ?: 0 }
        println("  итог: $quality/${cases.size * Score.MAX} баллов, ${caseRuns.avgLatency()} мс в среднем, память ${memory?.sizeBytes.gb()}")
        ProfileResult(profile, caseRuns, memory, diskSize(profile.model), firstLoadMs)
    }

    ollama.loadedModels().forEach { ollama.unload(it.name) }

    val reportPath = Config.outputDir().also { it.createDirectories() }.resolve("report.md")
    reportPath.writeText(Report.render(results, cases.size, runs))
    println()
    val base = results.firstOrNull { it.profile.key == "baseline" }
    val best = results.maxBy { it.quality }
    println(
        "ИТОГ: база ${base?.quality ?: "—"}/${cases.size * Score.MAX} → лучший профиль «${best.profile.key}» ${best.quality}/${cases.size * Score.MAX}",
    )
    println("Отчёт: $reportPath")
}

data class CaseRun(
    val case: Case,
    val answers: List<String>,
    val scores: List<Score>,
    val latencies: List<Long>,
    val speeds: List<Double>,
    val errors: List<String>,
) {
    val firstScore: Score? get() = scores.firstOrNull()
    val identical: Boolean
        get() = answers.size > 1 && answers.map { it.trim().replace(Regex("\\s+"), " ") }.distinct().size == 1
}

data class ProfileResult(
    val profile: Profile,
    val caseRuns: List<CaseRun>,
    val memory: OllamaClient.LoadedModel?,
    val diskBytes: Long?,
    val loadMs: Long,
) {
    val quality: Int get() = caseRuns.sumOf { it.firstScore?.total ?: 0 }
}

fun List<CaseRun>.avgLatency(): Long =
    flatMap { it.latencies }.let { if (it.isEmpty()) 0 else it.sum() / it.size }

fun Long?.gb(): String = if (this == null || this <= 0) "—" else "%.1f ГБ".format(this / 1e9)
