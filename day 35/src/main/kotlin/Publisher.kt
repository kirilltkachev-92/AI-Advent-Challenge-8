import kotlin.io.path.absolutePathString

/** Итог публикации: тег и URL релиза на GitHub. */
data class PublishResult(val tag: String, val releaseUrl: String)

/**
 * Реальная публикация: аннотированный тег + git push тега + gh release create.
 * Сюда конвейер доходит только если преflight без FAIL-ов и release gate дал добро.
 */
object Publisher {

    fun tagExists(version: String): Boolean =
        exec("git", "rev-parse", "--verify", "refs/tags/$version").ok

    fun publish(draft: ReleaseDraft): PublishResult {
        require(!tagExists(draft.version)) { "тег ${draft.version} уже существует" }

        val tag = exec("git", "tag", "-a", draft.version, "-m", draft.title)
        check(tag.ok) { "git tag: ${tag.stderr}" }

        val push = exec("git", "push", "origin", draft.version)
        check(push.ok) { "git push тега: ${push.stderr}" }

        val notesPath = Config.releaseNotesFile().absolutePathString()
        val release = exec(
            "gh", "release", "create", draft.version,
            "--title", "${draft.version} — ${draft.title}",
            "--notes-file", notesPath,
        )
        check(release.ok) { "gh release create: ${release.stderr}" }

        return PublishResult(draft.version, release.stdout.lines().last().trim())
    }
}
