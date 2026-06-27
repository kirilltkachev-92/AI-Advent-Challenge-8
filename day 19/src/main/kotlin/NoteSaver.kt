import java.nio.file.Files
import java.nio.file.Path
import java.nio.charset.StandardCharsets

/** Результат сохранения: куда записали и сколько байт. */
data class SaveResult(val path: Path, val bytes: Int)

/**
 * Сохранение результата пайплайна в файл (инструмент save_to_file).
 *
 * Все файлы кладём строго внутрь [baseDir]: имя из аргумента LLM санируется
 * (только базовое имя, без переходов по каталогам) — чтобы tool нельзя было
 * заставить писать куда угодно по файловой системе.
 */
class NoteSaver(private val baseDir: Path) {

    fun save(filename: String, content: String): SaveResult {
        Files.createDirectories(baseDir)
        val safe = sanitize(filename)
        val target = baseDir.resolve(safe)
        val bytes = content.toByteArray(StandardCharsets.UTF_8)
        Files.write(target, bytes)
        return SaveResult(target, bytes.size)
    }

    companion object {
        /** Оставляем только базовое имя, разрешённые символы и расширение по умолчанию .md. */
        fun sanitize(raw: String): String {
            // Берём то, что после последнего / или \ — никаких ../ и абсолютных путей.
            val base = raw.trim().substringAfterLast('/').substringAfterLast('\\')
            val cleaned = base
                .replace(Regex("[^\\p{L}\\p{N}._ -]"), "_")
                .trim()
                .ifBlank { "note" }
            return if (cleaned.contains('.')) cleaned else "$cleaned.md"
        }
    }
}
