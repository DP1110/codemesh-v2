package com.codemesh.app.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.codemesh.app.viewmodel.SettingsViewModel
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    onBack: () -> Unit
) {
    val themeStyle by viewModel.themeStyle.collectAsState()
    val saveState by viewModel.saveState.collectAsState()
    
    var bridgeIp by remember { mutableStateOf(viewModel.bridgeIp) }
    var laptopIp by remember { mutableStateOf(viewModel.laptopIp) }
    var model by remember { mutableStateOf(viewModel.model) }
    var systemPrompt by remember { mutableStateOf(viewModel.systemPrompt) }
    var historyDepth by remember { mutableStateOf(viewModel.historyDepth.toFloat()) }

    var visible by remember { mutableStateOf(false) }
    
    LaunchedEffect(Unit) {
        visible = true
    }
    
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(AppTheme.colors.base)
    ) {
        // Top Bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = AppTheme.colors.text
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "Settings",
                color = AppTheme.colors.text,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold
            )
        }
        
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            // Theme Toggle
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 16.dp)
                    .height(48.dp)
                    .background(AppTheme.colors.surface, RoundedCornerShape(24.dp))
                    .border(1.dp, AppTheme.colors.divider, RoundedCornerShape(24.dp)),
                verticalAlignment = Alignment.CenterVertically
            ) {
                ThemeToggleButton(
                    text = "ChatGPT Style",
                    isSelected = themeStyle == ThemeStyle.CHATGPT,
                    onClick = { viewModel.setTheme(ThemeStyle.CHATGPT) },
                    modifier = Modifier.weight(1f)
                )
                ThemeToggleButton(
                    text = "Claude Style",
                    isSelected = themeStyle == ThemeStyle.CLAUDE,
                    onClick = { viewModel.setTheme(ThemeStyle.CLAUDE) },
                    modifier = Modifier.weight(1f)
                )
            }
            
            StaggeredField(visible = visible, delayMillis = 0) {
                SettingsTextField(
                    label = "Bridge IP",
                    value = bridgeIp,
                    onValueChange = { bridgeIp = it }
                )
            }
            
            StaggeredField(visible = visible, delayMillis = 100) {
                SettingsTextField(
                    label = "Laptop IP",
                    value = laptopIp,
                    onValueChange = { laptopIp = it }
                )
            }
            
            StaggeredField(visible = visible, delayMillis = 200) {
                SettingsTextField(
                    label = "Model",
                    value = model,
                    onValueChange = { model = it }
                )
            }
            
            StaggeredField(visible = visible, delayMillis = 300) {
                SettingsTextField(
                    label = "System prompt",
                    value = systemPrompt,
                    onValueChange = { systemPrompt = it },
                    singleLine = false,
                    modifier = Modifier.height(120.dp)
                )
            }
            
            StaggeredField(visible = visible, delayMillis = 400) {
                Column(modifier = Modifier.padding(vertical = 16.dp)) {
                    Text(
                        text = "History depth: ${historyDepth.toInt()}",
                        color = AppTheme.colors.text,
                        fontSize = 14.sp
                    )
                    
                    val animatable = remember { Animatable(1f) }
                    LaunchedEffect(Unit) {
                        delay(400) // Wait for stagger
                        animatable.animateTo(1.3f, tween(150))
                        animatable.animateTo(1f, tween(150))
                        animatable.animateTo(1.3f, tween(150))
                        animatable.animateTo(1f, tween(150))
                    }

                    Slider(
                        value = historyDepth,
                        onValueChange = { historyDepth = it },
                        valueRange = 0f..100f,
                        colors = SliderDefaults.colors(
                            thumbColor = AppTheme.colors.accent,
                            activeTrackColor = AppTheme.colors.accent,
                            inactiveTrackColor = AppTheme.colors.surface
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .scale(animatable.value)
                    )
                }
            }
            
            Spacer(modifier = Modifier.weight(1f))
            
            StaggeredField(visible = visible, delayMillis = 500) {
                var isSavingOld by remember { mutableStateOf(false) }
                var showCheck by remember { mutableStateOf(false) }
                
                LaunchedEffect(saveState.isSaving) {
                    if (isSavingOld && !saveState.isSaving) {
                        showCheck = true
                        delay(1500)
                        showCheck = false
                    }
                    isSavingOld = saveState.isSaving
                }
                
                val interactionSource = remember { MutableInteractionSource() }
                val isPressed by interactionSource.collectIsPressedAsState()
                val buttonScale by animateFloatAsState(
                    targetValue = if (isPressed) 0.97f else 1f,
                    animationSpec = tween(durationMillis = 120),
                    label = "buttonScale"
                )

                Button(
                    onClick = {
                        viewModel.save(
                            bridgeIp,
                            laptopIp,
                            model,
                            systemPrompt,
                            historyDepth.toInt()
                        )
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 16.dp)
                        .height(52.dp)
                        .scale(buttonScale),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = AppTheme.colors.button,
                        contentColor = AppTheme.colors.buttonText
                    ),
                    shape = RoundedCornerShape(8.dp),
                    interactionSource = interactionSource
                ) {
                    if (showCheck) {
                        Icon(
                            imageVector = Icons.Default.Check,
                            contentDescription = "Saved",
                            tint = AppTheme.colors.buttonText
                        )
                    } else {
                        Text(
                            text = if (saveState.isSaving) "Saving..." else "Save",
                            color = AppTheme.colors.buttonText,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@Composable
fun ThemeToggleButton(
    text: String,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxHeight()
            .clickable(onClick = onClick)
            .then(
                if (isSelected) {
                    Modifier
                        .background(
                            color = AppTheme.colors.accent.copy(alpha = 0.15f),
                            shape = RoundedCornerShape(24.dp)
                        )
                        .border(
                            width = 1.dp,
                            color = AppTheme.colors.accent,
                            shape = RoundedCornerShape(24.dp)
                        )
                } else {
                    Modifier
                }
            ),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            color = if (isSelected) AppTheme.colors.accent else AppTheme.colors.muted,
            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
            fontSize = 14.sp
        )
    }
}

@Composable
fun StaggeredField(
    visible: Boolean,
    delayMillis: Int,
    content: @Composable () -> Unit
) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(300, delayMillis)) + 
                slideInVertically(tween(300, delayMillis)) { it / 4 }
    ) {
        content()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsTextField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    singleLine: Boolean = true,
    modifier: Modifier = Modifier
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label, color = AppTheme.colors.muted, fontSize = 12.sp) },
        singleLine = singleLine,
        colors = OutlinedTextFieldDefaults.colors(
            focusedContainerColor = AppTheme.colors.surface,
            unfocusedContainerColor = AppTheme.colors.surface,
            focusedTextColor = AppTheme.colors.text,
            unfocusedTextColor = AppTheme.colors.text,
            focusedBorderColor = AppTheme.colors.accent,
            unfocusedBorderColor = AppTheme.colors.divider,
            cursorColor = AppTheme.colors.accent
        ),
        shape = RoundedCornerShape(8.dp),
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
    )
}
