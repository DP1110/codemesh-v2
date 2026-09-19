package com.codemesh.app.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.codemesh.app.data.Message
import com.codemesh.app.viewmodel.ChatUiState
import com.codemesh.app.viewmodel.ChatViewModel
import com.codemesh.app.viewmodel.ConnectionState
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    viewModel: ChatViewModel,
    onOpenSidebar: () -> Unit,
    onOpenSettings: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    val colors = AppTheme.colors
    val isClaude = AppTheme.isClaude

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.base)
    ) {
        // ─── Top Bar ────────────────────────────────────────
        TopBar(
            uiState = uiState,
            isClaude = isClaude,
            colors = colors,
            onOpenSidebar = onOpenSidebar,
            onOpenSettings = onOpenSettings
        )

        // ─── Content ────────────────────────────────────────
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
            if (uiState.messages.isEmpty() && !uiState.isStreaming) {
                EmptyState(isClaude = isClaude, colors = colors)
            } else {
                MessageList(
                    messages = uiState.messages,
                    isStreaming = uiState.isStreaming,
                    streamingContent = uiState.streamingContent,
                    isClaude = isClaude,
                    colors = colors
                )
            }
        }

        // ─── Input Bar ──────────────────────────────────────
        InputBar(
            isStreaming = uiState.isStreaming,
            colors = colors,
            onSend = { text -> viewModel.sendMessage(text) },
            onStop = { viewModel.stopGeneration() }
        )
    }
}

// ═══════════════════════════════════════════════════════════════
// Top Bar
// ═══════════════════════════════════════════════════════════════
@Composable
private fun TopBar(
    uiState: ChatUiState,
    isClaude: Boolean,
    colors: CodeMeshPalette,
    onOpenSidebar: () -> Unit,
    onOpenSettings: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.surface)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Hamburger
        IconButton(onClick = onOpenSidebar) {
            Icon(Icons.Filled.Menu, "Menu", tint = colors.text)
        }

        // Asterisk (Claude only)
        if (isClaude) {
            Text(
                text = "✳",
                color = colors.accent,
                fontSize = 18.sp,
                modifier = Modifier.padding(end = 4.dp)
            )
        }

        // Title
        Text(
            text = "CodeMesh",
            color = colors.text,
            fontWeight = FontWeight.Bold,
            fontSize = 18.sp
        )

        Spacer(Modifier.width(8.dp))

        // Model pill
        if (uiState.selectedModel.isNotEmpty()) {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = colors.base,
                modifier = Modifier.padding(horizontal = 4.dp)
            ) {
                Text(
                    text = uiState.selectedModel,
                    color = colors.muted,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                )
            }
        }

        // Status dot
        StatusDot(state = uiState.connectionState, colors = colors)

        Spacer(Modifier.weight(1f))

        // Settings gear
        IconButton(onClick = onOpenSettings) {
            Icon(Icons.Filled.Settings, "Settings", tint = colors.muted)
        }
    }
}

// ═══════════════════════════════════════════════════════════════
// Status Dot (green pulse / red blink / amber blink)
// ═══════════════════════════════════════════════════════════════
@Composable
private fun StatusDot(state: ConnectionState, colors: CodeMeshPalette) {
    val infiniteTransition = rememberInfiniteTransition(label = "statusDot")
    val alpha by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 0.3f,
        animationSpec = infiniteRepeatable(
            animation = tween(
                durationMillis = when (state) {
                    ConnectionState.ONLINE -> 2000
                    ConnectionState.OFFLINE -> 1500
                    ConnectionState.UNKNOWN -> 800
                },
                easing = FastOutSlowInEasing
            ),
            repeatMode = RepeatMode.Reverse
        ),
        label = "dotAlpha"
    )

    val dotColor = when (state) {
        ConnectionState.ONLINE -> Color(0xFF4CAF50)
        ConnectionState.OFFLINE -> Color(0xFFE57373)
        ConnectionState.UNKNOWN -> Color(0xFFFFA726)
    }

    Box(
        modifier = Modifier
            .padding(horizontal = 6.dp)
            .size(8.dp)
            .clip(CircleShape)
            .background(dotColor.copy(alpha = alpha))
    )
}

