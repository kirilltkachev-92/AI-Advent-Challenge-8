import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Button
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.material.LinearProgressIndicator
import androidx.compose.material.MaterialTheme
import androidx.compose.material.OutlinedButton
import androidx.compose.material.OutlinedTextField
import androidx.compose.material.RadioButton
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.material.darkColors
import androidx.compose.material.lightColors
import androidx.compose.runtime.Composable
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.math.min

private const val SCROLL_STEP_MS = 220L
private const val SCROLL_FOLLOW_STEP_MS = 150L
private const val SCROLL_MAX_STEPS = 28
private const val SCROLL_FOLLOW_MAX_STEPS = 8
private const val SCROLL_LAYOUT_DELAY_MS = 80L
private const val TYPEWRITER_PROMPT_CHUNK_MS = 40L
private const val TYPEWRITER_PROMPT_CHARS = 2
private const val TYPEWRITER_ANSWER_CHUNK_MS = 68L
private const val TYPEWRITER_ANSWER_CHARS = 2

private enum class RunMode {
    Single,
    SequentialAll,
}

private enum class DisplaySpeed {
    Slow,
    Fast,
}

private enum class MessageKind { Prompt, Answer, Comparison, Status }

private data class UiBlock(
    val kind: MessageKind,
    val title: String,
    val content: String,
)

fun main() {
    Config.loadDotEnv()
    val client = DeepSeekClient(apiKey = Config.apiKey())
    val solver = TemperatureSolver(client)

    application {
        val windowState = rememberWindowState(width = 900.dp, height = 900.dp)

        Window(
            onCloseRequest = ::exitApplication,
            state = windowState,
            title = "DeepSeek — День 4: температура",
        ) {
            MaterialTheme(
                colors = if (MaterialTheme.colors.isLight) lightColors() else darkColors(),
            ) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    TemperatureScreen(solver = solver)
                }
            }
        }
    }
}

