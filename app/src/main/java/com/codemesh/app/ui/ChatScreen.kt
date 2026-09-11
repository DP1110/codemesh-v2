package com.codemesh.app.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Badge
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.codemesh.app.data.Message
import com.codemesh.app.viewmodel.ChatViewModel
import com.codemesh.app.viewmodel.ConnectionState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    viewModel: ChatViewModel,
    onOpenSidebar: () -> Unit,
    onOpenSettings: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("CodeMesh", color = CodeMeshColors.Primary, fontSize = 20.sp)
                        Spacer(Modifier.width(8.dp))
                        Badge(
                            containerColor = CodeMeshColors.Warning,
                            contentColor = Color.White
                        ) {
                            Text(
                                uiState.selectedModel,
                                modifier = Modifier.padding(horizontal = 4.dp)
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        val dotColor = when (uiState.connectionState) {
                            ConnectionState.ONLINE -> CodeMeshColors.Success
                            ConnectionState.OFFLINE -> Color.Red
                            ConnectionState.UNKNOWN -> Color.Gray
                        }
                        Box(
                            modifier = Modifier
                                .size(10.dp)
                                .clip(CircleShape)
                                .background(dotColor)
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onOpenSidebar) {
                        Icon(Icons.Default.Menu, contentDescription = "Sidebar", tint = Color.White)
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.createNewSession() }) {
                        Icon(Icons.Default.Add, contentDescription = "New Chat", tint = Color.White)
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings", tint = Color.White)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = CodeMeshColors.Background
                )
            )
        },
        containerColor = CodeMeshColors.Background
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // Error banner
            uiState.error?.let { error ->
                Text(
                    text = "⚠ $error",
                    color = CodeMeshColors.Warning,
                    fontSize = 12.sp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0x33FF8800))
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }

            Box(modifier = Modifier.weight(1f)) {
                if (uiState.messages.isEmpty() && !uiState.isStreaming) {
                    // Empty state
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("CodeMesh", color = CodeMeshColors.Primary, fontSize = 28.sp)
                            Text(
                                "Ask anything about code...",
                                color = CodeMeshColors.MutedText,
                                fontSize = 14.sp,
                                modifier = Modifier.padding(top = 8.dp)
                            )
                        }
                    }
                } else {
                    MessageList(
                        messages = uiState.messages,
                        isStreaming = uiState.isStreaming,
                        streamingContent = uiState.streamingContent
                    )
                }
            }

            InputBar(
                onSend = { viewModel.sendMessage(it) },
                isStreaming = uiState.isStreaming,
                onStop = { viewModel.stopGeneration() }
            )
        }
    }
}

@Composable
fun MessageList(
    messages: List<Message>,
    isStreaming: Boolean,
    streamingContent: String
) {
    val listState = rememberLazyListState()

    // Auto-scroll to bottom
    LaunchedEffect(messages.size, streamingContent) {
        val totalItems = messages.size + (if (isStreaming) 1 else 0)
        if (totalItems > 0) {
            listState.animateScrollToItem(totalItems - 1)
        }
    }

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(vertical = 16.dp)
    ) {
        items(messages, key = { it.id }) { message ->
            if (message.role != "system") {
                MessageBubble(message = message)
            }
        }
        if (isStreaming) {
            item(key = "streaming") {
                MessageBubble(
                    message = Message(
                        sessionId = "",
                        role = "assistant",
                        content = streamingContent,
                        createdAt = System.currentTimeMillis()
                    ),
                    isStreaming = true
                )
            }
        }
    }
}

@Composable
fun MessageBubble(
    message: Message,
    isStreaming: Boolean = false
) {
    val isUser = message.role == "user"
    val align = if (isUser) Alignment.End else Alignment.Start
    val bgColor = if (isUser) CodeMeshColors.UserBubbleBg else CodeMeshColors.AiBubbleBg
    val borderColor = if (isUser) CodeMeshColors.UserBubbleBorder else CodeMeshColors.AiBubbleBorder
    val labelColor = if (isUser) CodeMeshColors.Primary else CodeMeshColors.Success
    val labelText = if (isUser) "You" else "LLM"

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        horizontalAlignment = align
    ) {
        Text(
            text = labelText,
            color = labelColor,
            fontSize = 12.sp,
            modifier = Modifier.padding(bottom = 4.dp)
        )
        Box(
            modifier = Modifier
                .fillMaxWidth(if (isUser) 0.85f else 0.95f)
                .background(bgColor, RoundedCornerShape(16.dp))
                .border(1.dp, borderColor, RoundedCornerShape(16.dp))
                .padding(16.dp)
        ) {
            if (isUser) {
                Text(text = message.content, color = Color.White, fontSize = 14.sp)
            } else {
                Row {
                    Column(modifier = Modifier.weight(1f, fill = false)) {
                        if (message.content.isNotEmpty()) {
                            MarkdownText(
                                markdown = message.content
                            )
                        }
                    }
                    if (isStreaming) {
                        val infiniteTransition = rememberInfiniteTransition(label = "cursor")
                        val cursorAlpha by infiniteTransition.animateFloat(
                            initialValue = 0f,
                            targetValue = 1f,
                            animationSpec = infiniteRepeatable(
                                animation = tween(530),
                                repeatMode = RepeatMode.Reverse
                            ),
                            label = "cursor_alpha"
                        )
                        Text(
                            text = "▌",
                            color = CodeMeshColors.Primary,
                            fontSize = 16.sp,
                            modifier = Modifier.alpha(cursorAlpha)
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun InputBar(
    onSend: (String) -> Unit,
    isStreaming: Boolean,
    onStop: () -> Unit
) {
    var text by remember { mutableStateOf("") }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(CodeMeshColors.Panel)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            modifier = Modifier.weight(1f),
            placeholder = { Text("Ask anything...", color = CodeMeshColors.MutedText) },
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = CodeMeshColors.Background,
                unfocusedContainerColor = CodeMeshColors.Background,
                focusedBorderColor = CodeMeshColors.Primary,
                unfocusedBorderColor = CodeMeshColors.PanelBorder,
                focusedTextColor = Color.White,
                unfocusedTextColor = Color.White,
                cursorColor = CodeMeshColors.Primary
            ),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(
                onSend = {
                    if (text.isNotBlank() && !isStreaming) {
                        onSend(text)
                        text = ""
                    }
                }
            ),
            maxLines = 4
        )
        Spacer(modifier = Modifier.width(8.dp))

        if (isStreaming) {
            IconButton(
                onClick = onStop,
                modifier = Modifier
                    .size(48.dp)
                    .background(CodeMeshColors.Warning, CircleShape)
            ) {
                Icon(Icons.Default.Stop, contentDescription = "Stop", tint = Color.White)
            }
        } else {
            val enabled = text.isNotBlank()
            IconButton(
                onClick = {
                    if (enabled) {
                        onSend(text)
                        text = ""
                    }
                },
                enabled = enabled,
                modifier = Modifier
                    .size(48.dp)
                    .background(
                        if (enabled) CodeMeshColors.Primary else CodeMeshColors.PanelBorder,
                        CircleShape
                    )
            ) {
                Icon(Icons.Default.Send, contentDescription = "Send", tint = Color.White)
            }
        }
    }
}
