import org.apache.pdfbox.Loader
import org.apache.pdfbox.text.PDFTextStripper
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.name
import kotlin.io.path.readText

/** Тип исходного документа — от него зависит, как структурная стратегия ищет разделы. */
enum class DocKind { PDF, MARKDOWN, CODE }

/** Документ, уже приведённый к тексту (шаг «pdf/md/код → text»). */
data class RawDocument(
    val source: String, // путь к файлу-источнику (метаданное source)
    val title: String,  // человекочитаемое имя (метаданное title/file)
    val kind: DocKind,
    val text: String,
) {
    val pagesEquivalent: Double get() = text.length / 3000.0 // ~3000 знаков ≈ страница
}

/**
 * Загрузчик корпуса: всё берётся ТОЛЬКО из каталога docs/ — pdf, markdown и код.
 * Основной корпус — статья Brown et al., «Language Models are Few-Shot Learners»
 * (GPT-3, arXiv 2005.14165), ~75 страниц.
 */
object DocumentLoader {

    init {
        // PDFBox шумит WARNING'ами про шрифты без Unicode-маппинга — на извлечение
        // текста это не влияет, глушим.
        java.util.logging.Logger.getLogger("org.apache.pdfbox").level = java.util.logging.Level.SEVERE
    }

    fun loadCorpus(docsDir: Path): List<RawDocument> {
        require(Files.isDirectory(docsDir)) { "Каталог с документами не найден: $docsDir" }
        val docs = mutableListOf<RawDocument>()
        Files.list(docsDir).use { stream ->
            stream.sorted().forEach { file ->
                when (file.extension.lowercase()) {
                    "pdf" -> docs += RawDocument(file.toString(), file.name, DocKind.PDF, extractPdfText(file))
                    "md" -> docs += RawDocument(file.toString(), file.name, DocKind.MARKDOWN, file.readText())
                    "kt", "kts" -> docs += RawDocument(file.toString(), file.name, DocKind.CODE, file.readText())
                }
            }
        }
        require(docs.isNotEmpty()) { "В $docsDir нет документов (pdf/md/kt)" }
        return docs
    }

    /** PDF → текст через PDFBox; переносы-дефисы на границах строк склеиваем. */
    private fun extractPdfText(pdf: Path): String {
        Loader.loadPDF(pdf.toFile()).use { doc ->
            val raw = PDFTextStripper().getText(doc)
            return raw.replace(Regex("(\\p{L})-\\n(\\p{L})"), "$1$2")
        }
    }
}