@Composable
private fun TemperatureScreen(solver: TemperatureSolver) {
    val scope = rememberCoroutineScope()
    val blocks = remember { mutableStateListOf<UiBlock>() }
    var promptText by remember { mutableStateOf(Config.SAMPLE_PROMPT) }
    var selectedTemperature by remember { mutableStateOf(Config.TEMPERATURE_STEPS.first()) }
    var runMode by remember { mutableStateOf(RunMode.SequentialAll) }
    var displaySpeed by remember { mutableStateOf(DisplaySpeed.Slow) }
    var isLoading by remember { mutableStateOf(false) }
    var progressLabel by remember { mutableStateOf<String?>(null) }
    var errorText by remember { mutableStateOf<String?>(null) }
    val listState = rememberLazyListState()
    val scrollMutex = remember { Mutex() }

    fun typewriterPacing(kind: MessageKind, length: Int): Pair<Int, Long> {
        val isPrompt = kind == MessageKind.Prompt
        return when {
            isPrompt && length > 2000 -> 8 to 28L
            isPrompt && length > 600 -> 4 to 34L
            isPrompt -> TYPEWRITER_PROMPT_CHARS to TYPEWRITER_PROMPT_CHUNK_MS
            !isPrompt && length > 2000 -> 4 to 52L
            !isPrompt && length > 600 -> 3 to 60L
            else -> TYPEWRITER_ANSWER_CHARS to TYPEWRITER_ANSWER_CHUNK_MS
        }
    }

    suspend fun scrollToLatest(extraItems: Int = 0, smooth: Boolean = false) {
        val targetIndex = blocks.size - 1 + extraItems
        if (targetIndex < 0) return

        scrollMutex.withLock {
            if (displaySpeed == DisplaySpeed.Fast) {
                runCatching { listState.scrollToItem(targetIndex) }
                return@withLock
            }
            if (smooth) delay(SCROLL_LAYOUT_DELAY_MS)
            val start = listState.firstVisibleItemIndex.coerceAtMost(targetIndex)
            val distance = targetIndex - start
            if (distance <= 0) {
                runCatching { listState.scrollToItem(targetIndex) }
                return@withLock
            }
            val maxSteps = if (smooth) SCROLL_MAX_STEPS else SCROLL_FOLLOW_MAX_STEPS
            val stepDelay = if (smooth) SCROLL_STEP_MS else SCROLL_FOLLOW_STEP_MS
            val steps = distance.coerceAtMost(maxSteps).coerceAtLeast(1)
            for (step in 1..steps) {
                val index = start + (distance * step / steps)
                runCatching { listState.scrollToItem(index) }
                delay(stepDelay)
            }
            runCatching { listState.scrollToItem(targetIndex) }
        }
    }

    suspend fun appendBlockInstant(
        kind: MessageKind,
        title: String,
        content: String,
        smoothScroll: Boolean = false,
    ) {
        blocks += UiBlock(kind, title, content)
        scrollToLatest(extraItems = if (isLoading) 1 else 0, smooth = smoothScroll)
    }

    suspend fun appendBlockAnimated(kind: MessageKind, title: String, fullText: String) {
        if (displaySpeed == DisplaySpeed.Fast) {
            appendBlockInstant(kind, title, fullText)
            return
        }
        val isAnswer = kind == MessageKind.Answer || kind == MessageKind.Comparison
        blocks += UiBlock(kind, title, "")
        val blockIndex = blocks.lastIndex
        scrollToLatest(extraItems = if (isLoading) 1 else 0, smooth = false)

        val (charsPerStep, stepDelay) = typewriterPacing(kind, fullText.length)
        var shown = 0
        var tick = 0
        while (shown < fullText.length) {
            shown = min(shown + charsPerStep, fullText.length)
            blocks[blockIndex] = blocks[blockIndex].copy(content = fullText.substring(0, shown))
            tick++
            val scrollEvery = if (isAnswer) 12 else 20
            if (tick % scrollEvery == 0 || shown == fullText.length) {
                scrollToLatest(extraItems = if (isLoading) 1 else 0, smooth = false)
            }
            delay(stepDelay)
        }
    }

    suspend fun showResult(prefix: String, result: TemperatureResult) {
        val exchange = result.exchange
        appendBlockInstant(
            MessageKind.Prompt,
            "$prefix · Промпт · ${exchange.label}",
            formatPrompt(exchange.messages) + "\n\n[temperature = ${exchange.temperature}]",
        )
        appendBlockAnimated(
            MessageKind.Answer,
            "$prefix · ${exchange.label}",
            exchange.response,
        )
    }

    fun clearResults() {
        blocks.clear()
        errorText = null
        progressLabel = null
    }

    fun runSingle() {
        val prompt = promptText.trim()
        if (prompt.isEmpty() || isLoading) return

        errorText = null
        isLoading = true
        progressLabel = temperatureLabel(selectedTemperature)

        scope.launch {
            try {
                appendBlockInstant(
                    MessageKind.Prompt,
                    "Запрос",
                    prompt,
                    smoothScroll = displaySpeed == DisplaySpeed.Slow,
                )
                val result = withContext(Dispatchers.IO) {
                    solver.ask(prompt, selectedTemperature)
                }
                showResult(temperatureLabel(selectedTemperature), result)
            } catch (e: Exception) {
                errorText = e.message ?: "Ошибка запроса"
            } finally {
                isLoading = false
                progressLabel = null
            }
        }
    }

    fun runSequentialAll() {
        val prompt = promptText.trim()
        if (prompt.isEmpty() || isLoading) return

        clearResults()
        isLoading = true

        scope.launch {
            try {
                appendBlockInstant(
                    MessageKind.Prompt,
                    "Запрос",
                    prompt,
                    smoothScroll = displaySpeed == DisplaySpeed.Slow,
                )
                val results = mutableListOf<TemperatureResult>()
                for (temperature in Config.TEMPERATURE_STEPS) {
                    progressLabel = "Выполняется: ${temperatureLabel(temperature)}…"
                    appendBlockInstant(
                        MessageKind.Status,
                        "Статус",
                        "Запрос к API: ${temperatureLabel(temperature)} (${temperatureDescription(temperature)})",
                    )

                    val result = withContext(Dispatchers.IO) {
                        solver.ask(prompt, temperature)
                    }
                    results += result
                    showResult(temperatureLabel(temperature), result)
                }

                progressLabel = "Сравнение ответов…"
                appendBlockInstant(MessageKind.Status, "Статус", "Формируем сравнительный анализ…")

                val comparison = withContext(Dispatchers.IO) {
                    solver.compareAll(prompt, results)
                }
                val comparisonPrompt = formatPrompt(comparison.exchange.messages) +
                    "\n\n[temperature = ${comparison.exchange.temperature}]"
                appendBlockInstant(
                    MessageKind.Prompt,
                    "Сравнение · Промпт · ${comparison.exchange.label}",
                    comparisonPrompt,
                )
                appendBlockAnimated(MessageKind.Comparison, "Сравнение · Выводы", comparison.content)
            } catch (e: Exception) {
                errorText = e.message ?: "Ошибка запроса"
            } finally {
                isLoading = false
                progressLabel = null
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            Text(
                text = "День 4 — температура",
                style = MaterialTheme.typography.h6,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Один запрос с temperature от 0 до 2 (шаг 0.3) — сравнение по точности, креативности и разнообразию.",
                style = MaterialTheme.typography.caption,
                color = MaterialTheme.colors.onSurface.copy(alpha = 0.7f),
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Скорость вывода",
                style = MaterialTheme.typography.subtitle2,
                fontWeight = FontWeight.Medium,
            )
            OptionRow(
                selected = displaySpeed,
                target = DisplaySpeed.Slow,
                label = "Медленно — печать ответов",
                enabled = !isLoading,
                onSelect = { displaySpeed = it },
            )
            OptionRow(
                selected = displaySpeed,
                target = DisplaySpeed.Fast,
                label = "Быстро — ответы сразу",
                enabled = !isLoading,
                onSelect = { displaySpeed = it },
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Режим запуска",
                style = MaterialTheme.typography.subtitle2,
                fontWeight = FontWeight.Medium,
            )
            OptionRow(
                selected = runMode,
                target = RunMode.SequentialAll,
                label = "Последовательно все температуры (0…2, шаг 0.3) + сравнение",
                enabled = !isLoading,
                onSelect = { runMode = it },
            )
            OptionRow(
                selected = runMode,
                target = RunMode.Single,
                label = "Одна выбранная температура",
                enabled = !isLoading,
                onSelect = { runMode = it },
            )

            if (runMode == RunMode.Single) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Температура",
                    style = MaterialTheme.typography.subtitle2,
                    fontWeight = FontWeight.Medium,
                )
                Config.TEMPERATURE_STEPS.forEach { temperature ->
                    OptionRow(
                        selected = selectedTemperature,
                        target = temperature,
                        label = "${temperatureLabel(temperature)} — ${temperatureDescription(temperature)}",
                        enabled = !isLoading,
                        onSelect = { selectedTemperature = it },
                    )
                }
            } else {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Будут выполнены по порядку: ${
                        Config.TEMPERATURE_STEPS.joinToString { temperatureLabel(it) }
                    }",
                    style = MaterialTheme.typography.caption,
                    color = MaterialTheme.colors.onSurface.copy(alpha = 0.7f),
                )
            }

            Spacer(modifier = Modifier.height(8.dp))
            OutlinedButton(onClick = { clearResults() }, enabled = !isLoading) {
                Text("Очистить результаты")
            }
        }

        if (isLoading) {
            Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                progressLabel?.let { label ->
                    Text(
                        text = label,
                        modifier = Modifier.padding(top = 4.dp),
                        style = MaterialTheme.typography.caption,
                    )
                }
            }
        }

        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 12.dp),
            state = listState,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (blocks.isEmpty() && !isLoading) {
                item {
                    Text(
                        text = "Введите запрос. В режиме «Последовательно» приложение выполнит его " +
                            "с temperature от 0 до 2 (шаг 0.3), затем сравнит ответы и сформулирует выводы.",
                        modifier = Modifier.padding(8.dp),
                        color = MaterialTheme.colors.onSurface.copy(alpha = 0.6f),
                    )
                }
            }
            itemsIndexed(blocks, key = { index, _ -> index }) { _, block ->
                ResultBlock(block)
            }
            if (isLoading && blocks.isNotEmpty()) {
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.Start,
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.height(24.dp).padding(start = 8.dp),
                            strokeWidth = 2.dp,
                        )
                    }
                }
            }
        }

        errorText?.let { err ->
            Text(
                text = err,
                color = MaterialTheme.colors.error,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                style = MaterialTheme.typography.caption,
            )
        }

        OutlinedTextField(
            value = promptText,
            onValueChange = { promptText = it },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp)
                .heightIn(min = 80.dp, max = 140.dp),
            label = { Text("Запрос") },
            enabled = !isLoading,
            maxLines = 6,
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(
                onClick = {
                    if (runMode == RunMode.SequentialAll) runSequentialAll() else runSingle()
                },
                enabled = !isLoading && promptText.isNotBlank(),
                modifier = Modifier.weight(1f),
            ) {
                Text(
                    if (runMode == RunMode.SequentialAll) {
                        "Запустить все последовательно"
                    } else {
                        "Отправить (${temperatureLabel(selectedTemperature)})"
                    },
                )
            }
        }
    }
}

