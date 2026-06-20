import kotlinx.serialization.Serializable

/**
 * Профиль пользователя — ядро персонализации (день 12).
 *
 * Профиль живёт ОТДЕЛЬНО от модели памяти (краткосрочная/рабочая/долговременная) и
 * подмешивается в КАЖДЫЙ запрос. В отличие от слоёв памяти, которые наполняет роутер
 * автоматически по ходу диалога, профиль задаётся пользователем явно: интервью при
 * первом запуске и команды. Это «кто пользователь и как он хочет, чтобы с ним общались».
 *
 * Предпочтения сгруппированы так, как в лекции: стиль, формат и ограничения,
 * плюс контекст (кто пользователь и зачем пришёл).
 */
@Serializable
data class UserProfile(
    val id: String,
    val name: String,
    val context: Context = Context(),
    val style: Style = Style(),
    val format: Format = Format(),
    /** Ограничения: что беречь / чего нельзя / что обязательно. Соблюдаются строго. */
    val constraints: List<String> = emptyList(),
) {
    /** Короткая подпись для статус-строки: что именно ассистент учитывает прямо сейчас. */
    fun shortLabel(): String =
        listOf(style.verbosity, style.tone, format.structure)
            .filter { it.isNotBlank() }
            .joinToString(", ")
}

/** Контекст: кто пользователь и какова цель. Лекционная третья группа персонализации. */
@Serializable
data class Context(
    val who: String = "",
    val goal: String = "",
    val level: String = "",
    val equipment: String = "",
)

/** Стиль общения: насколько подробно, в каком тоне, с терминами или без. */
@Serializable
data class Style(
    /** кратко | сбалансированно | подробно */
    val verbosity: String = "сбалансированно",
    /** дружелюбно | нейтрально | строго по делу */
    val tone: String = "нейтрально",
    /** избегать терминов | по ситуации | можно термины */
    val jargon: String = "по ситуации",
    val emoji: Boolean = false,
)

/** Формат ответа: как структурировать и насколько длинно, давать ли конкретные цифры. */
@Serializable
data class Format(
    /** по шагам | списком | сплошным текстом */
    val structure: String = "списком",
    /** короткий | средний | развёрнутый */
    val length: String = "средний",
    /** давать конкретные цифры: подходы×повторы, %, RPE, время */
    val numbers: Boolean = true,
)
