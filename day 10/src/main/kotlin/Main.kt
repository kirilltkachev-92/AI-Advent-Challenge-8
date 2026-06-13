import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.material.MaterialTheme
import androidx.compose.material.OutlinedButton
import androidx.compose.material.OutlinedTextField
import androidx.compose.material.Surface
import androidx.compose.material.Tab
import androidx.compose.material.TabRow
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

fun main() {
    Config.loadDotEnv()
    val client = DeepSeekClient(apiKey = Config.apiKey())

    application {
        val windowState = rememberWindowState(width = 1360.dp, height = 920.dp)
        Window(
            onCloseRequest = ::exitApplication,
            state = windowState,
            title = "День 10: стратегии управления контекстом",
        ) {
            MaterialTheme(colors = if (MaterialTheme.colors.isLight) lightColors() else darkColors()) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    AppScreen(client = client)
                }
            }
        }
    }
}

private enum class ScreenTab(val title: String) {
    INTERACTIVE("Интерактив"),
    COMPARE("Сравнение"),
}

@Composable
private fun AppScreen(client: ChatClient) {
    var tab by remember { mutableStateOf(ScreenTab.COMPARE) }
    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text(
            text = "Управление контекстом: Sliding Window / Facts / Branching",
            style = MaterialTheme.typography.h5,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = "Окно ${Config.windowSize()} сообщений · ${Config.model()} · без summary",
            style = MaterialTheme.typography.caption,
            color = MaterialTheme.colors.onSurface.copy(alpha = 0.65f),
            modifier = Modifier.padding(top = 4.dp, bottom = 10.dp),
        )
        TabRow(selectedTabIndex = tab.ordinal) {
            ScreenTab.entries.forEach { screenTab ->
                Tab(
                    selected = tab == screenTab,
                    onClick = { tab = screenTab },
                    text = { Text(screenTab.title) },
                )
            }
        }
        Spacer(modifier = Modifier.height(12.dp))
        when (tab) {
            ScreenTab.INTERACTIVE -> InteractiveScreen(client)
            ScreenTab.COMPARE -> CompareScreen(client)
        }
    }
}

