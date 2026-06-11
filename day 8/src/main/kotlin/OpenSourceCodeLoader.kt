/**
 * Open-source код из JetBrains/intellij-community (Apache 2.0) для демо токенов.
 */
object OpenSourceCodeLoader {
    data class OssSource(
        val fileName: String,
        val resourcePath: String,
        val repository: String = "JetBrains/intellij-community",
    ) {
        val label: String get() = "$fileName — $repository (Apache 2.0)"
    }

    data class CodeChunk(
        val source: OssSource,
        val fromLine: Int,
        val toLine: Int,
        val text: String,
    )

    private val corpus = listOf(
        OssSource("StringUtil.java", "/oss/StringUtil.java"),
        OssSource("FileUtil.java", "/oss/FileUtil.java"),
    )

    private val longDialogPrompts = listOf(
        "Кратко опиши, что делает этот фрагмент кода.",
        "Какие публичные методы здесь объявлены и зачем они нужны?",
        "Есть ли здесь работа со строками, регулярками или коллекциями?",
        "Какие edge-case'ы обрабатывает этот кусок?",
        "Что можно упростить или вынести в отдельный метод?",
        "Какие зависимости от других классов IntelliJ Platform видны?",
        "Оцени читаемость: что усложняет понимание?",
        "Есть ли здесь потенциальные проблемы с производительностью?",
        "Как бы ты протестировал этот фрагмент?",
        "Какие паттерны проектирования узнаёшь?",
        "Что делает код с null и пустыми строками?",
        "Суммируй фрагмент в 2–3 предложения для code review.",
    )

    fun longDialogMessages(chunks: Int = 12): List<String> {
        val codeChunks = loadChunks(linesPerChunk = 280).take(chunks)
        require(codeChunks.size == chunks) {
            "Недостаточно фрагментов кода для длинного диалога (нужно $chunks)"
        }

        return codeChunks.mapIndexed { index, chunk ->
            val prompt = longDialogPrompts[index % longDialogPrompts.size]
            buildString {
                append("Фрагмент ${index + 1}/$chunks (${chunk.source.label}, строки ${chunk.fromLine}–${chunk.toLine}).\n")
                append("$prompt\n\n")
                append("```java\n")
                append(chunk.text)
                append("\n```")
            }
        }
    }

    suspend fun buildOverflowHistory(
        contextLimit: Int,
        systemPrompt: String,
        overflowProbe: String,
        safetyMargin: Double,
        onProgress: (suspend (DialogScenarios.OverflowBuildProgress) -> Unit)? = null,
    ): List<ChatMessage> {
        val probeTokens = TokenCounter.estimateUserMessageTokens(overflowProbe)
        val targetTokens = (contextLimit * safetyMargin).toInt() + probeTokens
        val history = mutableListOf(ChatMessage(role = "system", content = systemPrompt))
        val chunks = loadChunks(linesPerChunk = linesPerChunkFor(contextLimit))
        require(chunks.isNotEmpty()) { "Корпус OSS-кода пуст" }

        var turn = 1
        var chunkIndex = 0
        val progressStep = progressStepFor(contextLimit)

        while (TokenCounter.estimateMessagesTokens(history) < targetTokens) {
            val chunk = chunks[chunkIndex % chunks.size]
            history += ChatMessage(role = "user", content = overflowUserMessage(turn, chunk))
            history += ChatMessage(role = "assistant", content = overflowAssistantMessage(turn, chunk))

            if (turn == 1 || turn % progressStep == 0) {
                onProgress?.invoke(
                    DialogScenarios.OverflowBuildProgress(
                        pairsAdded = turn,
                        estimatedTokens = TokenCounter.estimateMessagesTokens(history),
                        targetTokens = targetTokens,
                    ),
                )
            }
            turn++
            chunkIndex++
            if (turn > 50_000) {
                error(
                    "Не удалось набрать ${Config.formatTokenCount(targetTokens)} токенов " +
                        "из OSS-кода за $turn пар",
                )
            }
        }

        onProgress?.invoke(
            DialogScenarios.OverflowBuildProgress(
                pairsAdded = turn - 1,
                estimatedTokens = TokenCounter.estimateMessagesTokens(history),
                targetTokens = targetTokens,
            ),
        )
        return history
    }

    fun buildOverflowHistoryCompact(
        contextLimit: Int,
        overflowProbe: String,
        systemPrompt: String,
    ): List<ChatMessage> {
        val history = mutableListOf(ChatMessage(role = "system", content = systemPrompt))
        val probeTokens = TokenCounter.estimateUserMessageTokens(overflowProbe)
        val chunks = loadChunks(linesPerChunk = 8)
        var turn = 1
        var chunkIndex = 0

        while (TokenCounter.estimateMessagesTokens(history) + probeTokens <= contextLimit) {
            val chunk = chunks[chunkIndex % chunks.size]
            history += ChatMessage(role = "user", content = overflowUserMessage(turn, chunk))
            history += ChatMessage(
                role = "assistant",
                content = overflowAssistantMessage(turn, chunk),
            )
            turn++
            chunkIndex++
        }
        return history
    }

    fun corpusSummary(): String =
        corpus.joinToString { it.label }

    private fun loadChunks(linesPerChunk: Int): List<CodeChunk> {
        val result = mutableListOf<CodeChunk>()
        for (source in corpus) {
            val lines = readResource(source.resourcePath).lines()
            var nextLine = 1
            for (chunkLines in lines.chunked(linesPerChunk)) {
                if (chunkLines.isEmpty()) continue
                val fromLine = nextLine
                val toLine = fromLine + chunkLines.size - 1
                nextLine = toLine + 1
                result += CodeChunk(
                    source = source,
                    fromLine = fromLine,
                    toLine = toLine,
                    text = chunkLines.joinToString("\n"),
                )
            }
        }
        return result
    }

    private fun overflowUserMessage(turn: Int, chunk: CodeChunk): String =
        buildString {
            append("Контекст #$turn: добавь в историю фрагмент из ${chunk.source.fileName} ")
            append("(строки ${chunk.fromLine}–${chunk.toLine}, ${chunk.source.repository}).\n\n")
            append("```java\n")
            append(chunk.text)
            append("\n```")
        }

    private fun overflowAssistantMessage(turn: Int, chunk: CodeChunk): String =
        "Принято #$turn: фрагмент ${chunk.source.fileName} " +
            "(${chunk.fromLine}–${chunk.toLine}) учтён в контексте диалога."

    private fun linesPerChunkFor(contextLimit: Int): Int = when {
        contextLimit <= 10_000 -> 12
        contextLimit <= 100_000 -> 80
        contextLimit <= 500_000 -> 150
        else -> 220
    }

    private fun progressStepFor(contextLimit: Int): Int = when {
        contextLimit <= 100_000 -> 5
        contextLimit <= 500_000 -> 15
        else -> 40
    }

    private fun readResource(path: String): String {
        val normalized = if (path.startsWith("/")) path else "/$path"
        val stream = OpenSourceCodeLoader::class.java.getResourceAsStream(normalized)
            ?: error("Не найден ресурс $normalized")
        return stream.bufferedReader().use { it.readText() }
    }
}
