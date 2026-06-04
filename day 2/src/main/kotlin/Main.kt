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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Button
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.material.MaterialTheme
import androidx.compose.material.OutlinedButton
import androidx.compose.material.OutlinedTextField
import androidx.compose.material.RadioButton
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
import kotlin.math.min

private enum class MessageRole { User, Assistant, SystemNote }

private data class UiMessage(
    val role: MessageRole,
    val content: String,
    val label: String? = null,
)

private const val COMPARE_SCROLL_STEP_MS = 45L
private const val COMPARE_TYPEWRITER_CHUNK_MS = 18L
private const val COMPARE_TYPEWRITER_CHARS = 4
private const val COMPARE_PAUSE_BETWEEN_MODES_MS = 350L

fun main() {
    Config.loadDotEnv()
    val client = DeepSeekClient(apiKey = Config.apiKey())

    application {
        val windowState = rememberWindowState(width = 820.dp, height = 860.dp)

        Window(
            onCloseRequest = ::exitApplication,
            state = windowState,
            title = "DeepSeek Chat — День 2",
        ) {
            MaterialTheme(
                colors = if (MaterialTheme.colors.isLight) lightColors() else darkColors(),
            ) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    ChatScreen(client = client)
                }
            }
        }
    }
}

@Composable
private fun ChatScreen(client: DeepSeekClient) {
    val scope = rememberCoroutineScope()
    val messages = remember { mutableStateListOf<UiMessage>() }
    var input by remember { mutableStateOf(Config.SAMPLE_COMPARE_PROMPT) }
    var mode by remember { mutableStateOf(ResponseControlMode.Unrestricted) }
    var isLoading by remember { mutableStateOf(false) }
    var errorText by remember { mutableStateOf<String?>(null) }
    var lastRequestJson by remember { mutableStateOf<String?>(null) }
    var showRequestPanel by remember { mutableStateOf(false) }
    var isComparing by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

    fun resetConversation() {
        messages.clear()
        errorText = null
        lastRequestJson = null
    }

    LaunchedEffect(messages.size, isLoading) {
        if (isComparing) return@LaunchedEffect
        val lastIndex = messages.size + if (isLoading) 1 else 0
        if (lastIndex > 0) {
            listState.animateScrollToItem(lastIndex - 1)
        }
    }

    fun appendResult(userText: String, result: ChatResult, userLabel: String) {
        messages += UiMessage(MessageRole.User, userText, label = userLabel)
        messages += UiMessage(
            MessageRole.Assistant,
            result.content,
            label = "Ответ · ${modeLabel(result.mode)}",
        )
        lastRequestJson = result.requestBodyJson
    }

    suspend fun slowScrollToEnd(extraItems: Int = 0) {
        val targetIndex = messages.size - 1 + extraItems
        if (targetIndex < 0) return
        delay(50)
        val start = listState.firstVisibleItemIndex
        if (targetIndex <= start) {
            listState.animateScrollToItem(targetIndex)
            return
        }
        val steps = ((targetIndex - start).coerceAtMost(12)).coerceAtLeast(1)
        for (step in 1..steps) {
            val index = start + ((targetIndex - start) * step / steps)
            listState.animateScrollToItem(index)
            delay(COMPARE_SCROLL_STEP_MS)
        }
        listState.animateScrollToItem(targetIndex)
    }

    suspend fun appendCompareResult(userText: String, result: ChatResult, userLabel: String) {
        messages += UiMessage(MessageRole.User, userText, label = userLabel)
        slowScrollToEnd(extraItems = 1)

        val assistantLabel = "Ответ · ${modeLabel(result.mode)}"
        messages += UiMessage(MessageRole.Assistant, content = "", label = assistantLabel)
        val messageIndex = messages.lastIndex
        lastRequestJson = result.requestBodyJson

        val fullText = result.content
        val charsPerStep = when {
            fullText.length > 2000 -> 48
            fullText.length > 600 -> 16
            else -> COMPARE_TYPEWRITER_CHARS
        }
        val stepDelay = when {
            fullText.length > 2000 -> 6L
            fullText.length > 600 -> 12L
            else -> COMPARE_TYPEWRITER_CHUNK_MS
        }
        var shown = 0
        while (shown < fullText.length) {
            shown = min(shown + charsPerStep, fullText.length)
            messages[messageIndex] = messages[messageIndex].copy(
                content = fullText.substring(0, shown),
            )
            slowScrollToEnd(extraItems = if (isLoading) 1 else 0)
            delay(stepDelay)
        }
        delay(COMPARE_PAUSE_BETWEEN_MODES_MS)
    }

    fun send() {
        val text = input.trim()
        if (text.isEmpty() || isLoading) return

        errorText = null
        isLoading = true

        scope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    val apiMessages = client.messagesForUserPrompt(text, mode)
                    client.chatWithDetails(apiMessages, mode)
                }
                appendResult(text, result, userLabel = modeLabel(mode))
                input = ""
            } catch (e: Exception) {
                errorText = e.message ?: "Ошибка запроса"
            } finally {
                isLoading = false
            }
        }
    }

    fun compare() {
        val text = input.trim()
        if (text.isEmpty() || isLoading) return

        errorText = null
        resetConversation()
        isLoading = true
        isComparing = true

        scope.launch {
            try {
                for (compareMode in ResponseControlMode.entries) {
                    val result = withContext(Dispatchers.IO) {
                        client.chatWithDetails(
                            client.messagesForUserPrompt(text, compareMode),
                            compareMode,
                        )
                    }
                    appendCompareResult(text, result, userLabel = "Сравнение · ${modeLabel(compareMode)}")
                }
                slowScrollToEnd()
                input = ""
            } catch (e: Exception) {
                errorText = e.message ?: "Ошибка запроса"
            } finally {
                isComparing = false
                isLoading = false
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            Text(
                text = "DeepSeek — День 2: формат ответа",
                style = MaterialTheme.typography.h6,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(modifier = Modifier.height(8.dp))
            ModeRadioRow(
                mode = mode,
                target = ResponseControlMode.Unrestricted,
                label = "Без ограничений",
                enabled = !isLoading,
                onSelect = { mode = it },
            )
            ModeRadioRow(
                mode = mode,
                target = ResponseControlMode.FormatDescription,
                label = "Формат ответа",
                enabled = !isLoading,
                onSelect = { mode = it },
            )
            ModeRadioRow(
                mode = mode,
                target = ResponseControlMode.LengthLimit,
                label = "Ограничение длины",
                enabled = !isLoading,
                onSelect = { mode = it },
            )
            ModeRadioRow(
                mode = mode,
                target = ResponseControlMode.StopCondition,
                label = "Условие завершения",
                enabled = !isLoading,
                onSelect = { mode = it },
            )
            Spacer(modifier = Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { resetConversation() }, enabled = !isLoading) {
                    Text("Очистить")
                }
                OutlinedButton(onClick = { showRequestPanel = !showRequestPanel }, enabled = lastRequestJson != null) {
                    Text(if (showRequestPanel) "Скрыть JSON" else "Показать JSON")
                }
            }
        }

        if (showRequestPanel && lastRequestJson != null) {
            Text(
                text = "Последний запрос к API:",
                modifier = Modifier.padding(horizontal = 16.dp),
                style = MaterialTheme.typography.caption,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = lastRequestJson!!,
                modifier = Modifier
                    .padding(horizontal = 16.dp, vertical = 4.dp)
                    .heightIn(max = 120.dp)
                    .verticalScroll(rememberScrollState())
                    .fillMaxWidth()
                    .background(
                        MaterialTheme.colors.onSurface.copy(alpha = 0.06f),
                        RoundedCornerShape(8.dp),
                    )
                    .padding(8.dp),
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
            )
        }

        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 12.dp),
            state = listState,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (messages.isEmpty() && !isLoading) {
                item {
                    Text(
                        text = "Введите вопрос и нажмите «Сравнить» — один запрос уйдёт во всех 4 режимах. " +
                            "Или выберите режим и «Отправить».",
                        modifier = Modifier.padding(8.dp),
                        color = MaterialTheme.colors.onSurface.copy(alpha = 0.6f),
                    )
                }
            }
            itemsIndexed(messages, key = { index, _ -> index }) { _, msg ->
                MessageBubble(message = msg)
            }
            if (isLoading) {
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

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(
                onClick = { compare() },
                enabled = !isLoading && input.isNotBlank(),
                modifier = Modifier.weight(1f),
            ) {
                Text("Сравнить")
            }
            Button(
                onClick = { send() },
                enabled = !isLoading && input.isNotBlank(),
                modifier = Modifier.weight(1f),
            ) {
                Text("Отправить")
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("Ваш вопрос…") },
                maxLines = 5,
                enabled = !isLoading,
                singleLine = false,
            )
        }
    }
}

