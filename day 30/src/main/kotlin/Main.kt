import java.net.NetworkInterface
import kotlin.io.path.listDirectoryEntries

/**
 * День 30. Локальная LLM как приватный сервис.
 *
 * Локальная модель (Ollama) выкладывается наружу как приватный HTTP-сервис
 * с веб-чатом: доступ по API-токену, rate limit, max context, очередь
 * генераций. Знание сервиса — весь чат AI Advent Challenge #8 (RAG Дня 28).
 *
 * Режимы:
 *   serve  (по умолчанию) — поднять сервис;
 *   index  — только построить/обновить индекс чата (удобно перед деплоем);
 *   verify — прогнать чеклист задания против работающего сервиса → output/report.md.
 */
fun main(args: Array<String>) {
    Config.loadDotEnv()
    when (args.firstOrNull() ?: "serve") {
        "serve" -> serve()
        "index" -> prepare()
        "verify" -> Verify.run()
        else -> System.err.println("Режимы: serve | index | verify")
    }
}

private fun serve() {
    val (index, corpus) = prepare() ?: return

    if (Config.apiTokens().isEmpty()) {
        System.err.println(
            "API_TOKENS не задан — сервис не должен подниматься без авторизации.\n" +
                "Добавьте в .env, например: API_TOKENS=$(openssl rand -hex 16)",
        )
        return
    }

    val chatService = ChatService(index, corpus, OllamaEmbedder(), OllamaClient())
    HttpApi(chatService, OllamaClient()).start()

    println("\n✓ Сервис запущен: http://${Config.bindHost()}:${Config.port()}")
    lanAddresses().forEach { println("  в сети:    http://$it:${Config.port()}") }
    println(
        """
        |  веб-чат:   GET  /            (токен вводится на странице)
        |  здоровье:  GET  /healthz
        |  лимиты:    GET  /v1/limits   (Authorization: Bearer <токен>)
        |  чат:       POST /v1/chat     {"message":"…"} или {"messages":[…]}
        |
        |  модель: ${Config.chatModel()} · rate limit ${Config.rateLimitPerMin()}/мин (залп ${Config.rateLimitBurst()})
        |  контекст: num_ctx ${Config.numCtx()} · вход ≤ ${Config.maxInputChars()} симв. · генераций разом ≤ ${Config.maxConcurrent()}
        """.trimMargin(),
    )
}

/** Общая подготовка: Ollama на месте, корпус распарсен, индекс загружен/построен. */
private fun prepare(): Pair<DocumentIndex, List<TgMessage>>? {
    val ollama = OllamaClient()
    val version = ollama.version() ?: run {
        System.err.println(
            "Ollama не отвечает на ${Config.ollamaBaseUrl()}.\n" +
                "Запустите сервер: ollama serve (или systemctl start ollama)",
        )
        return null
    }
    val local = ollama.localModels()
    listOf(Config.chatModel(), Config.embedModel()).forEach { model ->
        if (local.none { it == model || it.startsWith("$model:") }) {
            System.err.println("Модель $model не скачана. Выполните: ollama pull $model")
            return null
        }
    }
    println("✓ Ollama $version на ${Config.ollamaBaseUrl()} — чат: ${Config.chatModel()}, эмбеддинги: ${Config.embedModel()}")

    val dataDir = Config.dataDir()
    val messages = TelegramExport.parseDir(dataDir)
    if (messages.isEmpty()) {
        System.err.println("В $dataDir не нашлось сообщений — нужны messages*.html из экспорта Telegram.")
        return null
    }
    println(
        "✓ Корпус: ${dataDir.listDirectoryEntries("messages*.html").size} файлов экспорта — " +
            "${messages.size} сообщений, ${messages.map { it.author }.distinct().size} участников, " +
            "${messages.first().date} → ${messages.last().date}",
    )

    val indexStart = System.currentTimeMillis()
    val index = Indexer.loadOrBuild(messages, OllamaEmbedder())
    println("✓ Индекс: ${index.chunks.size} векторов, dim=${index.dim} (${System.currentTimeMillis() - indexStart} мс)")
    return index to messages
}

/** Не-loopback IPv4-адреса — по какому адресу сервис виден из сети. */
private fun lanAddresses(): List<String> =
    NetworkInterface.getNetworkInterfaces().asSequence()
        .filter { it.isUp && !it.isLoopback }
        .flatMap { it.inetAddresses.asSequence() }
        .filterIsInstance<java.net.Inet4Address>()
        .map { it.hostAddress }
        .toList()
