/**
 * Агрегирование сохранённых замеров в сводку (требование задания:
 * «возвращать агрегированный результат»). Чистая функция над списком замеров —
 * её зовёт MCP-инструмент weather_summary и её же удобно проверять юнит-тестом.
 */
object WeatherSummary {

    /** Человекочитаемая сводка по городу. Если замеров нет — честно об этом сообщаем. */
    fun aggregate(city: String, samples: List<WeatherSample>): String {
        if (samples.isEmpty()) {
            return "По «$city» ещё нет ни одного замера — планировщик пока не собрал данные."
        }
        val temps = samples.map { it.temperatureC }
        val winds = samples.map { it.windSpeed }
        val latest = samples.last()
        val avgTemp = temps.average()
        val minTemp = temps.min()
        val maxTemp = temps.max()
        val avgWind = winds.average()

        // Самое частое состояние неба за период.
        val sky = samples.groupingBy { it.description }.eachCount()
            .maxByOrNull { it.value }?.key ?: latest.description

        val delta = latest.temperatureC - avgTemp
        val trend = when {
            delta > 0.3 -> "теплеет (последний замер на ${fmt(delta)}°C выше среднего)"
            delta < -0.3 -> "холодает (последний замер на ${fmt(-delta)}°C ниже среднего)"
            else -> "стабильно (около среднего)"
        }

        return buildString {
            appendLine("Сводка по «$city» за период ${latest(samples)} (${samples.size} замер(ов)):")
            appendLine("  • температура: сейчас ${fmt(latest.temperatureC)}°C, " +
                "средняя ${fmt(avgTemp)}°C, диапазон ${fmt(minTemp)}…${fmt(maxTemp)}°C")
            appendLine("  • ветер: средний ${fmt(avgWind)} км/ч")
            appendLine("  • небо: чаще всего «$sky», сейчас «${latest.description}»")
            append("  • тренд: $trend")
        }
    }

    private fun latest(samples: List<WeatherSample>): String {
        val from = samples.first().fetchedAt
        val to = samples.last().fetchedAt
        return if (from == to) from else "$from … $to"
    }

    private fun fmt(v: Double): String = "%.1f".format(v)
}
