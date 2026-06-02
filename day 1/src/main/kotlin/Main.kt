fun main() {
    Config.loadDotEnv()
    val client = DeepSeekClient(apiKey = Config.apiKey())
    val history = mutableListOf(
        ChatMessage(role = "system", content = "You are a helpful assistant."),
    )

    println("DeepSeek CLI chat")
    println("Commands: /exit or /quit to leave, /clear to reset conversation")
    println()

    while (true) {
        print("You> ")
        System.out.flush()
        val raw = readlnOrNull()
        if (raw == null) {
            println()
            println("Input closed. Run interactively from a terminal: ./gradlew run")
            break
        }
        val input = raw.trim()
        if (input.isEmpty()) continue

        when (input.lowercase()) {
            "/exit", "/quit" -> {
                println("Bye!")
                break
            }
            "/clear" -> {
                history.removeAll { it.role != "system" }
                println("Conversation cleared.")
                continue
            }
        }

        history += ChatMessage(role = "user", content = input)

        try {
            print("Assistant> ")
            val reply = client.chat(history)
            println(reply)
            println()
            history += ChatMessage(role = "assistant", content = reply)
        } catch (e: Exception) {
            history.removeLast()
            println()
            println("Error: ${e.message}")
            println()
        }
    }
}
