import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.float

object WeatherApi {
    private const val BASE = "https://api.open-meteo.com/v1/forecast"
    private val json = Json { ignoreUnknownKeys = true }

    fun currentTemp(lat: Double, lon: Double): Float {
        val body = HttpFetcher.get("$BASE?latitude=$lat&longitude=$lon&current=temperature_2m")
        return json.parseToJsonElement(body).jsonObject
            .getValue("current").jsonObject
            .getValue("temperature_2m").jsonPrimitive.float
    }
}
