/**
 * Точка входа «только сервер»: поднимает MCP-сервер планировщика и держит процесс.
 *
 * Нужна для проверки через MCP Inspector (Streamable HTTP → http://localhost:<порт>/mcp)
 * и для деплоя на VPS (там запускается именно этот main, а агент ходит по сети).
 *
 * Запуск: ./gradlew runServer   или   ./run-server.sh
 */
fun main() {
    Config.loadDotEnv()
    val port = Config.port()
    val path = Config.MCP_PATH

    val store = SampleStore(Config.dataFile())
    McpServer.schedulerServer(store).start(port, path)

    println("✓ MCP-сервер планировщика запущен: http://localhost:$port$path")
    println("  Инструменты: get_weather, record_weather_sample, weather_summary")
    println("  Хранилище замеров: ${Config.dataFile()}")
    println("  Проверка: npx @modelcontextprotocol/inspector  →  Streamable HTTP  →  этот URL")
    println("  Ctrl+C для остановки.")

    Thread.currentThread().join()
}
