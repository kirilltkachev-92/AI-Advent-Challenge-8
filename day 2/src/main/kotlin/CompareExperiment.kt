/**
 * CLI для прогона сравнения без UI. Запуск: ./gradlew runCompare
 */
fun runCompareExperiment() {
    Config.loadDotEnv()
    val client = DeepSeekClient(apiKey = Config.apiKey())
    val prompt = Config.SAMPLE_COMPARE_PROMPT

    println("=== День 2: сравнение формата ответа ===")
    println("Вопрос: $prompt\n")

    for (mode in ResponseControlMode.entries) {
        println("--- ${modeLabel(mode)} ---")
        val result = client.chatWithDetails(
            client.messagesForUserPrompt(prompt, mode),
            mode,
        )
        println("Запрос:\n${result.requestBodyJson}\n")
        println("Ответ (${result.content.length} символов):\n${result.content}\n")
    }
}

fun main() = runCompareExperiment()
