import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration

/** Один результат поиска в Википедии. */
data class WikiHit(val title: String, val snippet: String)

/**
 * Источник данных для пайплайна — публичный MediaWiki API Википедии
 * (бесплатный, без ключа и регистрации). Два метода:
 *   - search  — поиск статей по запросу (для инструмента search);
 *   - extract — вводный абзац статьи (для инструмента-отчёта summarize).
 *
 * Никакого LLM здесь нет: MCP-сервер только ходит за данными (как и просил Алексей —
 * «суммаризация» это просто отчёт/итог, а не вызов модели).
 */
class WikiApi(
    private val lang: String = "ru",
    /** Минимальная пауза между запросами (вежливый троттлинг, чтобы не словить бан/429). */
    private val minIntervalMs: Long = 500,
    /** Сколько раз повторить запрос при 429/503 (rate limit) с нарастающей паузой. */
    private val maxRetries: Int = 4,
) {
    private val http: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(15))
        .build()
    private val json = Json { ignoreUnknownKeys = true }

    /** Время последнего запроса — для выдержки паузы между обращениями к API. */
    private var lastRequestAt = 0L

    /** Поиск статей: возвращает до [limit] заголовков с коротким сниппетом. */
    fun search(query: String, limit: Int = 5): List<WikiHit> {
        val q = enc(query)
        val url = "https://$lang.wikipedia.org/w/api.php" +
            "?action=query&list=search&srsearch=$q&srlimit=$limit&format=json"
        val results = json.parseToJsonElement(get(url))
            .jsonObject["query"]?.jsonObject?.get("search")?.jsonArray ?: return emptyList()
        return results.map { el ->
            val o = el.jsonObject
            WikiHit(
                title = o["title"]?.jsonPrimitive?.content ?: "(без названия)",
                snippet = stripHtml(o["snippet"]?.jsonPrimitive?.content ?: ""),
            )
        }
    }

    /** Вводный абзац статьи по точному заголовку (пустая строка, если не нашлось). */
    fun extract(title: String): String = extractText(title, introOnly = true)

    /**
     * ПОЛНЫЙ текст статьи по точному заголовку (для инструмента save_articles —
     * сохранение оригинала без суммаризации). Пустая строка, если статья не нашлась.
     */
    fun article(title: String): String = extractText(title, introOnly = false)

    private fun extractText(title: String, introOnly: Boolean): String {
        val t = enc(title)
        val intro = if (introOnly) "&exintro=1" else ""
        val url = "https://$lang.wikipedia.org/w/api.php" +
            "?action=query&prop=extracts$intro&explaintext=1&redirects=1&titles=$t&format=json"
        val pages = json.parseToJsonElement(get(url))
            .jsonObject["query"]?.jsonObject?.get("pages")?.jsonObject ?: return ""
        // pages — это map { pageid -> {extract: ...} }; берём первую страницу.
        val page = pages.values.firstOrNull()?.jsonObject ?: return ""
        return page["extract"]?.jsonPrimitive?.content?.trim() ?: ""
    }

    private fun get(url: String): String {
        val request = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .timeout(Duration.ofSeconds(20))
            .header("Accept", "application/json")
            // Wikimedia просит указывать понятный User-Agent.
            .header("User-Agent", "advent-day19-pipeline/1.0 (educational MCP demo)")
            .GET()
            .build()

        var attempt = 0
        while (true) {
            throttle() // выдерживаем минимальную паузу между запросами
            val response = http.send(request, HttpResponse.BodyHandlers.ofString())
            val code = response.statusCode()
            if (code in 200..299) return response.body()

            // 429 Too Many Requests / 503 — это троттлинг: ждём и повторяем.
            if ((code == 429 || code == 503) && attempt < maxRetries) {
                val waitMs = retryAfterMs(response) ?: (1000L shl attempt) // 1s, 2s, 4s, 8s…
                System.err.println("⏳ Википедия отвечает $code (лимит запросов), пауза ${waitMs} мс и повтор…")
                sleep(waitMs)
                attempt++
                continue
            }
            error("Wikipedia HTTP $code: ${response.body().take(200)}")
        }
    }

    /** Не дёргаем API чаще, чем раз в [minIntervalMs]. */
    private fun throttle() {
        if (minIntervalMs <= 0) return
        val wait = minIntervalMs - (System.currentTimeMillis() - lastRequestAt)
        if (wait > 0) sleep(wait)
        lastRequestAt = System.currentTimeMillis()
    }

    private fun sleep(ms: Long) = try {
        Thread.sleep(ms)
    } catch (e: InterruptedException) {
        Thread.currentThread().interrupt()
    }

    /** Пауза из заголовка Retry-After (секунды), если сервер её прислал. */
    private fun retryAfterMs(response: HttpResponse<String>): Long? =
        response.headers().firstValue("Retry-After").orElse(null)
            ?.toLongOrNull()?.let { it.coerceIn(0, 60) * 1000 }

    private fun enc(s: String) = URLEncoder.encode(s, StandardCharsets.UTF_8)

    companion object {
        /** Снятие HTML-разметки из сниппета поиска (там приходят <span> и т.п.). */
        fun stripHtml(s: String): String =
            s.replace(Regex("<[^>]+>"), "")
                .replace("&quot;", "\"")
                .replace("&amp;", "&")
                .replace("&nbsp;", " ")
                .trim()
    }
}
