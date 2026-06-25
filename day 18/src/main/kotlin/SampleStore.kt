import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.exists
import kotlin.io.path.readText

/**
 * Один сохранённый замер погоды — то, что планировщик складывает на диск каждый тик.
 *
 * @param time      время измерения по данным Open-Meteo (локальное для города);
 * @param fetchedAt когда наш планировщик снял замер (ISO-8601, UTC) — по нему агрегируем;
 */
@Serializable
data class WeatherSample(
    val time: String,
    val fetchedAt: String,
    val temperatureC: Double,
    val windSpeed: Double,
    val weatherCode: Int,
    val description: String,
)

/**
 * Персистентное хранилище замеров (требование задания: «сохранять данные — JSON/SQLite»).
 *
 * Выбран JSON-файл, а не SQLite: проект принципиально без внешних драйверов
 * (как и рукописный MCP в Днях 16–17), а формат остаётся читаемым глазами.
 * Структура файла: { "Париж": [ {замер}, {замер}, ... ], "Токио": [ ... ] }.
 *
 * Потокобезопасно: планировщик пишет из фонового потока, а tools/call может читать
 * одновременно — поэтому весь доступ под [lock], а запись на диск атомарна (tmp + rename).
 */
class SampleStore(private val file: Path) {
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }
    private val lock = Any()
    private val data: MutableMap<String, MutableList<WeatherSample>> = loadFromDisk()

    /** Добавляет замер для города и сразу персистит весь файл. Возвращает число замеров по городу. */
    fun add(city: String, sample: WeatherSample): Int = synchronized(lock) {
        val list = data.getOrPut(normalize(city)) { mutableListOf() }
        list.add(sample)
        flush()
        list.size
    }

    /** Все замеры по городу в порядке добавления (пустой список, если города нет). */
    fun samples(city: String): List<WeatherSample> = synchronized(lock) {
        data[normalize(city)]?.toList() ?: emptyList()
    }

    /** Города, по которым есть хотя бы один замер. */
    fun cities(): List<String> = synchronized(lock) { data.keys.sorted() }

    /** Всего замеров во всём хранилище. */
    fun totalSamples(): Int = synchronized(lock) { data.values.sumOf { it.size } }

    /** Очистка (удобно для демо/тестов). */
    fun clear() = synchronized(lock) {
        data.clear()
        flush()
    }

    // --- дисковая часть -------------------------------------------------------

    private fun loadFromDisk(): MutableMap<String, MutableList<WeatherSample>> {
        if (!file.exists()) return mutableMapOf()
        val text = file.readText().trim()
        if (text.isEmpty()) return mutableMapOf()
        val parsed: Map<String, List<WeatherSample>> = json.decodeFromString(text)
        return parsed.mapValues { it.value.toMutableList() }.toMutableMap()
    }

    /** Атомарная запись: пишем во временный файл и переименовываем поверх. */
    private fun flush() {
        file.parent?.let { Files.createDirectories(it) }
        val tmp = file.resolveSibling("${file.fileName}.tmp")
        val snapshot: Map<String, List<WeatherSample>> = data.mapValues { it.value.toList() }
        Files.writeString(tmp, json.encodeToString(snapshot))
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    private fun normalize(city: String): String = city.trim()
}
