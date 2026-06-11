import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Button
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.material.LinearProgressIndicator
import androidx.compose.material.MaterialTheme
import androidx.compose.material.OutlinedButton
import androidx.compose.material.OutlinedTextField
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.material.darkColors
import androidx.compose.material.lightColors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

fun main() {
    Config.loadDotEnv()
    val agent = ChatAgent(client = DeepSeekClient(apiKey = Config.apiKey()))

    application {
        val windowState = rememberWindowState(width = 1100.dp, height = 820.dp)

        Window(
            onCloseRequest = ::exitApplication,
            state = windowState,
            title = "День 8: токены и контекст",
        ) {
            MaterialTheme(
                colors = if (MaterialTheme.colors.isLight) lightColors() else darkColors(),
            ) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    TokenChatScreen(agent = agent)
                }
            }
        }
    }
}

@Composable
private fun TokenChatScreen(agent: ChatAgent) {
    val scope = rememberCoroutineScope()
    val turns = remember { mutableStateListOf<AgentTurn>().apply { addAll(agent.turns) } }
    val tokenRows = remember { mutableStateListOf<TurnTokenStats>().apply { addAll(agent.tokenHistory) } }
    var inputText by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }
    var errorText by remember { mutableStateOf<String?>(null) }
    var statusText by remember { mutableStateOf<String?>(null) }
    var activeScenario by remember { mutableStateOf<DialogScenario?>(null) }
    val listState = rememberLazyListState()
    val preview = remember(inputText, turns.size, tokenRows.size) {
        agent.previewTokens(inputText)
    }

    LaunchedEffect(turns.size, isLoading) {
        if (turns.isNotEmpty() || isLoading) {
            val target = turns.size + if (isLoading) 1 else 0
            listState.animateScrollToItem((target - 1).coerceAtLeast(0))
        }
    }

    Row(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Column(
            modifier = Modifier
                .weight(0.58f)
                .fillMaxHeight(),
        ) {
            Text(
                text = "Чат с агентом",
                style = MaterialTheme.typography.h5,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = "Лимит контекста: ${Config.formatTokenCount(agent.contextLimitTokens)} токенов · " +
                    "модель ${Config.model()}",
                style = MaterialTheme.typography.caption,
                color = MaterialTheme.colors.onSurface.copy(alpha = 0.7f),
                modifier = Modifier.padding(top = 4.dp, bottom = 8.dp),
            )

            ScenarioButtons(
                enabled = !isLoading,
                activeScenario = activeScenario,
                onScenario = { scenario ->
                    scope.launch {
                        runScenario(
                            agent = agent,
                            scenario = scenario,
                            turns = turns,
                            tokenRows = tokenRows,
                            setLoading = { isLoading = it },
                            setError = { errorText = it },
                            setStatus = { statusText = it },
                            setActiveScenario = { activeScenario = it },
                        )
                    }
                },
            )

            statusText?.let { status ->
                Text(
                    text = status,
                    color = MaterialTheme.colors.primary,
                    style = MaterialTheme.typography.caption,
                    modifier = Modifier.padding(vertical = 6.dp),
                )
            }

            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .background(MaterialTheme.colors.surface, RoundedCornerShape(8.dp))
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (turns.isEmpty() && !isLoading) {
                    item {
                        Text(
                            text = "Выберите сценарий или напишите сообщение.",
                            color = MaterialTheme.colors.onSurface.copy(alpha = 0.5f),
                        )
                    }
                }
                itemsIndexed(turns) { _, turn -> MessageBubble(turn = turn) }
                if (isLoading) {
                    item {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            CircularProgressIndicator(modifier = Modifier.height(20.dp))
                            Text("Агент думает…")
                        }
                    }
                }
            }

            errorText?.let { err ->
                Text(
                    text = err,
                    color = MaterialTheme.colors.error,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = inputText,
                    onValueChange = { inputText = it },
                    modifier = Modifier.weight(1f),
                    enabled = !isLoading,
                    placeholder = { Text("Ваше сообщение…") },
                    maxLines = 4,
                )
                Button(
                    onClick = {
                        sendMessage(
                            agent = agent,
                            text = inputText,
                            scope = scope,
                            turns = turns,
                            tokenRows = tokenRows,
                            setInput = { inputText = it },
                            setLoading = { isLoading = it },
                            setError = { errorText = it },
                            setStatus = { statusText = it },
                            clearScenario = { activeScenario = null },
                        )
                    },
                    enabled = !isLoading && inputText.isNotBlank(),
                ) {
                    Text("Отправить")
                }
            }

            OutlinedButton(
                onClick = {
                    agent.reset()
                    turns.clear()
                    tokenRows.clear()
                    errorText = null
                    statusText = null
                    inputText = ""
                    activeScenario = null
                },
                enabled = !isLoading && turns.isNotEmpty(),
                modifier = Modifier.padding(top = 8.dp),
            ) {
                Text("Очистить историю")
            }
        }

        Spacer(modifier = Modifier.width(16.dp))

        TokenPanel(
            modifier = Modifier
                .weight(0.42f)
                .fillMaxHeight(),
            preview = preview,
            historyTokens = agent.currentHistoryTokens,
            totalCostUsd = agent.totalSessionCostUsd,
            tokenRows = tokenRows,
            contextLimit = agent.contextLimitTokens,
        )
    }
}

