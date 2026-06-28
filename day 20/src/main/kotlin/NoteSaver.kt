import java.nio.file.Files
import java.nio.file.Path
import java.nio.charset.StandardCharsets
import kotlin.io.path.exists
import kotlin.io.path.fileSize
import kotlin.io.path.isRegularFile
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.readText

/** Результат сохранения: куда записали и сколько байт. */
data class SaveResult(val path: Path, val bytes: Int)

/** Запись из каталога: имя файла и его размер. */
data class FileEntry(val name: String, val bytes: Long)

/**
 * Файловое хранилище для storage-MCP (инструменты save_to_file / list_files / read_file).
 *
 * Все операции строго внутри [baseDir]: имя из аргумента LLM санируется (только базовое имя,
 * без переходов по каталогам) — чтобы инструментом нельзя было читать/писать куда угодно.
 */
class NoteSaver(private val baseDir: Path) {

    fun save(filename: String, content: String): SaveResult {
        Files.createDirectories(baseDir)
        val target = baseDir.resolve(sanitize(filename))
        val bytes = content.toByteArray(StandardCharsets.UTF_8)
        Files.write(target, bytes)
        return SaveResult(target, bytes.size)
    }

    /** Список сохранённых файлов (только обычные файлы внутри baseDir). */
    fun list(): List<FileEntry> {
        if (!baseDir.exists()) return emptyList()
        return baseDir.listDirectoryEntries()
            .filter { it.isRegularFile() }
            .sortedBy { it.name }
            .map { FileEntry(it.name, it.fileSize()) }
    }

    /** Чтение файла обратно по имени (имя санируется). null — если файла нет. */
    fun read(filename: String): String? {
        val target = baseDir.resolve(sanitize(filename))
        return if (target.exists() && target.isRegularFile()) target.readText() else null
    }

    companion object {
        /** Оставляем только базовое имя, разрешённые символы и расширение по умолчанию .md. */
        fun sanitize(raw: String): String {
            val base = raw.trim().substringAfterLast('/').substringAfterLast('\\')
            val cleaned = base
                .replace(Regex("[^\\p{L}\\p{N}._ -]"), "_")
                .trim()
                .ifBlank { "note" }
            return if (cleaned.contains('.')) cleaned else "$cleaned.md"
        }
    }
}
