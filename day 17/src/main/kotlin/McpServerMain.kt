/**
 * Точка входа «только сервер»: поднимает MCP-сервер вокруг Open-Meteo и держит процесс.
 *
 * Нужна для:
 *   - проверки сервера через MCP Inspector (`npx @modelcontextprotocol/inspector`),
 *     указав транспорт Streamable HTTP и URL http://localhost:<порт>/mcp;
 *   - деплоя на VPS (там запускается именно этот main, а агент ходит по сети).
 *
 * Запуск: ./gradlew runServer   или   ./run-server.sh
 */
fun main() {
    Config.loadDotEnv()
    val port = Config.port()
    val path = Config.MCP_PATH

    McpServer.weatherServer().start(port, path)

    println("✓ MCP-сервер запущен: http://localhost:$port$path")
    println("  Инструменты: get_weather, geocode_city (вокруг Open-Meteo, без ключей)")
    println("  Проверка: npx @modelcontextprotocol/inspector  →  Streamable HTTP  →  этот URL")
    println("  Ctrl+C для остановки.")

    // Держим процесс живым.
    Thread.currentThread().join()
}