// ═══════════════════════════════════════════════════════════════
// Empty State (New Chat)
// ═══════════════════════════════════════════════════════════════
@Composable
private fun EmptyState(isClaude: Boolean, colors: CodeMeshPalette) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        // Rotating asterisk (Claude only)
        if (isClaude) {
            val rotation by rememberInfiniteTransition(label = "asterisk").animateFloat(
                initialValue = 0f,
                targetValue = 360f,
                animationSpec = infiniteRepeatable(
                    animation = tween(12000, easing = LinearEasing)
                ),
                label = "asteriskRotation"
            )
            Text(
                text = "✳",
                color = colors.accent,
                fontSize = 36.sp,
                modifier = Modifier.rotate(rotation)
            )
            Spacer(Modifier.height(12.dp))
        }

        // Title
        Text(
            text = "CodeMesh",
            color = colors.text,
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold
        )

        // Tagline with delayed fade-in
        var tagVisible by remember { mutableStateOf(false) }
        LaunchedEffect(Unit) {
            kotlinx.coroutines.delay(250)
            tagVisible = true
        }
        AnimatedVisibility(
            visible = tagVisible,
            enter = fadeIn(animationSpec = tween(400))
        ) {
            Text(
                text = "Ask anything about code…",
                color = colors.muted,
                fontSize = 16.sp,
                modifier = Modifier.padding(top = 8.dp)
            )
        }
    }
}

// ═══════════════════════════════════════════════════════════════
// Message List
// ═══════════════════════════════════════════════════════════════
@Composable
private fun MessageList(
    messages: List<Message>,
    isStreaming: Boolean,
    streamingContent: String,
    isClaude: Boolean,
    colors: CodeMeshPalette
) {
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    // Auto-scroll to bottom when streaming
    LaunchedEffect(messages.size, streamingContent) {
        if (messages.isNotEmpty() || streamingContent.isNotEmpty()) {
            val targetIndex = messages.size + (if (isStreaming) 1 else 0)
            if (targetIndex > 0) {
                scope.launch {
                    listState.animateScrollToItem(targetIndex - 1)
                }
            }
        }
    }

    LazyColumn(
        state = listState,
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(vertical = 16.dp)
    ) {
        items(messages.filter { it.role != "system" }) { message ->
            MessageBubble(
                message = message,
                isClaude = isClaude,
                colors = colors
            )
        }

        // Streaming bubble
        if (isStreaming) {
            item {
                if (streamingContent.isEmpty()) {
                    ThinkingIndicator(isClaude = isClaude, colors = colors)
                } else {
                    StreamingBubble(
                        content = streamingContent,
                        isClaude = isClaude,
                        colors = colors
                    )
                }
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════
// Message Bubble
// ═══════════════════════════════════════════════════════════════
@Composable
private fun MessageBubble(
    message: Message,
    isClaude: Boolean,
    colors: CodeMeshPalette
) {
    val isUser = message.role == "user"

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = if (isUser) Alignment.End else Alignment.Start
    ) {
        if (isUser) {
            // User: surface pill, right-aligned
            Surface(
                shape = RoundedCornerShape(18.dp),
                color = colors.surface
            ) {
                Text(
                    text = message.content,
                    color = colors.text,
                    fontSize = 15.sp,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
                )
            }
        } else {
            // AI: plain text, left-aligned
            // Use serif in Claude style
            val fontFamily = if (isClaude) FontFamily.Serif else FontFamily.Default
            MarkdownRenderer(
                markdown = message.content,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

// ═══════════════════════════════════════════════════════════════
// Streaming Bubble (with blinking cursor)
// ═══════════════════════════════════════════════════════════════
@Composable
private fun StreamingBubble(
    content: String,
    isClaude: Boolean,
    colors: CodeMeshPalette
) {
    val cursorAlpha by rememberInfiniteTransition(label = "cursor").animateFloat(
        initialValue = 1f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(500),
            repeatMode = RepeatMode.Reverse
        ),
        label = "cursorBlink"
    )

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.Start
    ) {
        Text(
            text = content + "▌",
            color = colors.text.copy(alpha = if (content.isEmpty()) cursorAlpha else 1f),
            fontSize = 15.sp,
            fontFamily = if (isClaude) FontFamily.Serif else FontFamily.Default,
            modifier = Modifier.padding(end = 32.dp)
        )
    }
}

// ═══════════════════════════════════════════════════════════════
// Thinking Indicator
// ═══════════════════════════════════════════════════════════════
@Composable
private fun ThinkingIndicator(isClaude: Boolean, colors: CodeMeshPalette) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(vertical = 8.dp)
    ) {
        if (isClaude) {
            // Claude: rotating asterisk
            val rotation by rememberInfiniteTransition(label = "thinkAst").animateFloat(
                initialValue = 0f,
                targetValue = 360f,
                animationSpec = infiniteRepeatable(
                    animation = tween(3000, easing = LinearEasing)
                ),
                label = "thinkRotation"
            )

            // Pulsing dot
            val dotAlpha by rememberInfiniteTransition(label = "thinkDot").animateFloat(
                initialValue = 1f,
                targetValue = 0.3f,
                animationSpec = infiniteRepeatable(
                    animation = tween(600),
                    repeatMode = RepeatMode.Reverse
                ),
                label = "dotPulse"
            )
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(colors.muted.copy(alpha = dotAlpha))
            )
            Spacer(Modifier.width(8.dp))

            Text(
                text = "✳",
                color = colors.accent,
                fontSize = 16.sp,
                modifier = Modifier.rotate(rotation)
            )
        } else {
            // ChatGPT: pulsing dot
            val dotAlpha by rememberInfiniteTransition(label = "thinkDotGpt").animateFloat(
                initialValue = 1f,
                targetValue = 0.2f,
                animationSpec = infiniteRepeatable(
                    animation = tween(800),
                    repeatMode = RepeatMode.Reverse
                ),
                label = "dotPulseGpt"
            )
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(colors.muted.copy(alpha = dotAlpha))
            )
            Spacer(Modifier.width(8.dp))

            // Shimmer asterisk
            val shimAlpha by rememberInfiniteTransition(label = "shimmer").animateFloat(
                initialValue = 0.4f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(1200),
                    repeatMode = RepeatMode.Reverse
                ),
                label = "shimmerAlpha"
            )
            Text(
                text = "✳",
                color = colors.accent.copy(alpha = shimAlpha),
                fontSize = 16.sp
            )
        }

        Spacer(Modifier.width(8.dp))

        // Shimmering "Thinking…"
        val textAlpha by rememberInfiniteTransition(label = "thinkText").animateFloat(
            initialValue = 0.5f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(1000),
                repeatMode = RepeatMode.Reverse
            ),
            label = "thinkTextAlpha"
        )
        Text(
            text = "Thinking…",
            color = colors.muted.copy(alpha = textAlpha),
            fontSize = 14.sp
        )
    }
}

