package com.codemesh.app.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

@Composable
fun SetupScreen(
    onRetry: () -> Unit,
    onSkip: () -> Unit,
    isChecking: Boolean
) {
    var stepsVisible by remember { mutableStateOf(false) }
    var errorShake by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        stepsVisible = true
        delay(500)
        errorShake = true
    }

    val shakeOffset = remember { Animatable(0f) }
    LaunchedEffect(errorShake) {
        if (errorShake) {
            shakeOffset.animateTo(
                targetValue = 0f,
                animationSpec = keyframes {
                    durationMillis = 400
                    0f at 0
                    -15f at 100
                    15f at 200
                    -15f at 300
                    0f at 400
                }
            )
        }
    }
    val shakeTranslation = shakeOffset.value

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(AppTheme.colors.base)
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        WifiRippleIcon()
        
        Spacer(modifier = Modifier.height(32.dp))
        
        Text(
            text = "Connect to LLM-Bridge",
            color = AppTheme.colors.text,
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold
        )
        
        Spacer(modifier = Modifier.height(32.dp))
        
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            val steps = listOf(
                "Power on ESP8266 bridge",
                "Join Wi-Fi \"LLM-Bridge\"",
                "Start Ollama on your laptop",
                "Join the same Wi-Fi on laptop"
            )
            
            steps.forEachIndexed { index, step ->
                AnimatedVisibility(
                    visible = stepsVisible,
                    enter = fadeIn(animationSpec = tween(300, delayMillis = index * 100)) +
                            slideInHorizontally(
                                animationSpec = tween(300, delayMillis = index * 100),
                                initialOffsetX = { -50 }
                            )
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Box(
                            modifier = Modifier
                                .size(24.dp)
                                .background(AppTheme.colors.surface, CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "${index + 1}",
                                color = AppTheme.colors.text,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Spacer(modifier = Modifier.width(16.dp))
                        Text(
                            text = step,
                            color = AppTheme.colors.muted,
                            fontSize = 16.sp
                        )
                    }
                }
            }
        }
        
        Spacer(modifier = Modifier.height(48.dp))
        
        Text(
            text = "Could not reach the bridge at 192.168.4.1",
            color = AppTheme.colors.error,
            fontSize = 14.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.graphicsLayer {
                translationX = shakeTranslation
            }
        )
        
        Spacer(modifier = Modifier.height(24.dp))
        
        AnimatedButton(
            text = if (isChecking) "Connecting..." else "Retry",
            onClick = onRetry,
            isChecking = isChecking
        )
        
        Spacer(modifier = Modifier.height(16.dp))
        
        TextButton(onClick = onSkip) {
            Text(
                text = "Skip (I'm already connected)",
                color = AppTheme.colors.muted,
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

@Composable
fun WifiRippleIcon() {
    val infiniteTransition = rememberInfiniteTransition(label = "ripple")
    
    val alphas = List(3) { index ->
        infiniteTransition.animateFloat(
            initialValue = 0.6f,
            targetValue = 0.0f,
            animationSpec = infiniteRepeatable(
                animation = tween(2400, delayMillis = index * 800, easing = LinearEasing),
                repeatMode = RepeatMode.Restart
            ),
            label = "alpha$index"
        )
    }
    
    val scales = List(3) { index ->
        infiniteTransition.animateFloat(
            initialValue = 1.0f,
            targetValue = 2.5f,
            animationSpec = infiniteRepeatable(
                animation = tween(2400, delayMillis = index * 800, easing = LinearEasing),
                repeatMode = RepeatMode.Restart
            ),
            label = "scale$index"
        )
    }
    
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.size(120.dp)
    ) {
        for (i in 0 until 3) {
            Box(
                modifier = Modifier
                    .size(64.dp)
                    .graphicsLayer {
                        scaleX = scales[i].value
                        scaleY = scales[i].value
                        alpha = alphas[i].value
                    }
                    .background(AppTheme.colors.accent, CircleShape)
            )
        }
        
        Box(
            modifier = Modifier
                .size(64.dp)
                .background(AppTheme.colors.surface, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.Wifi,
                contentDescription = "Wi-Fi",
                tint = AppTheme.colors.accent,
                modifier = Modifier.size(32.dp)
            )
        }
    }
}

@Composable
fun AnimatedButton(
    text: String,
    onClick: () -> Unit,
    isChecking: Boolean
) {
    var pressed by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.97f else 1.0f,
        animationSpec = tween(120),
        label = "buttonScale"
    )
    
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp)
            .scale(scale)
            .clip(RoundedCornerShape(28.dp))
            .background(AppTheme.colors.button)
            .pointerInput(Unit) {
                while (true) {
                    awaitPointerEventScope {
                        awaitFirstDown(requireUnconsumed = false)
                        pressed = true
                        waitForUpOrCancellation()
                        pressed = false
                        if (!isChecking) {
                            onClick()
                        }
                    }
                }
            }
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                enabled = !isChecking,
                onClick = {}
            ),
        contentAlignment = Alignment.Center
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            AnimatedContent(
                targetState = isChecking,
                label = "buttonContent"
            ) { checking ->
                if (checking) {
                    CircularProgressIndicator(
                        modifier = Modifier
                            .size(24.dp)
                            .padding(end = 8.dp),
                        color = AppTheme.colors.buttonText,
                        strokeWidth = 2.dp
                    )
                }
            }
            
            Text(
                text = text,
                color = AppTheme.colors.buttonText,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}
