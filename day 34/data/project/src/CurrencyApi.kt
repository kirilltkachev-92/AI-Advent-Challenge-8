import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.double

object CurrencyApi {
    private const val BASE = "https://open.er-api.com/v6/latest"
    private val json = Json { ignoreUnknownKeys = true }

    fun rate(from: String, to: String): Double {
        val body = HttpFetcher.get("$BASE/$from")
        return json.parseToJsonElement(body).jsonObject
            .getValue("rates").jsonObject
            .getValue(to).jsonPrimitive.double
    }
}
