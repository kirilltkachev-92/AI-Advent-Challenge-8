import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/** Черновик релиза от модели: версия, заголовок, release notes. */
data class ReleaseDraft(
    val version: String,
    val title: String,
    val notes: String,
    val versionReason: String,
)

/** Суждение модели о готовности релиза по собранным фактам. */
data class GateVerdict(
    val ready: Boolean,
    val blockers: List<String>,
    val warnings: List<String>,
    val summary: String,
)

/**
 * AI-часть конвейера: DeepSeek получает реальные факты репозитория и
 * 1) пишет черновик релиза (semver-версия с обоснованием + release notes),
 * 2) выносит вердикт о готовности (release gate).
 * Оба ответа — строгий JSON (response_format=json_object), протокол вручную.
 */
class ReleaseAgent(
    private val apiKey: String,
    private val model: String = Config.deepSeekModel(),
) {
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30)).build()
    private val json = Json { ignoreUnknownKeys = true }

    fun draft(facts: RepoFacts): ReleaseDraft {
        val system = """
            Ты — релиз-инженер. По фактам git-репозитория подготовь черновик релиза
            на GitHub. Репозиторий — AI Advent Challenge #8: 35 ежедневных задач
            (каждая — отдельное Kotlin-приложение вокруг LLM), марафон завершён,
            это его итоговый релиз.

            Верни СТРОГО JSON без markdown-обёртки, с полями:
            {
              "version": "semver-версия тега, с префиксом v",
              "version_reason": "одно предложение, почему именно такая версия",
              "title": "заголовок релиза (по-русски, ёмкий, до 60 символов)",
              "notes": "release notes в Markdown, по-русски"
            }

            Требования к notes:
            - это витрина проекта: краткое вступление, что за марафон и что в итоге;
            - сгруппируй 35 дней по темам (не перечисляй все подряд): основы работы
              с LLM, протоколы/интеграции, RAG, агенты и инструменты и т.п. —
              группы выведи из реальных тем дней;
            - отметь 4—6 самых интересных дней с номерами;
            - в конце — раздел «Как запускать» одним абзацем: в каждом каталоге
              day N есть README и run.sh, нужен JDK 17 и ключ DeepSeek;
            - без выдумок: только то, что следует из фактов.
        """.trimIndent()

        val user = buildString {
            appendLine("Факты репозитория:")
            appendLine("- remote: ${facts.remoteUrl}")
            appendLine("- ветка: ${facts.branch}, HEAD: ${facts.headSha} «${facts.headSubject}»")
            appendLine("- последний тег: ${facts.lastTag ?: "нет (это будет первый релиз)"}")
            appendLine("- коммитов в релиз: ${facts.commits.size}")
            appendLine()
            appendLine("Темы всех дней (из README):")
            facts.dayThemes.forEach { appendLine("- день ${it.day}: ${it.title}") }
            appendLine()
            appendLine("История коммитов (hash, дата, заголовок):")
            facts.commits.forEach { appendLine("- ${it.sha} ${it.date} ${it.subject}") }
        }

        val obj = chatJson(system, user)
        return ReleaseDraft(
            version = obj["version"]?.jsonPrimitive?.content ?: "v1.0.0",
            versionReason = obj["version_reason"]?.jsonPrimitive?.content ?: "",
            title = obj["title"]?.jsonPrimitive?.content ?: "Релиз",
            notes = obj["notes"]?.jsonPrimitive?.content ?: "",
        )
    }

    fun gate(facts: RepoFacts, checks: List<Check>, draft: ReleaseDraft): GateVerdict {
        val system = """
            Ты — release gate: по фактам репозитория и результатам преflight-проверок
            реши, готов ли релиз к публикации. Незакоммиченные изменения tracked-файлов,
            расхождение с origin, чужая ветка — блокеры. Неотслеживаемый мусор вроде
            .DS_Store и перегенерированные артефакты самого конвейера (файлы в
            day 35/output/) — только предупреждения. Уже существующий тег этой
            версии — блокер.

            Верни СТРОГО JSON без markdown-обёртки:
            {
              "ready": true/false,
              "blockers": ["..."],
              "warnings": ["..."],
              "summary": "одно-два предложения по-русски: решение и главная причина"
            }
        """.trimIndent()

        val user = buildString {
            appendLine("Планируемый релиз: ${draft.version} «${draft.title}»")
            appendLine("Существующие теги: ${facts.lastTag ?: "нет"}")
            appendLine()
            appendLine("Преflight-проверки:")
            checks.forEach { appendLine("- [${it.status}] ${it.name}: ${it.detail}") }
            appendLine()
            appendLine("Состояние дерева (git status --porcelain):")
            if (facts.dirtyFiles.isEmpty()) appendLine("(чисто)")
            facts.dirtyFiles.forEach { appendLine(it) }
            appendLine()
            appendLine("Синхронизация: ahead=${facts.aheadOfRemote}, behind=${facts.behindRemote}")
            appendLine("GitHub CLI: ${if (facts.ghAuthenticated) "авторизован (${facts.ghRepo})" else "нет доступа"}")
        }

        val obj = chatJson(system, user)
        return GateVerdict(
            ready = obj["ready"]?.jsonPrimitive?.content?.toBoolean() ?: false,
            blockers = obj["blockers"]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList(),
            warnings = obj["warnings"]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList(),
            summary = obj["summary"]?.jsonPrimitive?.content ?: "",
        )
    }

    private fun chatJson(system: String, user: String): JsonObject {
        val body = buildJsonObject {
            put("model", model)
            put(
                "messages",
                JsonArray(
                    listOf(
                        buildJsonObject { put("role", "system"); put("content", system) },
                        buildJsonObject { put("role", "user"); put("content", user) },
                    ),
                ),
            )
            putJsonObject("response_format") { put("type", "json_object") }
            put("temperature", 0.3)
            put("stream", false)
        }
        val request = HttpRequest.newBuilder()
            .uri(URI.create("${Config.DEEPSEEK_API_BASE}/chat/completions"))
            .timeout(Duration.ofMinutes(3))
            .header("Content-Type", "application/json")
            .header("Authorization", "Bearer $apiKey")
            .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
            .build()
        val response = http.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() !in 200..299) {
            error("DeepSeek HTTP ${response.statusCode()}: ${response.body().take(300)}")
        }
        val content = json.parseToJsonElement(response.body()).jsonObject["choices"]
            ?.jsonArray?.firstOrNull()?.jsonObject?.get("message")?.jsonObject
            ?.get("content")?.jsonPrimitive?.content
            ?: error("Пустой ответ DeepSeek")
        return json.parseToJsonElement(content).jsonObject
    }
}
