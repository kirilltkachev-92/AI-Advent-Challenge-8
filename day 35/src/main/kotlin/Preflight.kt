/** Статус одной проверки перед релизом. */
enum class CheckStatus { OK, WARN, FAIL }

data class Check(val name: String, val status: CheckStatus, val detail: String)

/**
 * Детерминированные преflight-проверки — то, что нельзя доверять модели:
 * ветка, чистота дерева, синхронизация с origin, доступ к GitHub.
 * LLM потом смотрит на эти же факты и выносит своё суждение (ReleaseAgent.gate),
 * но публикацию блокируют только FAIL-ы отсюда.
 */
object Preflight {

    fun run(facts: RepoFacts): List<Check> {
        val checks = mutableListOf<Check>()

        checks += if (facts.branch == "main") {
            Check("Ветка", CheckStatus.OK, "main")
        } else {
            Check("Ветка", CheckStatus.FAIL, "релиз только с main, сейчас: ${facts.branch}")
        }

        // Изменённые tracked-файлы — блокер; неотслеживаемый мусор (.DS_Store) — предупреждение.
        val modified = facts.dirtyFiles.filterNot { it.startsWith("??") }
        val untracked = facts.dirtyFiles.filter { it.startsWith("??") }
        checks += when {
            modified.isNotEmpty() ->
                Check("Рабочее дерево", CheckStatus.FAIL, "незакоммиченные изменения: ${modified.joinToString()}")
            untracked.isNotEmpty() ->
                Check("Рабочее дерево", CheckStatus.WARN, "неотслеживаемые файлы: ${untracked.joinToString { it.removePrefix("?? ") }}")
            else -> Check("Рабочее дерево", CheckStatus.OK, "чисто")
        }

        checks += when {
            facts.behindRemote > 0 ->
                Check("Синхронизация с origin", CheckStatus.FAIL, "отстаём от origin/${facts.branch} на ${facts.behindRemote}")
            facts.aheadOfRemote > 0 ->
                Check("Синхронизация с origin", CheckStatus.FAIL, "${facts.aheadOfRemote} незапушенных коммитов")
            else -> Check("Синхронизация с origin", CheckStatus.OK, "HEAD совпадает с origin/${facts.branch}")
        }

        checks += if (facts.ghAuthenticated && facts.ghRepo != null) {
            Check("GitHub CLI", CheckStatus.OK, "авторизован, репозиторий ${facts.ghRepo}")
        } else {
            Check("GitHub CLI", CheckStatus.FAIL, "gh не авторизован или репозиторий недоступен")
        }

        checks += if (facts.commits.isEmpty()) {
            Check("История для релиза", CheckStatus.FAIL, "нет новых коммитов после ${facts.lastTag}")
        } else {
            val since = facts.lastTag?.let { "после $it" } ?: "с начала истории (первый релиз)"
            Check("История для релиза", CheckStatus.OK, "${facts.commits.size} коммитов $since")
        }

        return checks
    }

    fun hasBlockers(checks: List<Check>): Boolean = checks.any { it.status == CheckStatus.FAIL }
}
