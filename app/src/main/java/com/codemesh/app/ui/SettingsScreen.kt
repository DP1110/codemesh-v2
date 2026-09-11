package com.codemesh.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.codemesh.app.viewmodel.SettingsViewModel
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    onBack: () -> Unit
) {
    var bridgeIp by remember { mutableStateOf(viewModel.bridgeIp.ifEmpty { "192.168.4.1" }) }
    var laptopIp by remember { mutableStateOf(viewModel.laptopIp.ifEmpty { "192.168.4.2" }) }
    var model by remember { mutableStateOf(viewModel.model.ifEmpty { "llama3" }) }
    var systemPrompt by remember { mutableStateOf(viewModel.systemPrompt.ifEmpty { "You are CodeMesh, a helpful coding assistant." }) }
    var historyDepth by remember { mutableFloatStateOf(viewModel.historyDepth.toFloat().takeIf { it > 0 } ?: 10f) }

    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings", color = Color.White) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = Color.White)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = CodeMeshColors.Background)
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = CodeMeshColors.Background
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
        ) {
            val colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = CodeMeshColors.Panel,
                unfocusedContainerColor = CodeMeshColors.Panel,
                focusedBorderColor = CodeMeshColors.Primary,
                unfocusedBorderColor = CodeMeshColors.PanelBorder,
                focusedTextColor = Color.White,
                unfocusedTextColor = Color.White
            )

            OutlinedTextField(
                value = bridgeIp,
                onValueChange = { bridgeIp = it },
                label = { Text("Bridge IP", color = CodeMeshColors.MutedText) },
                modifier = Modifier.fillMaxWidth(),
                colors = colors
            )
            Spacer(Modifier.padding(8.dp))

            OutlinedTextField(
                value = laptopIp,
                onValueChange = { laptopIp = it },
                label = { Text("Laptop IP", color = CodeMeshColors.MutedText) },
                modifier = Modifier.fillMaxWidth(),
                colors = colors
            )
            Spacer(Modifier.padding(8.dp))

            OutlinedTextField(
                value = model,
                onValueChange = { model = it },
                label = { Text("Model", color = CodeMeshColors.MutedText) },
                modifier = Modifier.fillMaxWidth(),
                colors = colors
            )
            Spacer(Modifier.padding(8.dp))

            OutlinedTextField(
                value = systemPrompt,
                onValueChange = { systemPrompt = it },
                label = { Text("System Prompt", color = CodeMeshColors.MutedText) },
                modifier = Modifier.fillMaxWidth(),
                minLines = 3,
                maxLines = 5,
                colors = colors
            )
            Spacer(Modifier.padding(16.dp))

            Text("History Depth: ${historyDepth.toInt()}", color = Color.White)
            Slider(
                value = historyDepth,
                onValueChange = { historyDepth = it },
                valueRange = 1f..50f,
                steps = 49,
                colors = SliderDefaults.colors(
                    thumbColor = CodeMeshColors.Primary,
                    activeTrackColor = CodeMeshColors.Primary,
                    inactiveTrackColor = CodeMeshColors.PanelBorder
                )
            )

            Spacer(Modifier.weight(1f))

            Button(
                onClick = {
                    viewModel.save(
                        bridgeIp = bridgeIp,
                        laptopIp = laptopIp,
                        model = model,
                        systemPrompt = systemPrompt,
                        historyDepth = historyDepth.toInt()
                    )
                    scope.launch {
                        snackbarHostState.showSnackbar("Settings saved")
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = CodeMeshColors.Primary)
            ) {
                Text("Save", color = Color.Black)
            }
        }
    }
}
