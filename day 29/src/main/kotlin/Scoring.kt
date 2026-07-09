import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Программная оценка ответа против эталона, 6 баллов на кейс:
 *
 *  1. строгий JSON — ответ парсится как есть, без ```fences``` и пояснений;
 *  2. day       — номер дня совпал;
 *  3. title     — название задания совпало (нормализованное сравнение);
 *  4. theme     — тема из фиксированного enum угадана точно;
 *  5. result    — строка раздела «Результат:» найдена (совпадение ≥60% слов);
 *  6. format    — формат сдачи назван (видео и код).
 *
 * Поля 2–6 оцениваются и при нестрогом JSON (вырезаем блок из markdown):
 * иначе базовый профиль получил бы ноль за всё из-за одной обёртки ```json.
 */
data class Score(
    val strictJson: Boolean,
    val day: Boolean,
    val title: Boolean,
    val theme: Boolean,
    val result: Boolean,
    val format: Boolean,
) {
    val total: Int
        get() = listOf(strictJson, day, title, theme, result, format).count { it }

    /** J/D/T/Θ/R/F — какой балл взят; точка — потерян. */
    fun flags(): String = buildString {
        append(if (strictJson) "J" else "·")
        append(if (day) "D" else "·")
        append(if (title) "T" else "·")
        append(if (theme) "Θ" else "·")
        append(if (result) "R" else "·")
        append(if (format) "F" else "·")
    }

    companion object {
        const val MAX = 6
    }
}

object Scoring {
    private val json = Json { ignoreUnknownKeys = true }

    fun score(answer: String, case: Case): Score {
        val strict = parse(answer.trim()) != null
        val obj = parse(answer.trim()) ?: parse(extractJsonBlock(answer)) ?: return Score(
            strictJson = false, day = false, title = false, theme = false, result = false, format = false,
        )
        val day = obj["day"]?.jsonPrimitive?.let { it.intOrNull ?: it.contentOrNull?.trim()?.toIntOrNull() }
        val title = str(obj, "title")
        val theme = str(obj, "theme")
        val result = str(obj, "result")
        val format = str(obj, "format")
        return Score(
            strictJson = strict,
            day = day == case.day,
            title = titleMatches(title, case.gold.title),
            theme = theme?.trim()?.lowercase() == case.gold.theme,
            result = wordOverlap(case.gold.result, result ?: "") >= 0.6,
            format = (format ?: "").lowercase().let { it.contains("видео") && it.contains("код") },
        )
    }

    private fun str(obj: JsonObject, key: String): String? =
        runCatching { obj[key]?.jsonPrimitive?.contentOrNull }.getOrNull()

    private fun parse(text: String): JsonObject? = runCatching {
        json.parseToJsonElement(text).jsonObject
    }.getOrNull()

    /** Вырезает JSON из markdown-обёртки или окружающего текста: первая { … последняя }. */
    private fun extractJsonBlock(answer: String): String {
        val start = answer.indexOf('{')
        val end = answer.lastIndexOf('}')
        return if (start in 0 until end) answer.substring(start, end + 1) else ""
    }

    private fun normalize(s: String): String =
        s.lowercase().replace(Regex("[^\\p{L}\\p{Nd}]+"), " ").trim()

    private fun titleMatches(answer: String?, gold: String): Boolean {
        if (answer == null) return false
        val a = normalize(answer)
        val g = normalize(gold)
        return a.isNotEmpty() && (a == g || a.contains(g) || g.contains(a))
    }

    private fun wordOverlap(gold: String, answer: String): Double {
        val goldWords = normalize(gold).split(" ").filter { it.length > 2 }.toSet()
        if (goldWords.isEmpty()) return 0.0
        val answerWords = normalize(answer).split(" ").toSet()
        return goldWords.count { it in answerWords }.toDouble() / goldWords.size
    }
}
