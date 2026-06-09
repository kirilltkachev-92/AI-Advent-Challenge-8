import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Button
import androidx.compose.material.CircularProgressIndicator
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

fun main() {
    Config.loadDotEnv()
    val agent = ChatAgent(DeepSeekClient(apiKey = Config.apiKey()))

    application {
        val windowState = rememberWindowState(width = 720.dp, height = 800.dp)

        Window(
            onCloseRequest = ::exitApplication,
            state = windowState,
            title = "День 7: сохранение контекста",
        ) {
            MaterialTheme(
                colors = if (MaterialTheme.colors.isLight) lightColors() else darkColors(),
            ) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    ChatScreen(agent = agent)
                }
            }
        }
    }
}

@Composable
private fun ChatScreen(agent: ChatAgent) {
    val scope = rememberCoroutineScope()
    val turns = remember { mutableStateListOf<AgentTurn>().apply { addAll(agent.turns) } }
    var inputText by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }
    var errorText by remember { mutableStateOf<String?>(null) }
    var lastLatencyMs by remember { mutableStateOf<Long?>(null) }
    val listState = rememberLazyListState()

    LaunchedEffect(turns.size, isLoading) {
        if (turns.isNotEmpty() || isLoading) {
            val target = turns.size + if (isLoading) 1 else 0
            listState.animateScrollToItem((target - 1).coerceAtLeast(0))
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
    ) {
        Text(
            text = "Чат с агентом",
            style = MaterialTheme.typography.h5,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = "История сохраняется в ${Config.HISTORY_FILE_NAME} — после перезапуска диалог продолжается.",
            style = MaterialTheme.typography.caption,
            color = MaterialTheme.colors.onSurface.copy(alpha = 0.7f),
            modifier = Modifier.padding(top = 4.dp, bottom = 12.dp),
        )

        if (agent.restoredMessageCount > 0 && turns.isNotEmpty()) {
            Text(
                text = "Восстановлено ${agent.restoredMessageCount} сообщений из прошлой сессии.",
                style = MaterialTheme.typography.caption,
                color = MaterialTheme.colors.primary,
                modifier = Modifier.padding(bottom = 8.dp),
            )
        }

        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(
                    MaterialTheme.colors.surface,
                    RoundedCornerShape(8.dp),
                )
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (turns.isEmpty() && !isLoading) {
                item {
                    Text(
                        text = "Напишите сообщение, чтобы начать диалог.",
                        color = MaterialTheme.colors.onSurface.copy(alpha = 0.5f),
                        modifier = Modifier.padding(8.dp),
                    )
                }
            }

            itemsIndexed(turns) { _, turn ->
                MessageBubble(turn = turn)
            }

            if (isLoading) {
                item {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        CircularProgressIndicator(modifier = Modifier.height(20.dp))
                        Text(
                            text = "Агент думает…",
                            color = MaterialTheme.colors.onSurface.copy(alpha = 0.7f),
                        )
                    }
                }
            }
        }

        lastLatencyMs?.let { ms ->
            Text(
                text = "Последний ответ: ${ms} мс",
                style = MaterialTheme.typography.caption,
                color = MaterialTheme.colors.onSurface.copy(alpha = 0.6f),
                modifier = Modifier.padding(top = 8.dp),
            )
        }

        errorText?.let { err ->
            Text(
                text = err,
                color = MaterialTheme.colors.error,
                modifier = Modifier.padding(top = 6.dp),
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

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
                    val text = inputText.trim()
                    if (text.isEmpty() || isLoading) return@Button

                    errorText = null
                    inputText = ""
                    turns += AgentTurn.User(text)
                    isLoading = true

                    scope.launch {
                        val result = withContext(Dispatchers.IO) {
                            agent.processUserMessage(text)
                        }
                        isLoading = false
                        when (result) {
                            is AgentResult.Success -> {
                                turns += AgentTurn.Assistant(result.assistantMessage)
                                lastLatencyMs = result.latencyMs
                            }
                            is AgentResult.Error -> {
                                turns.removeLast()
                                errorText = result.message
                            }
                        }
                    }
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
                errorText = null
                lastLatencyMs = null
                inputText = ""
            },
            enabled = !isLoading && (turns.isNotEmpty() || agent.messageCount > 0),
            modifier = Modifier.padding(top = 8.dp),
        ) {
            Text("Очистить историю")
        }
    }
}

@Composable
private fun MessageBubble(turn: AgentTurn) {
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
    }

    Box(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = align,
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 520.dp)
                .background(background, RoundedCornerShape(12.dp))
                .padding(horizontal = 14.dp, vertical = 10.dp),
        ) {
            Text(
                text = label,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colors.onSurface.copy(alpha = 0.6f),
            )
            Text(
                text = content,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}