@Composable
private fun InteractiveScreen(client: ChatClient) {
    var strategy by remember { mutableStateOf(ContextStrategy.SLIDING_WINDOW) }
    val sliding = remember(client) { SlidingWindowAgent(client) }
    val facts = remember(client) { FactsAgent(client) }
    val branching = remember(client) { BranchingAgent(client) }
    var input by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }
    var errorText by remember { mutableStateOf<String?>(null) }
    val listState = rememberLazyListState()
    val infoScroll = rememberScrollState()
    val scope = rememberCoroutineScope()

    fun activeAgent(): ContextAgent = when (strategy) {
        ContextStrategy.SLIDING_WINDOW -> sliding
        ContextStrategy.FACTS -> facts
        ContextStrategy.BRANCHING -> branching
    }

    LaunchedEffect(activeAgent().turns.size) {
        if (activeAgent().turns.isNotEmpty()) {
            listState.animateScrollToItem(activeAgent().turns.lastIndex)
        }
    }

    LaunchedEffect(activeAgent().turns.size, strategy) {
        snapshotFlow { infoScroll.maxValue }
            .distinctUntilChanged()
            .collect { max ->
                if (max > 0) infoScroll.animateScrollTo(max)
            }
    }

    Row(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.weight(0.62f).fillMaxHeight()) {
            StrategySelector(strategy) { selected ->
                strategy = selected
                errorText = null
            }
            Spacer(modifier = Modifier.height(8.dp))
            if (strategy == ContextStrategy.BRANCHING) {
                BranchingControls(branching, enabled = !isLoading)
                Spacer(modifier = Modifier.height(8.dp))
            }
            if (strategy == ContextStrategy.FACTS && facts.factsSnapshot.isNotEmpty()) {
                FactsPanel(facts.factsSnapshot)
                Spacer(modifier = Modifier.height(8.dp))
            }
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .background(MaterialTheme.colors.surface, RoundedCornerShape(8.dp))
                    .padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items(activeAgent().turns) { turn ->
                    TurnBubble(turn)
                }
                if (isLoading) {
                    item {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(modifier = Modifier.height(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Ожидание…", fontSize = 13.sp)
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    modifier = Modifier.weight(1f),
                    enabled = !isLoading,
                    placeholder = { Text("Сообщение…") },
                    singleLine = true,
                )
                Spacer(modifier = Modifier.width(8.dp))
                Button(
                    onClick = {
                        val text = input.trim()
                        if (text.isEmpty()) return@Button
                        scope.launch {
                            isLoading = true
                            errorText = null
                            try {
                                val result = withContext(Dispatchers.IO) {
                                    activeAgent().processUserMessage(text)
                                }
                                when (result) {
                                    is AgentResult.Success -> input = ""
                                    is AgentResult.Error -> errorText = result.message
                                }
                            } finally {
                                isLoading = false
                            }
                        }
                    },
                    enabled = !isLoading && input.isNotBlank(),
                ) { Text("Отправить") }
                OutlinedButton(
                    onClick = {
                        sliding.reset()
                        facts.reset()
                        branching.reset()
                        errorText = null
                    },
                    enabled = !isLoading,
                    modifier = Modifier.padding(start = 6.dp),
                ) { Text("Сброс") }
            }
            errorText?.let {
                Text(it, color = MaterialTheme.colors.error, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
            }
        }
        Column(
            modifier = Modifier
                .weight(0.38f)
                .fillMaxHeight()
                .padding(start = 14.dp)
                .verticalScroll(infoScroll),
        ) {
            StrategyInfoCard(strategy)
        }
    }
}

@Composable
private fun CompareScreen(client: ChatClient) {
    val scope = rememberCoroutineScope()
    var isLoading by remember { mutableStateOf(false) }
    var statusText by remember { mutableStateOf<String?>(null) }
    var errorText by remember { mutableStateOf<String?>(null) }
    var result by remember { mutableStateOf<CompareAllResult?>(null) }
    val liveTurns = remember { mutableStateListOf<StrategyTurnResult>() }
    val chatListState = rememberLazyListState()
    val analyticsScroll = rememberScrollState()

    LaunchedEffect(liveTurns.size) {
        if (liveTurns.isNotEmpty()) {
            chatListState.animateScrollToItem(liveTurns.lastIndex)
        }
    }

    LaunchedEffect(liveTurns.size, result, isLoading) {
        if (liveTurns.isEmpty() && result == null && !isLoading) return@LaunchedEffect
        snapshotFlow { analyticsScroll.maxValue }
            .distinctUntilChanged()
            .collect { max ->
                if (max > 0) analyticsScroll.animateScrollTo(max)
            }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(
                onClick = {
                    scope.launch {
                        isLoading = true
                        errorText = null
                        result = null
                        liveTurns.clear()
                        statusText = "Запуск сценария на 3 стратегиях…"
                        try {
                            val compareResult = withContext(Dispatchers.IO) {
                                CompareScenario.runAll(
                                    client = client,
                                    onProgress = { strategy, current, total ->
                                        withContext(Dispatchers.Main) {
                                            statusText = "${strategy.label}: ход $current/$total…"
                                        }
                                    },
                                    onTurn = { turn ->
                                        withContext(Dispatchers.Main) {
                                            liveTurns += turn
                                        }
                                        delay(150)
                                    },
                                )
                            }
                            result = compareResult
                            statusText = "Готово: ${CompareScenario.linearMessages.size} ходов × 3 стратегии"
                        } catch (e: Exception) {
                            errorText = e.message ?: e.toString()
                            statusText = null
                        } finally {
                            isLoading = false
                        }
                    }
                },
                enabled = !isLoading,
            ) { Text("Запустить сравнение") }
            OutlinedButton(
                onClick = {
                    if (!isLoading) {
                        result = null
                        liveTurns.clear()
                        statusText = null
                        errorText = null
                    }
                },
                enabled = !isLoading,
            ) { Text("Очистить") }
            if (isLoading) CircularProgressIndicator(modifier = Modifier.height(22.dp))
            statusText?.let { Text(it, color = MaterialTheme.colors.primary, fontSize = 13.sp) }
        }
        errorText?.let {
            Text(it, color = MaterialTheme.colors.error, modifier = Modifier.padding(top = 6.dp))
        }
        Spacer(modifier = Modifier.height(10.dp))
        Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
            Column(modifier = Modifier.weight(0.55f).fillMaxHeight()) {
                Text("Прогон сценария", fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(bottom = 6.dp))
                LazyColumn(
                    state = chatListState,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .background(MaterialTheme.colors.surface, RoundedCornerShape(8.dp))
                        .padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    items(liveTurns, key = { "${it.strategy}-${it.turnIndex}-${it.branchName}-${it.userMessage.hashCode()}" }) { turn ->
                        CompareTurnCard(turn)
                    }
                }
            }
            Column(
                modifier = Modifier
                    .weight(0.45f)
                    .fillMaxHeight()
                    .padding(start = 14.dp)
                    .verticalScroll(analyticsScroll),
            ) {
                ScenarioOutline()
                Spacer(modifier = Modifier.height(12.dp))
                result?.let { CompareAnalytics(it) }
            }
        }
    }
}

