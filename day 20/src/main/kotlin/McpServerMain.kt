/**
 * Точка входа «только серверы»: поднимает все четыре MCP-сервера и держит процесс.
 *
 * Нужна для проверки каждого сервера через MCP Inspector (Streamable HTTP → его URL)
 * и для деплоя. Каждый сервер — на своём порту, путь /mcp.
 *
 * Запуск: ./gradlew runServer   или   ./run-server.sh
 */
fun main() {
    Config.loadDotEnv()
    val specs = Config.servers()
    Boot.startAllServers(specs)

    println("✓ Запущено MCP-серверов: ${specs.size}")
    specs.forEach { println("   • ${it.name} → ${it.url}") }
    println("  Файлы storage-MCP сохраняются в: ${Config.outputDir()}")
    println("  Проверка: npx @modelcontextprotocol/inspector → Streamable HTTP → любой из URL выше")
    println("  Ctrl+C для остановки.")

    Thread.currentThread().join()
}
