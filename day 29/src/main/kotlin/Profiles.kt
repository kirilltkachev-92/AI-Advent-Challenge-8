import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Матрица оптимизации — каждый следующий профиль добавляет один шаг из задания:
 *
 *  baseline  — модель Дней 26–28 как есть: дефолты Ollama, «наивный» промпт;
 *  params    — + параметры: temperature 0, seed, num_ctx под задачу, num_predict;
 *  prompt    — + prompt-шаблон под кейс: схема, enum тем, правила, few-shot, format=json;
 *  quant     — те же веса 14b, но квант q3_K_M (7.0 ГБ вместо 9.0);
 *  mini      — qwen2.5:0.5b (0.4 ГБ) — крайняя точка «ресурсы против качества».
 *
 * Все настройки — на нашей стороне (опции запроса), никакого GUI.
 */
data class Profile(
    val key: String,
    val label: String,
    val model: String,
    val options: JsonObject?,
    val jsonMode: Boolean,
    val system: String,
    val user: (Case) -> String,
)

object Profiles {
    /**
     * num_ctx 2048: промпт кейса ≈ 700–1100 токенов, дефолтные 4096 держат
     * вдвое больший KV-кэш впустую. num_predict 256: карточка длиннее не бывает,
     * а болтливость дефолта режется на корню. seed — воспроизводимость.
     */
    private val tuned = buildJsonObject {
        put("temperature", 0)
        put("seed", 7)
        put("num_ctx", 2048)
        put("num_predict", 256)
    }

    private const val NAIVE_SYSTEM = "Ты полезный ассистент."

    private fun naiveUser(case: Case) =
        "Вот сообщение с заданием дня из Telegram-чата AI Advent Challenge:\n\n" +
            case.text +
            "\n\nИзвлеки из него информацию о задании и верни JSON с полями: day, title, theme, result, format."

    private val TUNED_SYSTEM = """
        Ты — парсер объявлений ежедневного челленджа по ИИ. На вход приходит сообщение
        «🔥 День N. …» из Telegram, на выход — ровно один JSON-объект без markdown,
        без пояснений и без лишних полей:

        {"day": <номер дня, число>,
         "title": "<название задания из первой строки, без слова День и номера>",
         "theme": "<одна тема из списка ниже>",
         "result": "<формулировка из раздела Результат: дословно>",
         "format": "<формат сдачи из раздела Формат:, без P.S. и ссылок>"}

        Темы (выбери строго одну):
        api — основы работы с LLM через API (запросы, форматы ответа, температура, версии моделей);
        agents — устройство агента как сущности поверх LLM;
        context — токены и управление контекстом (сжатие, стратегии);
        memory — модель памяти и персонализация ассистента;
        state — состояние задачи, инварианты, переходы (state machine);
        mcp — всё про MCP: подключение, инструменты, оркестрация, планировщики;
        rag — индексация, поиск, реранкинг, цитаты, RAG-чаты;
        local — локальные LLM: запуск, интеграция, локальный RAG.

        Пример.
        Вход:
        🔥 День 6. Первый агент
        Реализуйте простого агента, который:
        👉 принимает запрос пользователя
        👉 отправляет его в LLM через API
        Результат:
        Агент принимает запрос и корректно вызывает LLM через API
        Формат:
        Видео + Код
        P.S. Дублирую видео ссылкой - https://…

        Выход:
        {"day": 6, "title": "Первый агент", "theme": "agents", "result": "Агент принимает запрос и корректно вызывает LLM через API", "format": "Видео + Код"}
    """.trimIndent()

    private fun tunedUser(case: Case) = case.text

    fun matrix(): List<Profile> = listOf(
        Profile(
            key = "baseline",
            label = "База: дефолты Ollama + наивный промпт",
            model = Config.baseModel(),
            options = null,
            jsonMode = false,
            system = NAIVE_SYSTEM,
            user = ::naiveUser,
        ),
        Profile(
            key = "params",
            label = "+ параметры (temp 0, seed, num_ctx 2048, num_predict 256)",
            model = Config.baseModel(),
            options = tuned,
            jsonMode = false,
            system = NAIVE_SYSTEM,
            user = ::naiveUser,
        ),
        Profile(
            key = "prompt",
            label = "+ prompt-шаблон под кейс (схема, enum, few-shot, format=json)",
            model = Config.baseModel(),
            options = tuned,
            jsonMode = true,
            system = TUNED_SYSTEM,
            user = ::tunedUser,
        ),
        Profile(
            key = "quant",
            label = "квант q3_K_M (те же веса, оптимальный промпт)",
            model = Config.quantModel(),
            options = tuned,
            jsonMode = true,
            system = TUNED_SYSTEM,
            user = ::tunedUser,
        ),
        Profile(
            key = "mini",
            label = "мини-модель 0.5b (оптимальный промпт)",
            model = Config.miniModel(),
            options = tuned,
            jsonMode = true,
            system = TUNED_SYSTEM,
            user = ::tunedUser,
        ),
    )
}