@Composable
private fun <T> OptionRow(
    selected: T,
    target: T,
    label: String,
    enabled: Boolean,
    onSelect: (T) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(
            selected = selected == target,
            onClick = { onSelect(target) },
            enabled = enabled,
        )
        Text(text = label, style = MaterialTheme.typography.body2)
    }
}

@Composable
private fun ResultBlock(block: UiBlock) {
    val (bg, accent) = when (block.kind) {
        MessageKind.Prompt -> Color(0xFF455A64) to Color.White
        MessageKind.Answer -> Color(0xFF2E7D32) to Color.White
        MessageKind.Comparison -> Color(0xFF6A1B9A) to Color.White
        MessageKind.Status -> MaterialTheme.colors.surface to MaterialTheme.colors.onSurface.copy(0.7f)
    }

    Box(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    bg.copy(alpha = if (block.kind == MessageKind.Status) 0.15f else 0.92f),
                    RoundedCornerShape(12.dp),
                )
                .padding(horizontal = 14.dp, vertical = 10.dp),
        ) {
            Text(
                text = block.title,
                color = accent,
                fontWeight = FontWeight.SemiBold,
                style = MaterialTheme.typography.subtitle2,
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = block.content,
                color = if (block.kind == MessageKind.Status) {
                    MaterialTheme.colors.onSurface
                } else {
                    accent
                },
                style = MaterialTheme.typography.body1,
                fontFamily = if (block.kind == MessageKind.Prompt) FontFamily.Monospace else FontFamily.Default,
                fontSize = if (block.kind == MessageKind.Prompt) 12.sp else MaterialTheme.typography.body1.fontSize,
            )
        }
    }
}
