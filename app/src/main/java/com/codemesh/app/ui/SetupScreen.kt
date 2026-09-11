package com.codemesh.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun SetupScreen(
    onRetry: () -> Unit,
    onSkip: () -> Unit,
    isChecking: Boolean
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(CodeMeshColors.Background)
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "CodeMesh",
            color = CodeMeshColors.Primary,
            fontSize = 32.sp,
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.padding(16.dp))
        
        Text(
            text = "Connect to LLM-Bridge",
            color = Color.White,
            fontSize = 20.sp,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(Modifier.padding(24.dp))

        val instructions = listOf(
            "1. Power on ESP8266 bridge",
            "2. Connect to Wi-Fi: SSID \"LLM-Bridge\", password \"llm12345\"",
            "3. Start Ollama on laptop: OLLAMA_HOST=0.0.0.0 ollama serve",
            "4. Connect laptop to LLM-Bridge Wi-Fi too"
        )
        
        Column(modifier = Modifier.fillMaxWidth()) {
            instructions.forEach { text ->
                Text(
                    text = text,
                    color = CodeMeshColors.MutedText,
                    fontSize = 16.sp,
                    modifier = Modifier.padding(vertical = 8.dp)
                )
            }
        }
        
        Spacer(Modifier.padding(32.dp))

        if (isChecking) {
            CircularProgressIndicator(
                color = CodeMeshColors.Primary,
                modifier = Modifier.size(48.dp)
            )
            Spacer(Modifier.padding(8.dp))
            Text("Checking connection...", color = CodeMeshColors.MutedText)
        } else {
            Button(
                onClick = onRetry,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = CodeMeshColors.Primary)
            ) {
                Text("Retry", color = Color.Black, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.padding(8.dp))
            TextButton(onClick = onSkip) {
                Text("Skip (I'm already connected)", color = CodeMeshColors.MutedText)
            }
            Spacer(Modifier.padding(8.dp))
            Text(
                "Could not reach the bridge at 192.168.4.1",
                color = Color.Red,
                textAlign = TextAlign.Center
            )
        }
    }
}
