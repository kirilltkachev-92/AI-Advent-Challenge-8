/**
 * Точка входа «только сервер»: поднимает MCP-сервер композиции и держит процесс.
 *
 * Нужна для проверки через MCP Inspector (Streamable HTTP → http://localhost:<порт>/mcp)
 * и для деплоя на VPS.
 *
 * Запуск: ./gradlew runServer   или   ./run-server.sh
 */
fun main() {
    Config.loadDotEnv()
    val port = Config.port()
    val path = Config.MCP_PATH

    val wiki = WikiApi(Config.wikiLang())
    McpServer.compositionServer(
        search = wiki::search,
        extract = wiki::extract,
        saver = NoteSaver(Config.outputDir()),
    ).start(port, path)

    println("✓ MCP-сервер композиции запущен: http://localhost:$port$path")
    println("  Инструменты: search, summarize, save_to_file")
    println("  Файлы сохраняются в: ${Config.outputDir()}")
    println("  Проверка: npx @modelcontextprotocol/inspector  →  Streamable HTTP  →  этот URL")
    println("  Ctrl+C для остановки.")

    Thread.currentThread().join()
}
