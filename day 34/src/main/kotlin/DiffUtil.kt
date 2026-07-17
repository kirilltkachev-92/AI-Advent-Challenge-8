/**
 * Унифицированный diff вручную (LCS по строкам) — чтобы каждое изменение
 * файла было видно как привычный `diff -u`, без обращения к git: рабочая
 * копия проекта живёт в output/ и в git не попадает.
 */
object DiffUtil {

    /** Сравнивает два текста и возвращает unified diff (пусто, если различий нет). */
    fun unified(oldText: String, newText: String, path: String, context: Int = 2): String {
        if (oldText == newText) return ""
        val a = oldText.lines()
        val b = newText.lines()

        // Таблица LCS: O(n·m) — файлы демо-проекта маленькие.
        val n = a.size
        val m = b.size
        val lcs = Array(n + 1) { IntArray(m + 1) }
        for (i in n - 1 downTo 0) {
            for (j in m - 1 downTo 0) {
                lcs[i][j] = if (a[i] == b[j]) lcs[i + 1][j + 1] + 1
                else maxOf(lcs[i + 1][j], lcs[i][j + 1])
            }
        }

        // Разворачиваем в последовательность операций: ' ' общая, '-' удалена, '+' добавлена.
        data class Op(val kind: Char, val aLine: Int, val bLine: Int, val text: String)
        val ops = mutableListOf<Op>()
        var i = 0
        var j = 0
        while (i < n && j < m) {
            when {
                a[i] == b[j] -> { ops += Op(' ', i, j, a[i]); i++; j++ }
                lcs[i + 1][j] >= lcs[i][j + 1] -> { ops += Op('-', i, j, a[i]); i++ }
                else -> { ops += Op('+', i, j, b[j]); j++ }
            }
        }
        while (i < n) { ops += Op('-', i, j, a[i]); i++ }
        while (j < m) { ops += Op('+', i, j, b[j]); j++ }

        // Группируем изменения в ханки с context строками контекста вокруг.
        val changed = ops.indices.filter { ops[it].kind != ' ' }
        if (changed.isEmpty()) return ""
        val keep = BooleanArray(ops.size)
        changed.forEach { c ->
            for (k in maxOf(0, c - context)..minOf(ops.size - 1, c + context)) keep[k] = true
        }

        val sb = StringBuilder()
        sb.appendLine("--- a/$path")
        sb.appendLine("+++ b/$path")
        var pos = 0
        while (pos < ops.size) {
            if (!keep[pos]) { pos++; continue }
            var end = pos
            while (end < ops.size && keep[end]) end++
            val hunk = ops.subList(pos, end)
            val aStart = (hunk.firstOrNull { it.kind != '+' }?.aLine ?: hunk.first().aLine) + 1
            val bStart = (hunk.firstOrNull { it.kind != '-' }?.bLine ?: hunk.first().bLine) + 1
            val aCount = hunk.count { it.kind != '+' }
            val bCount = hunk.count { it.kind != '-' }
            sb.appendLine("@@ -$aStart,$aCount +$bStart,$bCount @@")
            hunk.forEach { sb.appendLine("${it.kind}${it.text}") }
            pos = end
        }
        return sb.toString().trimEnd()
    }
}