@Composable
private fun StrategySelector(selected: ContextStrategy, onSelect: (ContextStrategy) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ContextStrategy.entries.forEach { strategy ->
            val color = strategyColor(strategy)
            val isSelected = strategy == selected
            Box(
                modifier = Modifier
                    .background(
                        if (isSelected) color.copy(alpha = 0.18f) else Color.Transparent,
                        RoundedCornerShape(8.dp),
                    )
                    .border(
                        width = 1.dp,
                        color = if (isSelected) color else MaterialTheme.colors.onSurface.copy(alpha = 0.2f),
                        shape = RoundedCornerShape(8.dp),
                    )
                    .clickable { onSelect(strategy) }
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            ) {
                Text(
                    text = strategy.label,
                    fontSize = 12.sp,
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                    color = if (isSelected) color else MaterialTheme.colors.onSurface,
                )
            }
        }
    }
}

@Composable
private fun BranchingControls(agent: BranchingAgent, enabled: Boolean) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        OutlinedButton(
            onClick = { agent.createCheckpoint() },
            enabled = enabled && !agent.hasCheckpoint,
        ) { Text("Checkpoint") }
        OutlinedButton(
            onClick = {
                val name = "ветка ${agent.branchList.size}"
                agent.createBranch(name)
            },
            enabled = enabled && agent.hasCheckpoint,
        ) { Text("Новая ветка") }
        agent.branchList.forEach { branch ->
            val active = agent.activeBranch?.id == branch.id
            OutlinedButton(
                onClick = { agent.switchBranch(branch.id) },
                enabled = enabled,
                modifier = Modifier.background(
                    if (active) strategyColor(ContextStrategy.BRANCHING).copy(alpha = 0.12f)
                    else Color.Transparent,
                    RoundedCornerShape(4.dp),
                ),
            ) {
                Text(branch.name, fontSize = 11.sp)
            }
        }
    }
}

@Composable
private fun FactsPanel(facts: Map<String, String>) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF1565C0).copy(alpha = 0.08f), RoundedCornerShape(8.dp))
            .padding(10.dp),
    ) {
        Text("Facts (${facts.size})", fontWeight = FontWeight.SemiBold, fontSize = 12.sp, color = Color(0xFF1565C0))
        facts.forEach { (k, v) ->
            Text("• $k: $v", fontSize = 11.sp, modifier = Modifier.padding(top = 2.dp))
        }
    }
}

@Composable
private fun StrategyInfoCard(strategy: ContextStrategy) {
    val color = strategyColor(strategy)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(color.copy(alpha = 0.08f), RoundedCornerShape(8.dp))
            .padding(12.dp),
    ) {
        Text(strategy.label, fontWeight = FontWeight.Bold, color = color)
        Text(strategy.description, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
        Spacer(modifier = Modifier.height(12.dp))
        when (strategy) {
            ContextStrategy.SLIDING_WINDOW -> Text(
                "Старые сообщения полностью отбрасываются. При длинном диалоге модель «забывает» начало.",
                fontSize = 12.sp,
            )
            ContextStrategy.FACTS -> Text(
                "После каждого сообщения LLM обновляет блок фактов. В запрос: facts + окно. Доп. расход на extraction.",
                fontSize = 12.sp,
            )
            ContextStrategy.BRANCHING -> Text(
                "До checkpoint — общий префикс. После — независимые ветки. Переключайтесь между ними.",
                fontSize = 12.sp,
            )
        }
    }
}

