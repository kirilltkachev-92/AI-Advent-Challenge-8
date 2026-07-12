import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.LocalDateTime
import java.util.concurrent.CompletableFuture
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

/**
 * Проверка по чеклисту задания — обычным HTTP-клиентом СНАРУЖИ сервиса:
 *   1) доступ к модели по сети (healthz + реальный ответ модели);
 *   2) приватность (без токена и с неверным токеном — 401);
 *   3) стабильность при нескольких запросах (последовательно и параллельно);
 *   4) базовые ограничения: rate limit → 429, max input → 413, история → подрезка.
 *
 * VERIFY_BASE_URL=http://<vps>:8030 VERIFY_TOKEN=… ./run.sh verify — те же
 * проверки против уже развёрнутого VPS. Отчёт — output/report.md.
 */
object Verify {

    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()
    private val json = Json { ignoreUnknownKeys = true }

    private data class Row(val check: String, val ok: Boolean, val details: String)

    private class Api(val base: String, val token: String?) {
        fun get(path: String, auth: Boolean = true): HttpResponse<String> {
            val b = HttpRequest.newBuilder().uri(URI.create("$base$path")).timeout(Duration.ofSeconds(30)).GET()
            if (auth && token != null) b.header("Authorization", "Bearer $token")
            return http.send(b.build(), HttpResponse.BodyHandlers.ofString())
        }

        fun chat(body: String, token: String? = this.token): HttpResponse<String> {
            val b = HttpRequest.newBuilder()
                .uri(URI.create("$base/v1/chat"))
                .timeout(Duration.ofMinutes(5))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
            if (token != null) b.header("Authorization", "Bearer $token")
            return http.send(b.build(), HttpResponse.BodyHandlers.ofString())
        }
    }

