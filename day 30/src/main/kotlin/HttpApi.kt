import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.net.InetSocketAddress
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * HTTP-слой сервиса — com.sun.net.httpserver из JDK, без фреймворков.
 *
 * Публичное:  GET /            — веб-чат (одна страница)
 *             GET /healthz     — жив ли сервис (для проверки доступа по сети)
 * По токену:  POST /v1/chat    — {messages:[{role,content}…]} → ответ + источники
 *             GET  /v1/limits  — действующие ограничения сервиса
 *
 * Порядок обороны на /v1/…: 401 (нет токена) → 429 (rate limit) →
 * 413 (сообщение больше max input) → 503 (очередь генераций переполнена).
 */
class HttpApi(private val chat: ChatService, private val ollama: OllamaClient) {

    private val json = Json { ignoreUnknownKeys = true }
    private val tokens = Config.apiTokens()
    private val limiter = RateLimiter(Config.rateLimitPerMin(), Config.rateLimitBurst())

    // Потолок одновременных генераций: LLM на CPU не должна получать 10 запросов разом.
    private val gate = Semaphore(Config.maxConcurrent(), true)
    private val waiting = AtomicInteger(0)

    private val startedAt = System.currentTimeMillis()
    private val served = AtomicLong(0)

    fun start(): HttpServer {
        val server = HttpServer.create(InetSocketAddress(Config.bindHost(), Config.port()), 0)
        server.executor = Executors.newFixedThreadPool(16)

        server.createContext("/") { ex ->
            handle(ex) {
                if (ex.requestURI.path != "/") return@handle send(ex, 404, error("not_found", "Нет такого пути"))
                sendHtml(ex, WebUi.PAGE)
            }
        }
        server.createContext("/healthz") { ex ->
            handle(ex) {
                send(ex, 200, buildJsonObject {
                    put("status", "ok")
                    put("model", Config.chatModel())
                    put("uptime_sec", (System.currentTimeMillis() - startedAt) / 1000)
                    put("requests_served", served.get())
                })
            }
        }
        server.createContext("/v1/limits") { ex ->
            handle(ex) {
                val token = authorized(ex) ?: return@handle
                if (!rateLimited(ex, token)) send(ex, 200, limitsJson())
            }
        }
        server.createContext("/v1/chat") { ex ->
            handle(ex) { handleChat(ex) }
        }

        server.start()
        return server
    }

    private fun handleChat(ex: HttpExchange) {
        if (ex.requestMethod == "OPTIONS") return send(ex, 204, null)
        if (ex.requestMethod != "POST") return send(ex, 405, error("method_not_allowed", "Только POST"))
        val token = authorized(ex) ?: return

        // Rate limit — до всякой работы: дешёвый отказ дешевле дорогой генерации.
        if (rateLimited(ex, token)) return

        val history = parseHistory(ex) ?: return
        val lastUser = history.last()
        if (lastUser.content.length > Config.maxInputChars()) {
            return send(
                ex, 413,
                error(
                    "input_too_long",
                    "Сообщение ${lastUser.content.length} символов — больше max input " +
                        "${Config.maxInputChars()}. Сократите вопрос.",
                ),
            )
        }

        // Очередь к модели: не больше MAX_CONCURRENT генераций, хвост ждёт,
        // а когда ожидающих больше MAX_WAITING — честный 503, а не зависание.
        if (waiting.incrementAndGet() > Config.maxWaiting()) {
            waiting.decrementAndGet()
            return send(ex, 503, error("overloaded", "Очередь запросов переполнена, попробуйте позже"))
        }
        val acquired = try {
            gate.tryAcquire(Config.queueTimeoutMs(), TimeUnit.MILLISECONDS)
        } finally {
            waiting.decrementAndGet()
        }
        if (!acquired) return send(ex, 503, error("overloaded", "Модель занята дольше таймаута очереди"))

        try {
            val answer = chat.answer(history)
            served.incrementAndGet()
            send(ex, 200, buildJsonObject {
                put("answer", answer.text)
                put("model", Config.chatModel())
                put("history_trimmed", answer.historyTrimmed)
                putJsonArray("sources") {
                    answer.sources.forEach { s ->
                        add(buildJsonObject {
                            put("chunk_id", s.chunkId)
                            put("date", s.date)
                            put("score", "%.3f".format(s.score).toDouble())
                            put("preview", s.preview)
                        })
                    }
                }
                putJsonObject("usage") {
                    put("prompt_tokens", answer.promptTokens)
                    put("answer_tokens", answer.answerTokens)
                }
                putJsonObject("timings") {
                    put("total_ms", answer.totalMs)
                    put("tokens_per_sec", "%.1f".format(answer.tokensPerSec).toDouble())
                }
            })
        } finally {
            gate.release()
        }
    }

