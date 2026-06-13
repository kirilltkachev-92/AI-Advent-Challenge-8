import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Button
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.material.LinearProgressIndicator
import androidx.compose.material.MaterialTheme
import androidx.compose.material.OutlinedButton
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
import androidx.compose.runtime.snapshotFlow
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
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

private sealed class ChatItem {
    data class Turn(
        val comparison: TurnComparison,
        val summaryPreview: String? = null,
    ) : ChatItem()
}

fun main() {
    Config.loadDotEnv()
    val client = DeepSeekClient(apiKey = Config.apiKey())

    application {
        val windowState = rememberWindowState(width = 1280.dp, height = 900.dp)

        Window(
            onCloseRequest = ::exitApplication,
            state = windowState,
            title = "День 9: сжатие истории — сравнение",
        ) {
            MaterialTheme(
                colors = if (MaterialTheme.colors.isLight) lightColors() else darkColors(),
            ) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    ComparisonScreen(client = client)
                }
            }
        }
    }
}

@Composable
private fun ComparisonScreen(client: ChatClient) {
    val scope = rememberCoroutineScope()
    val chatItems = remember { mutableStateListOf<ChatItem>() }
    val chatListState = rememberLazyListState()
    var isLoading by remember { mutableStateOf(false) }
    var errorText by remember { mutableStateOf<String?>(null) }
    var statusText by remember { mutableStateOf<String?>(null) }
    var compareResult by remember { mutableStateOf<CompareResult?>(null) }
    val analyticsScroll = rememberScrollState()

    LaunchedEffect(chatItems.size, isLoading) {
        if (chatItems.isNotEmpty() || isLoading) {
            val target = chatItems.size + if (isLoading) 1 else 0
            chatListState.animateScrollToItem((target - 1).coerceAtLeast(0))
        }
    }

    LaunchedEffect(compareResult?.turns?.size, isLoading) {
        val turnCount = compareResult?.turns?.size ?: 0
        if (turnCount == 0 && !isLoading) return@LaunchedEffect
        snapshotFlow { analyticsScroll.maxValue }
            .distinctUntilChanged()
            .collect { max ->
                if (max > 0) {
                    analyticsScroll.animateScrollTo(max)
                }
            }
    }

    fun resetState() {
        chatItems.clear()
        compareResult = null
        errorText = null
        statusText = null
        scope.launch { analyticsScroll.scrollTo(0) }
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text(
            text = "Сжатие истории: сравнение с/без компрессии",
            style = MaterialTheme.typography.h5,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = "Окно ${Config.recentMessageCount()} сообщений · батч ${Config.compressBatchSize()} · ${Config.model()}",
            style = MaterialTheme.typography.caption,
            color = MaterialTheme.colors.onSurface.copy(alpha = 0.65f),
            modifier = Modifier.padding(top = 4.dp, bottom = 10.dp),
        )

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(
                onClick = {
                    scope.launch {
                        isLoading = true
                        resetState()
                        statusText = "Запуск сценария (${CompareScenario.messages.size} ходов)…"
                        val partialTurns = mutableListOf<TurnComparison>()
                        try {
                            val result = withContext(Dispatchers.IO) {
                                CompareScenario.run(
                                    client = client,
                                    onProgress = { current, total, phase ->
                                        withContext(Dispatchers.Main) {
                                            statusText = "Ход $current/$total · фаза «${phase.label}»…"
                                        }
                                    },
                                    onTurn = { turn, compressed ->
                                        withContext(Dispatchers.Main) {
                                            chatItems += ChatItem.Turn(
                                                comparison = turn,
                                                summaryPreview = if (turn.compressionJustHappened) {
                                                    compressed.summary
                                                } else {
                                                    null
                                                },
                                            )
                                            partialTurns += turn
                                            compareResult = CompareResult(
                                                turns = partialTurns.toList(),
                                                plainTotalPromptTokens = partialTurns.sumOf { it.plainPromptTokens },
                                                compressedTotalPromptTokens = partialTurns.sumOf { it.compressedPromptTokens },
                                                plainTotalCostUsd = partialTurns.sumOf { it.plainCostUsd },
                                                compressedTotalCostUsd = partialTurns.sumOf { it.compressedCostUsd },
                                                compressionEvents = turn.compressionCountAfter,
                                                finalSummary = compressed.summary,
                                            )
                                        }
                                        delay(250)
                                    },
                                )
                            }
                            compareResult = result
                            statusText =
                                "Готово: −${result.totalTokensSaved} prompt-токенов " +
                                    "(${String.format(Locale.US, "%.1f", result.savingsPercent)}%), " +
                                    "сжатий: ${result.compressionEvents}"
                        } catch (e: Exception) {
                            errorText = e.message ?: e.toString()
                            statusText = null
                        } finally {
                            isLoading = false
                        }
                    }
                },
                enabled = !isLoading,
            ) {
                Text("Запустить сравнение")
            }
            OutlinedButton(onClick = { if (!isLoading) resetState() }, enabled = !isLoading) {
                Text("Очистить")
            }
            if (isLoading) {
                CircularProgressIndicator(modifier = Modifier.height(22.dp))
            }
            statusText?.let {
                Text(text = it, color = MaterialTheme.colors.primary, fontSize = 13.sp)
            }
        }

        errorText?.let {
            Text(text = it, color = MaterialTheme.colors.error, modifier = Modifier.padding(top = 6.dp))
        }

        Spacer(modifier = Modifier.height(10.dp))

        Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
            ChatPanel(
                modifier = Modifier
                    .weight(0.58f)
                    .fillMaxHeight(),
                items = chatItems,
                listState = chatListState,
                isLoading = isLoading,
            )

            Spacer(modifier = Modifier.width(14.dp))

            Column(
                modifier = Modifier
                    .weight(0.42f)
                    .fillMaxHeight()
                    .verticalScroll(analyticsScroll),
            ) {
                ScenarioOutline()
                Spacer(modifier = Modifier.height(12.dp))
                when {
                    compareResult != null -> AnalyticsContent(compareResult!!)
                    !isLoading -> {
                        Text(
                            text = "Чат слева заполнится автоматически при запуске сценария.",
                            color = MaterialTheme.colors.onSurface.copy(alpha = 0.5f),
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ChatPanel(
    modifier: Modifier,
    items: List<ChatItem>,
    listState: androidx.compose.foundation.lazy.LazyListState,
    isLoading: Boolean,
) {
    Column(modifier = modifier) {
        Text(
            text = "Чат (автоматический прогон)",
            style = MaterialTheme.typography.subtitle1,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(bottom = 8.dp),
        )

        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(MaterialTheme.colors.surface, RoundedCornerShape(8.dp))
                .padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (items.isEmpty() && !isLoading) {
                item {
                    Text(
                        text = "Нажмите «Запустить сравнение» — сообщения появятся здесь сами.",
                        color = MaterialTheme.colors.onSurface.copy(alpha = 0.5f),
                        fontSize = 13.sp,
                    )
                }
            }
            items(items, key = { item -> "t-${(item as ChatItem.Turn).comparison.turnIndex}" }) { item ->
                TurnChatCard(item as ChatItem.Turn)
            }
            if (isLoading) {
                item {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        CircularProgressIndicator(modifier = Modifier.height(18.dp))
                        Text("Ожидание ответа…", fontSize = 13.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun TurnChatCard(item: ChatItem.Turn) {
    val turn = item.comparison
    val borderColor = when (turn.phase) {
        ScenarioPhase.FACTS -> Color(0xFF1565C0)
        ScenarioPhase.VOLUME -> Color(0xFF6A1B9A)
        ScenarioPhase.VERIFY -> Color(0xFF2E7D32)
    }
    val savingsPercent = if (turn.plainPromptTokens > 0) {
        turn.tokensSaved.toDouble() / turn.plainPromptTokens * 100.0
    } else {
        0.0
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .background(borderColor.copy(alpha = 0.07f), RoundedCornerShape(10.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "#${turn.turnIndex}",
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(end = 8.dp),
            )
            PhaseBadge(turn.phase, compact = true)
            if (turn.compressionJustHappened) {
                Text(
                    text = " · ⚡ сжатие #${turn.compressionCountAfter}",
                    fontSize = 11.sp,
                    color = Color(0xFFE65100),
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }

        Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
            Text(
                text = turn.userMessage,
                fontSize = 13.sp,
                modifier = Modifier
                    .widthIn(max = 520.dp)
                    .background(MaterialTheme.colors.primary.copy(alpha = 0.12f), RoundedCornerShape(10.dp))
                    .padding(10.dp),
            )
        }

        item.summaryPreview?.let { summary ->
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFFE65100).copy(alpha = 0.1f), RoundedCornerShape(8.dp))
                    .padding(10.dp),
            ) {
                Text(
                    text = "⚡ ${Config.compressBatchSize()} сообщений → summary",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color(0xFFE65100),
                )
                if (summary.isNotBlank()) {
                    Text(
                        text = summary,
                        fontSize = 11.sp,
                        color = MaterialTheme.colors.onSurface.copy(alpha = 0.8f),
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        }

        ChatReplyBlock(
            title = "Без сжатия",
            content = turn.plainReply,
            promptTokens = turn.plainPromptTokens,
            accentColor = MaterialTheme.colors.primary,
        )
        ChatReplyBlock(
            title = "Со сжатием",
            content = turn.compressedReply,
            promptTokens = turn.compressedPromptTokens,
            accentColor = Color(0xFF2E7D32),
        )

        TurnTokenSummary(
            plainTokens = turn.plainPromptTokens,
            compressedTokens = turn.compressedPromptTokens,
            tokensSaved = turn.tokensSaved,
            savingsPercent = savingsPercent,
        )
    }
}

@Composable
private fun ChatReplyBlock(
    title: String,
    content: String,
    promptTokens: Int,
    accentColor: Color,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(accentColor.copy(alpha = 0.08f), RoundedCornerShape(8.dp))
            .padding(10.dp),
    ) {
        Text(
            text = title,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            color = accentColor,
        )
        Text(
            text = content,
            fontSize = 13.sp,
            modifier = Modifier.padding(top = 6.dp),
        )
        Text(
            text = "prompt: $promptTokens tok",
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colors.onSurface.copy(alpha = 0.55f),
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

@Composable
private fun TurnTokenSummary(
    plainTokens: Int,
    compressedTokens: Int,
    tokensSaved: Int,
    savingsPercent: Double,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colors.onSurface.copy(alpha = 0.05f), RoundedCornerShape(8.dp))
            .padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Экономия на ходе",
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = "−$tokensSaved tok (${String.format(Locale.US, "%.0f", savingsPercent)}%)",
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                color = if (tokensSaved > 0) Color(0xFF2E7D32) else MaterialTheme.colors.onSurface,
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = plainTokens.toString(),
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colors.primary,
            )
            Text("→", fontSize = 11.sp, color = MaterialTheme.colors.onSurface.copy(alpha = 0.4f))
            Text(
                text = compressedTokens.toString(),
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                color = Color(0xFF2E7D32),
            )
            val maxTok = plainTokens.coerceAtLeast(1)
            LinearProgressIndicator(
                progress = (compressedTokens.toFloat() / maxTok).coerceIn(0f, 1f),
                modifier = Modifier.weight(1f).height(6.dp),
                color = Color(0xFF2E7D32),
            )
        }
    }
}

@Composable
private fun AnalyticsContent(result: CompareResult) {
    SummaryBlock(result)
    Spacer(modifier = Modifier.height(16.dp))
    PhaseSection(
        title = "Проверка качества",
        subtitle = "Ответы на вопросы по фактам из начала диалога — после сжатия",
        turns = result.verifyTurns,
        highlight = true,
    )
}

@Composable
private fun ScenarioOutline() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colors.surface, RoundedCornerShape(8.dp))
            .padding(12.dp),
    ) {
        Text("Сценарий (${CompareScenario.messages.size} ходов)", fontWeight = FontWeight.SemiBold)
        Spacer(modifier = Modifier.height(6.dp))
        ScenarioPhase.entries.forEach { phase ->
            val count = CompareScenario.messages.count { it.phase == phase }
            Row(modifier = Modifier.padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                PhaseBadge(phase, compact = true)
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "${phase.label} · $count — ${phase.description}",
                    fontSize = 11.sp,
                    color = MaterialTheme.colors.onSurface.copy(alpha = 0.75f),
                )
            }
        }
    }
}

@Composable
private fun SummaryBlock(result: CompareResult) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colors.primary.copy(alpha = 0.08f), RoundedCornerShape(8.dp))
            .padding(14.dp),
    ) {
        Text("Итог", style = MaterialTheme.typography.h6, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(8.dp))
        Row(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.weight(1f)) {
                MetricRow("Без сжатия, Σ prompt", result.plainTotalPromptTokens.toString())
                MetricRow("Со сжатием, Σ prompt", result.compressedTotalPromptTokens.toString())
                MetricRow(
                    "Экономия",
                    "${result.totalTokensSaved} (${String.format(Locale.US, "%.1f", result.savingsPercent)}%)",
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                MetricRow("Сжатий", result.compressionEvents.toString())
                MetricRow("Без сжатия, USD", formatUsd(result.plainTotalCostUsd))
                MetricRow("Со сжатием, USD", formatUsd(result.compressedTotalCostUsd))
            }
        }
        result.finalSummary?.let { summary ->
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = "Итоговый summary (${TokenCounter.estimateTextTokens(summary)} ток. оценка)",
                fontWeight = FontWeight.SemiBold,
                fontSize = 12.sp,
                color = Color(0xFF2E7D32),
            )
            Text(text = summary, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
        }
    }
}

@Composable
private fun PhaseSection(
    title: String,
    subtitle: String,
    turns: List<TurnComparison>,
    highlight: Boolean,
) {
    Text(text = title, style = MaterialTheme.typography.subtitle1, fontWeight = FontWeight.SemiBold)
    Text(
        text = subtitle,
        style = MaterialTheme.typography.caption,
        color = MaterialTheme.colors.onSurface.copy(alpha = 0.6f),
        modifier = Modifier.padding(bottom = 10.dp),
    )
    turns.forEach { turn ->
        TurnComparisonCard(turn = turn, emphasize = highlight)
    }
}

@Composable
private fun TurnComparisonCard(turn: TurnComparison, emphasize: Boolean) {
    val borderColor = when (turn.phase) {
        ScenarioPhase.FACTS -> Color(0xFF1565C0)
        ScenarioPhase.VOLUME -> Color(0xFF6A1B9A)
        ScenarioPhase.VERIFY -> Color(0xFF2E7D32)
    }
    val bgAlpha = if (emphasize) 0.1f else 0.04f

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp)
            .background(borderColor.copy(alpha = bgAlpha), RoundedCornerShape(8.dp))
            .padding(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "#${turn.turnIndex}",
                fontWeight = FontWeight.Bold,
                fontSize = 12.sp,
                modifier = Modifier.padding(end = 8.dp),
            )
            PhaseBadge(turn.phase)
            if (turn.compressionJustHappened) {
                Text(
                    text = " · сжатие #${turn.compressionCountAfter}",
                    fontSize = 11.sp,
                    color = Color(0xFFE65100),
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
        Text(
            text = turn.userMessage,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(top = 8.dp),
        )
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            ReplyColumn(
                modifier = Modifier.weight(1f),
                title = "Без сжатия",
                tokens = turn.plainPromptTokens,
                reply = turn.plainReply,
                titleColor = MaterialTheme.colors.primary,
            )
            ReplyColumn(
                modifier = Modifier.weight(1f),
                title = "Со сжатием",
                tokens = turn.compressedPromptTokens,
                tokensSaved = turn.tokensSaved,
                reply = turn.compressedReply,
                titleColor = Color(0xFF2E7D32),
            )
        }
        TurnTokenSummary(
            plainTokens = turn.plainPromptTokens,
            compressedTokens = turn.compressedPromptTokens,
            tokensSaved = turn.tokensSaved,
            savingsPercent = if (turn.plainPromptTokens > 0) {
                turn.tokensSaved.toDouble() / turn.plainPromptTokens * 100.0
            } else {
                0.0
            },
        )
    }
}

@Composable
private fun ReplyColumn(
    modifier: Modifier,
    title: String,
    tokens: Int,
    reply: String,
    titleColor: Color,
    tokensSaved: Int? = null,
) {
    Column(
        modifier = modifier
            .background(MaterialTheme.colors.onSurface.copy(alpha = 0.04f), RoundedCornerShape(6.dp))
            .padding(10.dp),
    ) {
        Text(
            text = title,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            color = titleColor,
        )
        Text(text = reply, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
        Text(
            text = buildString {
                append("prompt: $tokens tok")
                tokensSaved?.takeIf { it > 0 }?.let { append(" · −$it") }
            },
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colors.onSurface.copy(alpha = 0.55f),
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

@Composable
private fun PhaseBadge(phase: ScenarioPhase, compact: Boolean = false) {
    val (bg, fg) = when (phase) {
        ScenarioPhase.FACTS -> Color(0xFF1565C0).copy(alpha = 0.15f) to Color(0xFF1565C0)
        ScenarioPhase.VOLUME -> Color(0xFF6A1B9A).copy(alpha = 0.15f) to Color(0xFF6A1B9A)
        ScenarioPhase.VERIFY -> Color(0xFF2E7D32).copy(alpha = 0.15f) to Color(0xFF2E7D32)
    }
    Box(
        modifier = Modifier
            .background(bg, RoundedCornerShape(4.dp))
            .padding(horizontal = if (compact) 6.dp else 8.dp, vertical = 2.dp),
    ) {
        Text(
            text = phase.label,
            fontSize = if (compact) 10.sp else 11.sp,
            fontWeight = FontWeight.SemiBold,
            color = fg,
        )
    }
}

@Composable
private fun MetricRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
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

private fun formatUsd(value: Double): String =
    String.format(Locale.US, "$%.6f", value)
