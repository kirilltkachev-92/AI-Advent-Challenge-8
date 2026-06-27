import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.nio.file.Files
import kotlin.io.path.createTempDirectory
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Тесты композиции инструментов на уровне JSON-RPC, без сети и без LLM.
 * Источник данных — заглушка; проверяем, что цепочка search → summarize → save_to_file
 * проходит и данные КОРРЕКТНО ПЕРЕДАЮТСЯ между инструментами (итог summarize == файл).
 */
class McpCompositionTest {
    private val json = Json { ignoreUnknownKeys = true }

    // Заглушка Википедии: предсказуемый поиск и вводные тексты.
    private val fakeSearch: (String, Int) -> List<WikiHit> = { query, limit ->
        listOf(
            WikiHit("Эрмитаж", "крупнейший музей"),
            WikiHit("Зимний дворец", "резиденция императоров"),
            WikiHit("Дворцовая площадь", "главная площадь"),
        ).take(limit)
    }
    private val fakeExtract: (String) -> String = { title ->
        when (title) {
            "Эрмитаж" -> "Государственный Эрмитаж — музей в Санкт-Петербурге."
            "Зимний дворец" -> "Зимний дворец — бывшая императорская резиденция."
            else -> ""
        }
    }

    // Полный текст статьи — заглушка (длиннее вводного, как настоящий оригинал).
    private val fakeArticle: (String) -> String = { title ->
        when (title) {
            "Эрмитаж" -> "Государственный Эрмитаж — музей в Санкт-Петербурге. Основан в 1764 году."
            "Зимний дворец" -> "Зимний дворец — бывшая императорская резиденция. Построен Растрелли."
            else -> ""
        }
    }

    private fun server(outDir: java.nio.file.Path) =
        McpServer.compositionServer(fakeSearch, fakeExtract, NoteSaver(outDir), fakeArticle)

    private fun call(server: McpServer, name: String, args: JsonObject): JsonObject {
        val params = buildJsonObject { put("name", name); put("arguments", args) }
        val msg = buildJsonObject {
            put("jsonrpc", "2.0"); put("id", 1); put("method", "tools/call"); put("params", params)
        }
        return server.handleRpc(msg)!!["result"]!!.jsonObject
    }

    private fun text(result: JsonObject): String =
        result["content"]!!.jsonArray.first().jsonObject["text"]!!.jsonPrimitive.content

    @Test
    fun `tools-list содержит инструменты пайплайна`() {
        val msg = buildJsonObject { put("jsonrpc", "2.0"); put("id", 1); put("method", "tools/list") }
        val tools = server(createTempDirectory("d19")).handleRpc(msg)!!["result"]!!.jsonObject["tools"]!!.jsonArray
        val names = tools.map { it.jsonObject["name"]!!.jsonPrimitive.content }
        assertEquals(listOf("search", "summarize", "save_to_file", "save_articles"), names)
    }

    @Test
    fun `search ограничивает количество и возвращает заголовки`() {
        val out = text(call(server(createTempDirectory("d19")), "search",
            buildJsonObject { put("query", "Эрмитаж"); put("limit", 2) }))
        assertTrue(out.contains("Найдено статей: 2"), out)
        assertTrue(out.contains("Эрмитаж"), out)
        assertTrue(out.contains("Зимний дворец"), out)
    }

    @Test
    fun `summarize собирает конспект по заголовкам`() {
        val out = text(call(server(createTempDirectory("d19")), "summarize", buildJsonObject {
            put("topic", "Эрмитаж")
            put("titles", buildJsonArray { add("Эрмитаж"); add("Зимний дворец") })
        }))
        assertTrue(out.contains("# Конспект: Эрмитаж"), out)
        assertTrue(out.contains("Государственный Эрмитаж"), out)
        assertTrue(out.contains("императорская резиденция"), out)
    }

    @Test
    fun `вся цепочка проходит и данные передаются без искажения`() {
        val outDir = createTempDirectory("d19-chain")
        val server = server(outDir)

        // (1) search → берём заголовки (как сделал бы агент).
        text(call(server, "search", buildJsonObject { put("query", "Эрмитаж") }))

        // (2) summarize → конспект.
        val report = text(call(server, "summarize", buildJsonObject {
            put("topic", "Эрмитаж")
            put("titles", buildJsonArray { add("Эрмитаж"); add("Зимний дворец") })
        }))

        // (3) save_to_file → передаём РОВНО результат summarize.
        val saved = text(call(server, "save_to_file", buildJsonObject {
            put("filename", "hermitage.md")
            put("content", report)
        }))
        assertTrue(saved.contains("Сохранено"), saved)

        // Корректность передачи данных: содержимое файла == конспект из summarize.
        val file = outDir.resolve("hermitage.md")
        assertTrue(Files.exists(file), "файл должен быть создан")
        assertEquals(report, file.readText())
    }

    @Test
    fun `save_to_file санирует имя и не выходит за каталог`() {
        val outDir = createTempDirectory("d19-safe")
        text(call(server(outDir), "save_to_file", buildJsonObject {
            put("filename", "../../evil.md")
            put("content", "x")
        }))
        // Файл должен оказаться внутри outDir под безопасным именем, а не на два уровня выше.
        assertTrue(Files.exists(outDir.resolve("evil.md")), "должен сохраниться внутри каталога")
        assertTrue(Files.notExists(outDir.parent.parent.resolve("evil.md")), "не должен сбежать из каталога")
    }

    @Test
    fun `save_articles сохраняет каждую статью в свой файл оригинальным текстом`() {
        val outDir = createTempDirectory("d19-raw")
        val out = text(call(server(outDir), "save_articles", buildJsonObject {
            put("titles", buildJsonArray { add("Эрмитаж"); add("Зимний дворец") })
        }))
        assertTrue(out.contains("Сохранено статей по отдельности"), out)

        // Два ОТДЕЛЬНЫХ файла, каждый — с полным оригинальным текстом (без суммаризации/обрезки).
        val erm = outDir.resolve("Эрмитаж.md")
        val win = outDir.resolve("Зимний дворец.md")
        assertTrue(Files.exists(erm) && Files.exists(win), "должны появиться два отдельных файла")
        assertTrue(erm.readText().contains("Основан в 1764 году"), erm.readText())
        assertTrue(win.readText().contains("Построен Растрелли"), win.readText())
    }

    @Test
    fun `save_articles пропускает статьи без текста`() {
        val outDir = createTempDirectory("d19-raw-skip")
        val out = text(call(server(outDir), "save_articles", buildJsonObject {
            // "Дворцовая площадь" в заглушке без текста — её надо пропустить.
            put("titles", buildJsonArray { add("Эрмитаж"); add("Дворцовая площадь") })
        }))
        assertTrue(out.contains("Сохранено статей по отдельности (без суммаризации): 1"), out)
        assertTrue(Files.notExists(outDir.resolve("Дворцовая площадь.md")), "пустую статью не сохраняем")
    }

    @Test
    fun `summarize без titles даёт isError=true`() {
        val result = call(server(createTempDirectory("d19")), "summarize", buildJsonObject {
            put("topic", "Эрмитаж")
        })
        assertTrue(result["isError"]!!.jsonPrimitive.content.toBoolean())
    }
}