    /** null — ответ уже отправлен (400). Проверяет и формат, и роли. */
    private fun parseHistory(ex: HttpExchange): List<Msg>? {
        val body = ex.requestBody.readBytes().toString(Charsets.UTF_8)
        val parsed = runCatching {
            val obj = json.parseToJsonElement(body).jsonObject
            obj["message"]?.jsonPrimitive?.content?.let { single ->
                listOf(Msg("user", single))
            } ?: obj.getValue("messages").jsonArray.map {
                val m = it.jsonObject
                Msg(m.getValue("role").jsonPrimitive.content, m.getValue("content").jsonPrimitive.content)
            }
        }.getOrNull()
        val bad = when {
            parsed == null -> "Тело должно быть JSON: {\"message\": \"…\"} или {\"messages\": [{role, content}…]}"
            parsed.isEmpty() -> "Список messages пуст"
            parsed.last().role != "user" -> "Последняя реплика должна быть от role=user"
            parsed.any { it.role !in setOf("user", "assistant") } -> "Роли только user и assistant"
            parsed.last().content.isBlank() -> "Пустой вопрос"
            else -> null
        }
        if (bad != null) {
            send(ex, 400, error("bad_request", bad))
            return null
        }
        return parsed
    }

    /** Общий rate limit всех /v1-маршрутов. true — лимит сработал, 429 уже отправлен. */
    private fun rateLimited(ex: HttpExchange, token: String): Boolean {
        val decision = limiter.check(token)
        if (decision.allowed) return false
        ex.responseHeaders.add("Retry-After", decision.retryAfterSec.toString())
        send(
            ex, 429,
            error(
                "rate_limited",
                "Слишком часто: лимит ${Config.rateLimitPerMin()} запросов/мин " +
                    "(залп до ${Config.rateLimitBurst()}). Повторите через ${decision.retryAfterSec} с.",
            ),
        )
        return true
    }

    /** Возвращает токен запроса или null (401 уже отправлен). */
    private fun authorized(ex: HttpExchange): String? {
        val presented = ex.requestHeaders.getFirst("Authorization")?.removePrefix("Bearer ")?.trim()
            ?: ex.requestHeaders.getFirst("X-API-Key")?.trim()
        val ok = presented != null && tokens.any {
            MessageDigest.isEqual(it.toByteArray(), presented.toByteArray())
        }
        if (!ok) {
            send(
                ex, 401,
                error("unauthorized", "Нужен API-токен: заголовок Authorization: Bearer <токен> или X-API-Key"),
            )
            return null
        }
        return presented
    }

    private fun limitsJson() = buildJsonObject {
        put("rate_limit_per_min", Config.rateLimitPerMin())
        put("rate_limit_burst", Config.rateLimitBurst())
        put("max_context_tokens", Config.numCtx())
        put("max_answer_tokens", Config.maxAnswerTokens())
        put("max_input_chars", Config.maxInputChars())
        put("history_char_budget", Config.historyCharBudget())
        put("max_concurrent_generations", Config.maxConcurrent())
        put("max_queue", Config.maxWaiting())
        put("model", Config.chatModel())
    }

    private fun error(code: String, message: String) = buildJsonObject {
        putJsonObject("error") { put("code", code); put("message", message) }
    }

    /** Общая обёртка: access-лог + любая ошибка становится JSON 500, а не тишиной. */
    private fun handle(ex: HttpExchange, block: () -> Unit) {
        val start = System.nanoTime()
        try {
            block()
        } catch (e: Exception) {
            runCatching { send(ex, 500, error("internal", e.message ?: e.javaClass.simpleName)) }
        } finally {
            val ms = (System.nanoTime() - start) / 1_000_000
            println(
                "%s %s %s ← %s за %d мс".format(
                    java.time.LocalTime.now().withNano(0),
                    ex.requestMethod,
                    ex.requestURI.path,
                    ex.remoteAddress.address.hostAddress,
                    ms,
                ),
            )
            ex.close()
        }
    }

    private fun send(ex: HttpExchange, status: Int, body: kotlinx.serialization.json.JsonObject?) {
        val bytes = (body?.toString() ?: "").toByteArray(Charsets.UTF_8)
        ex.responseHeaders.add("Content-Type", "application/json; charset=utf-8")
        cors(ex)
        ex.sendResponseHeaders(status, if (bytes.isEmpty()) -1 else bytes.size.toLong())
        if (bytes.isNotEmpty()) ex.responseBody.write(bytes)
    }

    private fun sendHtml(ex: HttpExchange, html: String) {
        val bytes = html.toByteArray(Charsets.UTF_8)
        ex.responseHeaders.add("Content-Type", "text/html; charset=utf-8")
        ex.sendResponseHeaders(200, bytes.size.toLong())
        ex.responseBody.write(bytes)
    }

    private fun cors(ex: HttpExchange) {
        ex.responseHeaders.add("Access-Control-Allow-Origin", "*")
        ex.responseHeaders.add("Access-Control-Allow-Headers", "Authorization, X-API-Key, Content-Type")
        ex.responseHeaders.add("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
    }
}
