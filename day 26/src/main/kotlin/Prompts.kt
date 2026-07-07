import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * 4 запроса разной сложности (задание требует минимум 3).
 * У каждого — программная проверка ответа, чтобы в отчёте была не только
 * «модель что-то сказала», а честные ✓/✗.
 */
data class Task(
    val id: String,
    val level: String,
    val prompt: String,
    val system: String? = null,
    /** Возвращает null, если ответ прошёл проверку, иначе — что не так. */
    val check: (String) -> String?,
)

private val lenientJson = Json { ignoreUnknownKeys = true }

/** Модели любят заворачивать JSON в ```json ... ``` — срезаем ограду перед парсингом. */
fun stripCodeFence(text: String): String {
    val trimmed = text.trim()
    if (!trimmed.startsWith("```")) return trimmed
    return trimmed
        .removePrefix("```json").removePrefix("```")
        .removeSuffix("```")
        .trim()
}

val TASKS = listOf(
    Task(
        id = "fact",
        level = "1 — простой факт",
        // Вопрос с подвохом: частый неверный ответ — «Сидней».
        prompt = "Какой город является столицей Австралии? Ответь одним словом.",
        check = { answer ->
            if (answer.contains("Канберра", ignoreCase = true) ||
                answer.contains("Canberra", ignoreCase = true)
            ) null else "ожидалась Канберра, получено: «${answer.take(80)}»"
        },
    ),
    Task(
        id = "code",
        level = "2 — генерация кода",
        prompt = "Напиши на Kotlin функцию isPalindrome(s: String): Boolean, которая " +
            "игнорирует регистр и все символы, кроме букв и цифр. Верни только код без пояснений.",
        check = { answer ->
            val code = stripCodeFence(answer)
            when {
                !code.contains("fun isPalindrome") -> "нет функции isPalindrome"
                !code.contains("Boolean") -> "нет типа Boolean"
                else -> null
            }
        },
    ),
    Task(
        id = "logic",
        level = "3 — логическая задача",
        // Аналог классической bat-and-ball: интуитивный (неверный) ответ — 10.
        prompt = "Ручка и карандаш вместе стоят 110 рублей. Ручка дороже карандаша " +
            "на 100 рублей. Сколько стоит карандаш? Рассуждай по шагам, " +
            "в конце напиши строку «Ответ: N рублей».",
        check = { answer ->
            val match = Regex("Ответ:\\s*(\\d+)").find(answer)
            when {
                match == null -> "нет строки «Ответ: N»"
                match.groupValues[1] != "5" -> "ожидалось 5, получено ${match.groupValues[1]}"
                else -> null
            }
        },
    ),
    Task(
        id = "json",
        level = "4 — структурированное извлечение",
        system = "Ты извлекаешь данные из текста. Отвечай строго одним JSON-объектом без пояснений.",
        prompt = "Извлеки из текста JSON с полями name (string), city (string), " +
            "amount (number), currency (string), date (string, ISO 8601):\n\n" +
            "«12 марта 2026 года Иван Петров из Новосибирска оплатил подписку " +
            "на 4 990 рублей со своей основной карты.»",
        check = { answer ->
            try {
                val obj = lenientJson.parseToJsonElement(stripCodeFence(answer)).jsonObject
                val missing = listOf("name", "city", "amount", "currency", "date")
                    .filter { it !in obj }
                when {
                    missing.isNotEmpty() -> "нет полей: $missing"
                    // Принимаем и «2026-03-12», и datetime-вариант — оба валидный ISO 8601.
                    !obj.getValue("date").jsonPrimitive.content.startsWith("2026-03-12") ->
                        "неверная дата: ${obj["date"]}"
                    else -> null
                }
            } catch (e: Exception) {
                "ответ не парсится как JSON: ${e.message?.take(80)}"
            }
        },
    ),
)