@Composable
private fun ModeRadioRow(
    mode: ResponseControlMode,
    target: ResponseControlMode,
    label: String,
    enabled: Boolean,
    onSelect: (ResponseControlMode) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(
            selected = mode == target,
            onClick = { onSelect(target) },
            enabled = enabled,
        )
        Text(
            text = label,
            style = MaterialTheme.typography.body2,
        )
    }
}

@Composable
private fun MessageBubble(message: UiMessage) {
    val isUser = message.role == MessageRole.User
    val bubbleColor = when {
        isUser -> Color(0xFF1976D2)
        MaterialTheme.colors.isLight -> Color(0xFFE8E8E8)
        else -> Color(0xFF3A3A3A)
    }
    val textColor = if (isUser) Color.White else MaterialTheme.colors.onSurface
    val alignment = if (isUser) Alignment.CenterEnd else Alignment.CenterStart

    Box(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = alignment,
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 520.dp)
                .background(bubbleColor, RoundedCornerShape(16.dp))
                .padding(horizontal = 14.dp, vertical = 10.dp),
        ) {
            val header = message.label ?: when (message.role) {
                MessageRole.User -> "Вы"
                MessageRole.Assistant -> "DeepSeek"
                MessageRole.SystemNote -> "Система"
            }
            Text(
                text = header,
                style = MaterialTheme.typography.caption,
                color = textColor.copy(alpha = 0.85f),
                fontWeight = FontWeight.Medium,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = message.content,
                color = textColor,
                style = MaterialTheme.typography.body1,
            )
        }
    }
}