@Composable
private fun ScenarioButtons(
    enabled: Boolean,
    activeScenario: DialogScenario?,
    onScenario: (DialogScenario) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = "Сценарии сравнения",
            style = MaterialTheme.typography.subtitle2,
            fontWeight = FontWeight.SemiBold,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DialogScenario.entries.forEach { scenario ->
                val selected = activeScenario == scenario
                OutlinedButton(
                    onClick = { onScenario(scenario) },
                    enabled = enabled,
                ) {
                    Text(
                        text = scenario.label + if (selected) " ✓" else "",
                        fontSize = 12.sp,
                    )
                }
            }
        }
        activeScenario?.let { scenario ->
            Text(
                text = scenario.description,
                style = MaterialTheme.typography.caption,
                color = MaterialTheme.colors.onSurface.copy(alpha = 0.65f),
            )
        }
    }
}

@Composable
private fun TokenPanel(
    modifier: Modifier,
    preview: TokenPreview,
    historyTokens: Int,
    totalCostUsd: Double,
    tokenRows: List<TurnTokenStats>,
    contextLimit: Int,
) {
    val usagePercent = if (inputPreviewActive(preview)) {
        preview.contextUsagePercent
    } else {
        TokenCounter.contextUsagePercent(historyTokens, contextLimit)
    }
    val barColor = when {
        usagePercent >= 100 -> Color(0xFFE53935)
        usagePercent >= 85 -> Color(0xFFFF9800)
        else -> MaterialTheme.colors.primary
    }

    Column(
        modifier = modifier
            .background(MaterialTheme.colors.surface, RoundedCornerShape(8.dp))
            .padding(14.dp),
    ) {
        Text(
            text = "Токены и стоимость",
            style = MaterialTheme.typography.h6,
            fontWeight = FontWeight.Bold,
        )

        Spacer(modifier = Modifier.height(12.dp))

        MetricRow("Текущий запрос", "${preview.userMessageTokens} (оценка)")
        MetricRow("История диалога", "$historyTokens (оценка)")
        if (inputPreviewActive(preview)) {
            MetricRow("С запросом", "${preview.historyTokensEstimated} (оценка)")
        }
        MetricRow("Лимит модели", contextLimit.toString())
        MetricRow("Сессия, USD", formatUsd(totalCostUsd))

        Spacer(modifier = Modifier.height(10.dp))
        Text(
            text = "Заполнение контекста: ${"%.1f".format(Locale.US, usagePercent)}%",
            style = MaterialTheme.typography.caption,
        )
        LinearProgressIndicator(
            progress = (usagePercent / 100.0).toFloat().coerceIn(0f, 1f),
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp)
                .height(8.dp),
            color = barColor,
        )

        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "Рост по ходам диалога",
            style = MaterialTheme.typography.subtitle2,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = "prompt = вся история · completion = ответ модели",
            style = MaterialTheme.typography.caption,
            color = MaterialTheme.colors.onSurface.copy(alpha = 0.6f),
            modifier = Modifier.padding(bottom = 8.dp),
        )

        if (tokenRows.isEmpty()) {
            Text(
                text = "Отправьте сообщение — здесь появится таблица токенов.",
                style = MaterialTheme.typography.caption,
                color = MaterialTheme.colors.onSurface.copy(alpha = 0.5f),
            )
        } else {
            TokenGrowthTable(tokenRows = tokenRows)
        }
    }
}