@Composable
private fun TurnBubble(turn: AgentTurn) {
    when (turn) {
        is AgentTurn.User -> Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
            Text(
                turn.content,
                modifier = Modifier
                    .widthIn(max = 480.dp)
                    .background(MaterialTheme.colors.primary.copy(alpha = 0.12f), RoundedCornerShape(10.dp))
                    .padding(10.dp),
                fontSize = 13.sp,
            )
        }
        is AgentTurn.Assistant -> Text(
            turn.content,
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colors.onSurface.copy(alpha = 0.05f), RoundedCornerShape(10.dp))
                .padding(10.dp),
            fontSize = 13.sp,
        )
        is AgentTurn.SystemNote -> Text(
            "↪ ${turn.content}",
            fontSize = 11.sp,
            color = MaterialTheme.colors.onSurface.copy(alpha = 0.55f),
        )
        is AgentTurn.Checkpoint -> Text(
            "⬦ checkpoint «${turn.label}» (ход ${turn.turnIndex})",
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            color = Color(0xFFE65100),
        )
    }
}

@Composable
private fun CompareTurnCard(turn: StrategyTurnResult) {
    val color = strategyColor(turn.strategy)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(color.copy(alpha = 0.07f), RoundedCornerShape(8.dp))
            .padding(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(turn.strategy.label, fontSize = 10.sp, fontWeight = FontWeight.Bold, color = color)
            turn.branchName?.let {
                Text(" · $it", fontSize = 10.sp, color = MaterialTheme.colors.onSurface.copy(alpha = 0.6f))
            }
            Spacer(modifier = Modifier.weight(1f))
            PhaseBadge(turn.phase)
        }
        Text(turn.userMessage, fontSize = 12.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 6.dp))
        Text(turn.reply, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
        Text(
            "prompt: ${turn.promptTokens} tok" +
                if (turn.extraTokens > 0) " + ${turn.extraTokens} extraction" else "",
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colors.onSurface.copy(alpha = 0.55f),
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}

@Composable
private fun ScenarioOutline() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colors.surface, RoundedCornerShape(8.dp))
            .padding(12.dp),
    ) {
        Text(
            "${CompareScenario.SCENARIO_TITLE} (${CompareScenario.linearMessages.size} ходов)",
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text("• ${CompareScenario.setupCount} — старт (название, бюджет, студия, гость)", fontSize = 11.sp)
        Text("• ${CompareScenario.discussCount} — обсуждение (вытесняет факты из окна)", fontSize = 11.sp)
        Text("• 3 — ветка «Промо» / «Контент»", fontSize = 11.sp)
        Text("• 6 — проверочных вопросов по ранним фактам", fontSize = 11.sp)
        Text("• Branching: checkpoint после обсуждения → 2 ветки", fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp))
    }
}

@Composable
private fun CompareAnalytics(result: CompareAllResult) {
    Text("Итоговое сравнение", style = MaterialTheme.typography.h6, fontWeight = FontWeight.Bold)
    Spacer(modifier = Modifier.height(10.dp))

    ComparisonTable(result)

    Spacer(modifier = Modifier.height(16.dp))
    Text("Проверка качества (verify)", fontWeight = FontWeight.SemiBold)
    Spacer(modifier = Modifier.height(8.dp))

    val slidingVerify = result.sliding.verifyTurns
    val factsVerify = result.facts.verifyTurns
    val maxVerify = maxOf(slidingVerify.size, factsVerify.size, 1)

    for (i in 0 until maxVerify.coerceAtMost(slidingVerify.size)) {
        val question = slidingVerify.getOrNull(i)?.userMessage ?: continue
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 6.dp)
                .background(MaterialTheme.colors.onSurface.copy(alpha = 0.04f), RoundedCornerShape(8.dp))
                .padding(10.dp),
        ) {
            Text(question, fontSize = 12.sp, fontWeight = FontWeight.Medium)
            VerifyReply("Sliding", slidingVerify.getOrNull(i)?.reply, strategyColor(ContextStrategy.SLIDING_WINDOW))
            VerifyReply("Facts", factsVerify.getOrNull(i)?.reply, strategyColor(ContextStrategy.FACTS))
        }
    }

    result.facts.facts?.let { facts ->
        Spacer(modifier = Modifier.height(16.dp))
        Text("Итоговые facts", fontWeight = FontWeight.SemiBold)
        FactsPanel(facts)
    }

    Spacer(modifier = Modifier.height(16.dp))
    Text("Выводы", fontWeight = FontWeight.SemiBold)
    ConclusionsCard(result)
}

