/**
 * Инфопанель: погода в Москве и курс доллара одной командой.
 */
fun main() {
    val temp = WeatherApi.currentTemp(lat = 55.75, lon = 37.62)
    val usd = CurrencyApi.rate(from = "USD", to = "RUB")
    println("Москва: %.1f °C · USD = %.2f ₽".format(temp, usd))
}
