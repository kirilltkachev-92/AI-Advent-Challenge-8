import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration

/** То, что нужно ревьюеру из события pull_request: номер PR и границы диффа. */
data class PrEvent(val number: Int, val baseSha: String, val headSha: String, val title: String)

/**
 * Клиент GitHub REST API — вручную на java.net.http, как весь марафон.
 * Умеет ровно два действия пайплайна: прочитать событие pull_request
 * из GITHUB_EVENT_PATH и вернуть ревью «прям в гитхаб — в PR в виде
 * комментов» (уточнение тьютора в чате).
 */
class GitHubClient(private val token: String, private val repo: String) {
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build()
    private val json = Json { ignoreUnknownKeys = true }

    /** POST /repos/{owner}/{repo}/issues/{number}/comments — коммент в PR. */
    fun postPrComment(prNumber: Int, markdown: String) {
        val body = buildJsonObject { put("body", markdown) }
        val request = HttpRequest.newBuilder()
            .uri(URI.create("${Config.githubApiBase()}/repos/$repo/issues/$prNumber/comments"))
            .timeout(Duration.ofSeconds(30))
            .header("Authorization", "Bearer $token")
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
            .build()
        val response = http.send(request, HttpResponse.BodyHandlers.ofString())
        check(response.statusCode() in 200..299) {
            "GitHub API → HTTP ${response.statusCode()}: ${response.body().take(300)}"
        }
        println("→ Коммент оставлен в PR #$prNumber")
    }

    companion object {
        /** Событие workflow-запуска: GITHUB_EVENT_PATH → номер PR, base/head SHA. */
        fun readPrEvent(eventPath: String): PrEvent {
            val event = Json { ignoreUnknownKeys = true }
                .parseToJsonElement(Files.readString(Path.of(eventPath))).jsonObject
            val pr = event["pull_request"]?.jsonObject
                ?: error("В событии нет pull_request — workflow должен запускаться on: pull_request")
            return PrEvent(
                number = pr.getValue("number").jsonPrimitive.int,
                baseSha = pr.getValue("base").jsonObject.getValue("sha").jsonPrimitive.content,
                headSha = pr.getValue("head").jsonObject.getValue("sha").jsonPrimitive.content,
                title = pr["title"]?.jsonPrimitive?.content ?: "",
            )
        }
    }
}