@Composable
private fun ComparisonTable(result: CompareAllResult) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colors.primary.copy(alpha = 0.06f), RoundedCornerShape(8.dp))
            .padding(12.dp),
    ) {
        Row(modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp)) {
            Text("Стратегия", modifier = Modifier.weight(1.2f), fontSize = 11.sp, fontWeight = FontWeight.Bold)
            Text("Σ prompt", modifier = Modifier.weight(0.8f), fontSize = 11.sp, fontWeight = FontWeight.Bold)
            Text("USD", modifier = Modifier.weight(0.8f), fontSize = 11.sp, fontWeight = FontWeight.Bold)
        }
        result.strategies.forEach { run ->
            Row(modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                Text(
                    run.strategy.label,
                    modifier = Modifier.weight(1.2f),
                    fontSize = 11.sp,
                    color = strategyColor(run.strategy),
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    run.totalPromptTokens.toString(),
                    modifier = Modifier.weight(0.8f),
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                )
                Text(
                    formatUsd(run.totalCostUsd),
                    modifier = Modifier.weight(0.8f),
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                )
            }
        }
    }
}

@Composable
private fun VerifyReply(label: String, reply: String?, color: Color) {
    if (reply == null) return
    Column(modifier = Modifier.padding(top = 6.dp)) {
        Text(label, fontSize = 10.sp, fontWeight = FontWeight.SemiBold, color = color)
        Text(reply, fontSize = 11.sp)
    }
}

@Composable
private fun ConclusionsCard(result: CompareAllResult) {
    val sliding = result.sliding
    val facts = result.facts
    val minTokens = result.strategies.minByOrNull { it.totalPromptTokens }!!
    val conclusions = listOf(
        "Токены: минимум у «${minTokens.strategy.label}» (${minTokens.totalPromptTokens} prompt). " +
            "Facts тратит +${facts.totalExtraTokens} на extraction.",
        "Стабильность: после ${CompareScenario.discussCount} сообщений обсуждения Sliding Window " +
            "теряет факты старта (окно ${Config.windowSize()}). Facts сохраняет их в key-value блоке.",
        "Branching: две независимые ветки от checkpoint — удобно для «что если» без смешивания контекста.",
        "UX: Sliding — простейший; Facts — прозрачные факты; Branching — гибкость, но сложнее навигация.",
    )
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF2E7D32).copy(alpha = 0.08f), RoundedCornerShape(8.dp))
            .padding(12.dp),
    ) {
        conclusions.forEach { line ->
            Text("• $line", fontSize = 11.sp, modifier = Modifier.padding(vertical = 3.dp))
        }
    }
}

@Composable
private fun PhaseBadge(phase: ScenarioPhase) {
    val (bg, fg) = when (phase) {
        ScenarioPhase.SETUP -> Color(0xFF1565C0) to Color(0xFF1565C0)
        ScenarioPhase.DISCUSS -> Color(0xFF455A64) to Color(0xFF455A64)
        ScenarioPhase.BRANCH_A -> Color(0xFF6A1B9A) to Color(0xFF6A1B9A)
        ScenarioPhase.BRANCH_B -> Color(0xFF00838F) to Color(0xFF00838F)
        ScenarioPhase.VERIFY -> Color(0xFF2E7D32) to Color(0xFF2E7D32)
    }
    Box(
        modifier = Modifier
            .background(bg.copy(alpha = 0.15f), RoundedCornerShape(4.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        Text(phase.label, fontSize = 9.sp, fontWeight = FontWeight.SemiBold, color = fg)
    }
}

private fun strategyColor(strategy: ContextStrategy): Color = when (strategy) {
    ContextStrategy.SLIDING_WINDOW -> Color(0xFF1565C0)
    ContextStrategy.FACTS -> Color(0xFF6A1B9A)
    ContextStrategy.BRANCHING -> Color(0xFFE65100)
}

private fun formatUsd(value: Double): String = String.format(Locale.US, "$%.5f", value)