    fun run() {
        val base = Config.verifyBaseUrl()
        val token = Config.verifyToken()
        if (token == null) {
            System.err.println("Не задан VERIFY_TOKEN (или API_TOKENS в .env) — проверять нечем.")
            return
        }
        val api = Api(base, token)
        val rows = mutableListOf<Row>()
        println("Проверяю сервис на $base …\n")

        // Действующие лимиты спрашиваем у самого сервиса: при проверке чужого
        // VPS локальный Config может не совпадать с настройками сервера.
        val limits = runCatching { json.parseToJsonElement(api.get("/v1/limits").body()).jsonObject }.getOrNull()
        val perMin = limits?.get("rate_limit_per_min")?.jsonPrimitive?.content?.toIntOrNull()
            ?: Config.rateLimitPerMin()
        val burst = limits?.get("rate_limit_burst")?.jsonPrimitive?.content?.toIntOrNull()
            ?: Config.rateLimitBurst()

        // --- 1. Доступ по сети ------------------------------------------------
        val health = runCatching { api.get("/healthz", auth = false) }.getOrNull()
        val healthOk = health?.statusCode() == 200
        rows += Row(
            "Доступ по сети: GET /healthz", healthOk,
            if (healthOk) "HTTP 200: ${health!!.body()}" else "сервис недоступен на $base",
        )
        if (!healthOk) return report(base, rows) // дальше проверять нечего

        // --- 2. Приватность ----------------------------------------------------
        val noToken = api.chat("""{"message":"ping"}""", token = null)
        val badToken = api.chat("""{"message":"ping"}""", token = "wrong-token-123")
        rows += Row(
            "Без токена и с неверным токеном — 401",
            noToken.statusCode() == 401 && badToken.statusCode() == 401,
            "без токена → ${noToken.statusCode()}, неверный → ${badToken.statusCode()}",
        )

        // --- 3. Один осмысленный ответ модели -----------------------------------
        val question = "Что было в задании дня 21 про индексацию документов?"
        val single = api.chat("""{"message":"$question"}""")
        val singleObj = runCatching { json.parseToJsonElement(single.body()).jsonObject }.getOrNull()
        val answer = singleObj?.get("answer")?.jsonPrimitive?.content ?: ""
        rows += Row(
            "Модель отвечает по сети (RAG по чату)",
            single.statusCode() == 200 && answer.length > 20,
            "HTTP ${single.statusCode()}, ${answer.length} символов: «${answer.take(120)}…»",
        )

        // --- 4. Стабильность: несколько запросов подряд и параллельно -----------
        val questions = listOf(
            "Какая была тема дня 28?",
            "Что тьютор говорил про RAG и код?",
            "Какие модели участники ставили на VPS в день 30?",
            "Что было в задании дня 25 про мини-чат?",
        )
        val seqTimes = mutableListOf<Long>()
        var seqOk = 0
        repeat(Config.verifySequential()) { i ->
            val q = questions[i % questions.size]
            val t0 = System.nanoTime()
            val r = api.chat("""{"message":"$q"}""")
            val ms = (System.nanoTime() - t0) / 1_000_000
            seqTimes += ms
            if (r.statusCode() == 200) seqOk++
            println("  последовательный ${i + 1}/${Config.verifySequential()}: HTTP ${r.statusCode()}, $ms мс")
        }
        rows += Row(
            "Стабильность: ${Config.verifySequential()} запроса подряд",
            seqOk == Config.verifySequential(),
            "успешных $seqOk/${Config.verifySequential()}, латентности ${seqTimes.joinToString { "$it мс" }}",
        )

        replenish(perMin, burst) // параллельному залпу нужен полный запас жетонов
        val parallel = Config.verifyParallel()
        val futures = (0 until parallel).map { i ->
            CompletableFuture.supplyAsync {
                val t0 = System.nanoTime()
                val r = api.chat("""{"message":"${questions[i % questions.size]}"}""")
                r.statusCode() to (System.nanoTime() - t0) / 1_000_000
            }
        }
        val results = futures.map { it.join() }
        val codes = results.groupingBy { it.first }.eachCount()
        // Под потолком одновременности часть может честно получить 429/503 — это
        // штатная защита; нештатны только 5xx-падения (500) и обрывы.
        val parOk = results.all { it.first in setOf(200, 429, 503) } && results.any { it.first == 200 }
        rows += Row(
            "Стабильность: $parallel запросов одновременно",
            parOk,
            "коды: $codes; времена: ${results.joinToString { "${it.second} мс" }} " +
                "(очередь ограничена, отказ — это 429/503, не падение)",
        )

        // --- 5. Rate limit ------------------------------------------------------
        replenish(perMin, burst) // c полным ведром видно и залп, и момент отказа
        var got429: HttpResponse<String>? = null
        var burstUsed = 0
        for (i in 1..burst + 10) {
            val r = api.get("/v1/limits")
            if (r.statusCode() == 429) { got429 = r; break }
            burstUsed++
        }
        rows += Row(
            "Rate limit: залп запросов упирается в 429",
            got429 != null,
            if (got429 != null) {
                "прошло $burstUsed, затем 429 c Retry-After: ${got429.headers().firstValue("Retry-After").orElse("?")} с"
            } else {
                "429 так и не наступил за ${burst + 10} запросов"
            },
        )

        // --- 6. Max context: слишком длинное сообщение → 413 ---------------------
        replenish(perMin, 2)
        val huge = "почему ".repeat(2000) // ~14000 символов
        val tooLong = api.chat("""{"message":"$huge"}""")
        rows += Row(
            "Max input: сообщение на ${huge.length} символов → 413",
            tooLong.statusCode() == 413,
            "HTTP ${tooLong.statusCode()}: ${tooLong.body().take(160)}",
        )

        // --- 7. Max context: длинная история подрезается, а не роняет сервис ------
        replenish(perMin, 2)
        val longHistory = buildString {
            append("""{"messages":[""")
            repeat(12) {
                append("""{"role":"user","content":"${"вода ".repeat(150)}"},""")
                append("""{"role":"assistant","content":"ок"},""")
            }
            append("""{"role":"user","content":"Какая была тема дня 28?"}]}""")
        }
        val trimmed = api.chat(longHistory)
        val trimmedFlag = runCatching {
            json.parseToJsonElement(trimmed.body()).jsonObject["history_trimmed"]?.jsonPrimitive?.content
        }.getOrNull()
        rows += Row(
            "Max context: длинная история подрезается",
            trimmed.statusCode() == 200 && trimmedFlag == "true",
            "HTTP ${trimmed.statusCode()}, history_trimmed=$trimmedFlag",
        )

        report(base, rows)
    }

    /**
     * После намеренного залпа даём ведру накопить жетоны. Именно сон, а не опрос:
     * каждый «проверочный» запрос сам тратит жетон, и опросом ведро не наполнить.
     */
    private fun replenish(perMin: Int, tokensNeeded: Int) {
        val msPerToken = 60_000L / perMin.coerceAtLeast(1)
        println("  …жду ${msPerToken * tokensNeeded / 1000} с, пока rate limit накопит $tokensNeeded жетона")
        Thread.sleep(msPerToken * tokensNeeded + 500)
    }

    private fun report(base: String, rows: List<Row>) {
        val md = buildString {
            appendLine("# День 30 — проверка приватного LLM-сервиса")
            appendLine()
            appendLine("- Сервис: `$base`")
            appendLine("- Время проверки: ${LocalDateTime.now().withNano(0)}")
            appendLine("- Проверял внешний HTTP-клиент (режим `verify`), сервис — чёрный ящик.")
            appendLine()
            appendLine("| Проверка | Итог | Детали |")
            appendLine("|---|---|---|")
            rows.forEach { r ->
                appendLine("| ${r.check} | ${if (r.ok) "✅" else "❌"} | ${r.details.replace("|", "\\|").replace("\n", " ")} |")
            }
            appendLine()
            val passed = rows.count { it.ok }
            appendLine("**Итого: $passed/${rows.size} проверок пройдено.**")
        }
        val path = Config.outputDir().also { it.createDirectories() }.resolve("report.md")
        path.writeText(md)
        println("\n$md")
        println("Отчёт: $path")
    }
}
