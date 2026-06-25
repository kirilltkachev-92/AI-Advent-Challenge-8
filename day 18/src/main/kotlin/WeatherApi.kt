import kotlinx.serialization.json.Json
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration

/** Координаты города, найденные геокодером Open-Meteo. */
data class GeoPlace(
    val name: String,
    val country: String,
    val latitude: Double,
    val longitude: Double,
    val timezone: String,
)

/** Текущая погода в точке. */
data class CurrentWeather(
    val place: GeoPlace,
    val temperatureC: Double,
    val windSpeed: Double,
    val weatherCode: Int,
    val description: String,
    val time: String,
)

/**
 * Тонкая обёртка вокруг публичного API Open-Meteo (бесплатный, без ключа и регистрации):
 *   - geocoding-api.open-meteo.com — поиск города → координаты;
 *   - api.open-meteo.com           — текущая погода по координатам.
 *
 * Это и есть тот «любой API», вокруг которого строится MCP-сервер Дня 17.
 */
class WeatherApi {
    private val http: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(15))
        .build()

    private val json = Json { ignoreUnknownKeys = true }

    /** Находит координаты города по названию. null — если ничего не найдено. */
    fun geocode(city: String): GeoPlace? {
        val q = URLEncoder.encode(city, StandardCharsets.UTF_8)
        val url = "https://geocoding-api.open-meteo.com/v1/search" +
            "?name=$q&count=1&language=ru&format=json"
        val body = get(url)
        val results = json.parseToJsonElement(body).jsonObject["results"]?.jsonArray
        val first = results?.firstOrNull()?.jsonObject ?: return null
        return GeoPlace(
            name = first["name"]?.jsonPrimitive?.content ?: city,
            country = first["country"]?.jsonPrimitive?.content ?: "",
            latitude = first["latitude"]!!.jsonPrimitive.double,
            longitude = first["longitude"]!!.jsonPrimitive.double,
            timezone = first["timezone"]?.jsonPrimitive?.content ?: "auto",
        )
    }

    /** Текущая погода по названию города. Бросает исключение, если город не найден. */
    fun currentWeather(city: String): CurrentWeather {
        val place = geocode(city) ?: error("Город «$city» не найден")
        val url = "https://api.open-meteo.com/v1/forecast" +
            "?latitude=${place.latitude}&longitude=${place.longitude}" +
            "&current=temperature_2m,weather_code,wind_speed_10m&timezone=auto"
        val current = json.parseToJsonElement(get(url)).jsonObject["current"]?.jsonObject
            ?: error("Open-Meteo не вернул текущую погоду")
        val code = current["weather_code"]?.jsonPrimitive?.int ?: -1
        return CurrentWeather(
            place = place,
            temperatureC = current["temperature_2m"]?.jsonPrimitive?.double ?: Double.NaN,
            windSpeed = current["wind_speed_10m"]?.jsonPrimitive?.double ?: Double.NaN,
            weatherCode = code,
            description = describe(code),
            time = current["time"]?.jsonPrimitive?.content ?: "",
        )
    }

    private fun get(url: String): String {
        val request = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .timeout(Duration.ofSeconds(20))
            .header("Accept", "application/json")
            .GET()
            .build()
        val response = http.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() !in 200..299) {
            error("Open-Meteo HTTP ${response.statusCode()}: ${response.body().take(200)}")
        }
        return response.body()
    }

    companion object {
        /** Расшифровка WMO weather code в человекочитаемое описание (RU). */
        fun describe(code: Int): String = when (code) {
            0 -> "ясно"
            1 -> "преимущественно ясно"
            2 -> "переменная облачность"
            3 -> "пасмурно"
            45, 48 -> "туман"
            51, 53, 55 -> "морось"
            56, 57 -> "ледяная морось"
            61, 63, 65 -> "дождь"
            66, 67 -> "ледяной дождь"
            71, 73, 75 -> "снег"
            77 -> "снежная крупа"
            80, 81, 82 -> "ливень"
            85, 86 -> "снежные заряды"
            95 -> "гроза"
            96, 99 -> "гроза с градом"
            else -> "неизвестно (код $code)"
        }
    }
}
