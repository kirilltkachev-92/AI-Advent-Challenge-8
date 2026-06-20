import java.util.concurrent.Callable
import java.util.concurrent.Executors

/**
 * Агент — как класс в ЯП (по формулировке Алексея Гладкова): у него есть имя, роль,
 * входные/выходные условия (через [run]), свой system prompt и клиент LLM. Из одного «класса»
 * агента можно поднять сколько угодно ИНСТАНСОВ и запускать их параллельно.
 */
class Agent(
    val name: String,
    val role: String,
    private val systemPrompt: String,
    private val client: ChatClient,
) {
    /**
     * Один запуск агента: единый system prompt (роль продукта + специализация + профиль) и
     * входные данные этапа. Возвращает артефакт-кусок и метрики.
     */
    fun run(input: String, profileBlock: String): AgentRun {
        val system = buildString {
            append(Config.PRODUCT_PROMPT)
            append("\n\n").append(systemPrompt)
            if (profileBlock.isNotBlank()) append("\n\n").append(profileBlock)
        }
        val messages = listOf(
            ChatMessage(role = "system", content = system),
            ChatMessage(role = "user", content = input),
        )
        val result = runCatching { client.chat(messages) }.getOrElse {
            return AgentRun(name, role, "⨯ ошибка агента: ${it.message}", 0, 0, ok = false)
        }
        return AgentRun(name, role, result.content, result.totalTokens, result.latencyMs, ok = true)
    }
}

/** Результат запуска одного инстанса агента. */
data class AgentRun(
    val name: String,
    val role: String,
    val output: String,
    val tokens: Int,
    val latencyMs: Long,
    val ok: Boolean,
)

/** Запуск роя агентов: несколько инстансов работают ПАРАЛЛЕЛЬНО, результаты собираются. */
object Swarm {
    fun run(tasks: List<() -> AgentRun>): List<AgentRun> {
        if (tasks.isEmpty()) return emptyList()
        if (tasks.size == 1) return listOf(tasks.first().invoke())
        val pool = Executors.newFixedThreadPool(minOf(tasks.size, 6))
        return try {
            pool.invokeAll(tasks.map { Callable(it) }).map { it.get() }
        } finally {
            pool.shutdown()
        }
    }
}