private fun inputPreviewActive(preview: TokenPreview): Boolean =
    preview.userMessageTokens > 0

@Composable
private fun MetricRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(text = label, style = MaterialTheme.typography.body2)
        Text(
            text = value,
            style = MaterialTheme.typography.body2,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
private fun TokenGrowthTable(tokenRows: List<TurnTokenStats>) {
    val scroll = rememberScrollState()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(scroll),
    ) {
        Row(modifier = Modifier.padding(bottom = 4.dp)) {
            TableHeaderCell("#", 28.dp)
            TableHeaderCell("запрос", 52.dp)
            TableHeaderCell("prompt", 58.dp)
            TableHeaderCell("ответ", 52.dp)
            TableHeaderCell("∑ USD", 64.dp)
            TableHeaderCell("% ctx", 52.dp)
        }
        tokenRows.forEach { row ->
            Row(
                modifier = Modifier
                    .padding(vertical = 3.dp)
                    .background(
                        MaterialTheme.colors.onSurface.copy(alpha = 0.04f),
                        RoundedCornerShape(4.dp),
                    )
                    .padding(vertical = 4.dp, horizontal = 2.dp),
            ) {
                TableCell((row.turnIndex / 2 + 1).toString(), 28.dp)
                TableCell(row.userMessageTokens.toString(), 52.dp)
                TableCell(row.promptTokens.toString() + if (row.tokensFromApi) "" else "*", 58.dp)
                TableCell(row.completionTokens.toString(), 52.dp)
                TableCell(formatUsd(row.cumulativeCostUsd), 64.dp)
                TableCell("${"%.0f".format(Locale.US, row.contextUsagePercent)}", 52.dp)
            }
        }
        Text(
            text = "* — оценка, если API не вернул usage",
            style = MaterialTheme.typography.caption,
            fontSize = 10.sp,
            color = MaterialTheme.colors.onSurface.copy(alpha = 0.5f),
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}

@Composable
private fun TableHeaderCell(text: String, width: androidx.compose.ui.unit.Dp) {
    Text(
        text = text,
        modifier = Modifier.width(width),
        fontSize = 11.sp,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colors.onSurface.copy(alpha = 0.7f),
    )
}

@Composable
private fun TableCell(text: String, width: androidx.compose.ui.unit.Dp) {
    Text(
        text = text,
        modifier = Modifier.width(width),
        fontSize = 11.sp,
        fontFamily = FontFamily.Monospace,
    )
}

@Composable
private fun MessageBubble(turn: AgentTurn) {
    when (turn) {
        is AgentTurn.SyntheticHistory -> {
            Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    modifier = Modifier
                        .widthIn(max = 520.dp)
                        .background(
                            MaterialTheme.colors.secondary.copy(alpha = 0.18f),
                            RoundedCornerShape(12.dp),
                        )
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                ) {
                    Text(
                        text = "Синтетическая история",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colors.onSurface.copy(alpha = 0.6f),
                    )
                    Text(
                        text = "${turn.messageCount} сообщений · ~${Config.formatTokenCount(turn.estimatedTokens)} " +
                            "(лимит ${Config.formatTokenCount(turn.contextLimit)})",
                        modifier = Modifier.padding(top = 4.dp),
                    )
                    Text(
                        text = "Реплики с OSS-кодом не показаны — иначе UI зависнет на ~1M токенов.",
                        style = MaterialTheme.typography.caption,
                        color = MaterialTheme.colors.onSurface.copy(alpha = 0.55f),
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
            return
        }
        else -> Unit
    }

    val isUser = turn is AgentTurn.User
    val background = if (isUser) {
        MaterialTheme.colors.primary.copy(alpha = 0.15f)
    } else {
        MaterialTheme.colors.secondary.copy(alpha = 0.12f)
    }
    val align = if (isUser) Alignment.CenterEnd else Alignment.CenterStart
    val label = if (isUser) "Вы" else "Агент"
    val content = when (turn) {
        is AgentTurn.User -> turn.content
        is AgentTurn.Assistant -> turn.content
        is AgentTurn.SyntheticHistory -> error("unreachable")
    }

    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = align) {
        Column(
            modifier = Modifier
                .widthIn(max = 420.dp)
                .background(background, RoundedCornerShape(12.dp))
                .padding(horizontal = 14.dp, vertical = 10.dp),
        ) {
            Text(
                text = label,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colors.onSurface.copy(alpha = 0.6f),
            )
            Text(text = content, modifier = Modifier.padding(top = 4.dp))
        }
    }
}

private fun sendMessage(
    agent: ChatAgent,
    text: String,
    scope: kotlinx.coroutines.CoroutineScope,
    turns: MutableList<AgentTurn>,
    tokenRows: MutableList<TurnTokenStats>,
    setInput: (String) -> Unit,
    setLoading: (Boolean) -> Unit,
    setError: (String?) -> Unit,
    setStatus: (String?) -> Unit,
    clearScenario: () -> Unit,
) {
    val trimmed = text.trim()
    if (trimmed.isEmpty()) return

    setError(null)
    setStatus(null)
    clearScenario()
    setInput("")
    turns += AgentTurn.User(trimmed)
    setLoading(true)

    scope.launch {
        val result = withContext(Dispatchers.IO) {
            agent.processUserMessage(trimmed)
        }
        setLoading(false)
        when (result) {
            is AgentResult.Success -> {
                turns += AgentTurn.Assistant(result.assistantMessage)
                tokenRows.clear()
                tokenRows.addAll(agent.tokenHistory)
                setStatus(
                    "Ход ${result.stats.turnIndex / 2 + 1}: prompt=${result.stats.promptTokens}, " +
                        "ответ=${result.stats.completionTokens}, " +
                        "стоимость хода=${formatUsd(result.stats.turnCostUsd)}",
                )
            }
            is AgentResult.Overflow -> {
                turns.removeLast()
                setError(formatOverflowMessage(result))
            }
            is AgentResult.Error -> {
                turns.removeLast()
                setError(result.message)
            }
        }
    }
}

private suspend fun runScenario(
    agent: ChatAgent,
    scenario: DialogScenario,
    turns: MutableList<AgentTurn>,
    tokenRows: MutableList<TurnTokenStats>,
    setLoading: (Boolean) -> Unit,
    setError: (String?) -> Unit,
    setStatus: (String?) -> Unit,
    setActiveScenario: (DialogScenario?) -> Unit,
) {
    setError(null)
    setStatus(null)
    setActiveScenario(scenario)

    when (scenario) {
        DialogScenario.SHORT, DialogScenario.LONG -> {
            agent.reset()
            turns.clear()
            tokenRows.clear()
            val messages = when (scenario) {
                DialogScenario.SHORT -> DialogScenarios.shortMessages
                DialogScenario.LONG -> DialogScenarios.longMessages
                else -> emptyList()
            }
            setLoading(true)
            for ((index, message) in messages.withIndex()) {
                setStatus("Сценарий «${scenario.label}»: сообщение ${index + 1}/${messages.size}…")
                turns += AgentTurn.User(message)
                val result = withContext(Dispatchers.IO) {
                    agent.processUserMessage(message)
                }
                when (result) {
                    is AgentResult.Success -> {
                        turns += AgentTurn.Assistant(result.assistantMessage)
                        tokenRows.clear()
                        tokenRows.addAll(agent.tokenHistory)
                    }
                    is AgentResult.Overflow -> {
                        turns.removeLast()
                        setError("Переполнение на сообщении ${index + 1}: ~${result.historyTokensEstimated} токенов")
                        setLoading(false)
                        return
                    }
                    is AgentResult.Error -> {
                        turns.removeLast()
                        setError("Ошибка на сообщении ${index + 1}: ${result.message}")
                        setLoading(false)
                        return
                    }
                }
                delay(300)
            }
            setLoading(false)
            setStatus(
                "Сценарий «${scenario.label}» завершён: ${agent.tokenHistory.size} ходов, " +
                    "итого ${formatUsd(agent.totalSessionCostUsd)}",
            )
        }
        DialogScenario.OVERFLOW -> {
            val modelLimit = Config.contextLimitTokens()
            agent.reset()
            agent.setContextLimit(modelLimit)
            turns.clear()
            tokenRows.clear()
            setLoading(true)

            setStatus(
                "Сборка истории из OSS-кода (${OpenSourceCodeLoader.corpusSummary()}) " +
                    "под лимит ${Config.model()} (${Config.formatTokenCount(modelLimit)})… " +
                    "30–120 сек, API пока не вызывается.",
            )

            val seededHistory = withContext(Dispatchers.Default) {
                DialogScenarios.buildOverflowHistory(
                    contextLimit = modelLimit,
                    onProgress = { progress ->
                        withContext(Dispatchers.Main) {
                            setStatus(
                                "Генерация истории: ${progress.pairsAdded} пар, " +
                                    "~${Config.formatTokenCount(progress.estimatedTokens)} / " +
                                    "${Config.formatTokenCount(progress.targetTokens)}…",
                            )
                        }
                    },
                )
            }
            agent.seedHistory(seededHistory)

            val historyTokens = agent.currentHistoryTokens
            val messageCount = agent.messageCount
            turns += AgentTurn.SyntheticHistory(
                messageCount = messageCount,
                estimatedTokens = historyTokens,
                contextLimit = modelLimit,
            )

            val probe = DialogScenarios.OVERFLOW_PROBE
            val localPreview = agent.previewTokens(probe)

            setStatus(
                "История ~${Config.formatTokenCount(historyTokens)} (лимит ${Config.formatTokenCount(modelLimit)}). " +
                    "С запросом: ~${Config.formatTokenCount(localPreview.historyTokensEstimated)}. " +
                    "Отправляем в ${Config.model()}… (ожидаем отказ API).",
            )

            turns += AgentTurn.User(probe)
            val result = withContext(Dispatchers.IO) {
                agent.processUserMessage(probe, bypassLocalOverflowGuard = true)
            }
            setLoading(false)

            when (result) {
                is AgentResult.Overflow -> {
                    turns.removeLast()
                    setError(formatOverflowMessage(result))
                }
                is AgentResult.Success -> {
                    turns += AgentTurn.Assistant(result.assistantMessage)
                    tokenRows.addAll(agent.tokenHistory)
                    setError(
                        "DeepSeek принял запрос (prompt=${result.stats.promptTokens} токенов). " +
                            "Оценка оказалась ниже реального лимита — увеличьте CONTEXT_LIMIT_TOKENS " +
                            "или OVERFLOW_API_SAFETY_MARGIN в Config.",
                    )
                }
                is AgentResult.Error -> {
                    turns.removeLast()
                    setError("Ошибка API: ${result.message}")
                }
            }
        }
    }
}

private fun formatUsd(value: Double): String =
    String.format(Locale.US, "$%.6f", value)

private fun formatOverflowMessage(result: AgentResult.Overflow): String =
    when (result.source) {
        OverflowSource.LocalGuard ->
            "⛔ Локальная защита: ~${result.historyTokensEstimated} токенов " +
                "(лимит ${result.contextLimit}). Запрос не отправлен в API."
        OverflowSource.ApiRejection ->
            buildString {
                append("⛔ DeepSeek отклонил запрос: переполнение контекста. ")
                append("Оценка ~${result.historyTokensEstimated} токенов, лимит ${result.contextLimit}. ")
                result.apiMessage?.let { append(it) }
            }
    }