// ═══════════════════════════════════════════════════════════════
// Input Bar
// ═══════════════════════════════════════════════════════════════
@Composable
private fun InputBar(
    isStreaming: Boolean,
    colors: CodeMeshPalette,
    onSend: (String) -> Unit,
    onStop: () -> Unit
) {
    var text by remember { mutableStateOf(TextFieldValue("")) }
    val hasText = text.text.isNotBlank()

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.base)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Input field
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = colors.surface,
            modifier = Modifier.weight(1f)
        ) {
            BasicTextField(
                value = text,
                onValueChange = { text = it },
                enabled = !isStreaming,
                textStyle = TextStyle(
                    color = colors.text,
                    fontSize = 15.sp
                ),
                cursorBrush = SolidColor(colors.accent),
                modifier = Modifier
                    .padding(horizontal = 16.dp, vertical = 12.dp)
                    .fillMaxWidth(),
                decorationBox = { innerTextField ->
                    if (text.text.isEmpty()) {
                        Text(
                            text = "Ask anything…",
                            color = colors.muted,
                            fontSize = 15.sp
                        )
                    }
                    innerTextField()
                }
            )
        }

        Spacer(Modifier.width(8.dp))

        // Send / Stop button
        val buttonScale by animateFloatAsState(
            targetValue = 1f,
            animationSpec = spring(stiffness = Spring.StiffnessHigh),
            label = "btnScale"
        )

        if (isStreaming) {
            // Stop button
            IconButton(
                onClick = onStop,
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(colors.error)
            ) {
                Icon(
                    Icons.Filled.Stop,
                    contentDescription = "Stop",
                    tint = Color.White,
                    modifier = Modifier.size(20.dp)
                )
            }
        } else {
            // Send button — grey when empty, accent when has text
            IconButton(
                onClick = {
                    if (hasText) {
                        onSend(text.text.trim())
                        text = TextFieldValue("")
                    }
                },
                enabled = hasText,
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(
                        if (hasText) colors.accent else colors.surface
                    )
                    .scale(buttonScale)
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.Send,
                    contentDescription = "Send",
                    tint = if (hasText) colors.buttonText else colors.muted,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}
