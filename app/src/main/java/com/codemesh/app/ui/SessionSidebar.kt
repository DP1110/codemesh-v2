package com.codemesh.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.codemesh.app.data.Session
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

@Composable
fun SessionSidebar(
    sessions: List<Session>,
    currentSessionId: String?,
    onSessionClick: (String) -> Unit,
    onNewChat: () -> Unit,
    onRenameSession: (String, String) -> Unit,
    onDeleteSession: (String) -> Unit
) {
    var sessionToRename by remember { mutableStateOf<Session?>(null) }
    var renameText by remember { mutableStateOf("") }
    var sessionToDelete by remember { mutableStateOf<Session?>(null) }

    ModalDrawerSheet(
        drawerContainerColor = CodeMeshColors.Panel,
        modifier = Modifier.width(300.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxHeight()
                .padding(16.dp)
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "CodeMesh",
                    color = CodeMeshColors.Primary,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
                Button(
                    onClick = onNewChat,
                    colors = ButtonDefaults.buttonColors(containerColor = CodeMeshColors.Primary)
                ) {
                    Icon(Icons.Default.Add, contentDescription = "New", tint = Color.Black)
                    Spacer(Modifier.width(4.dp))
                    Text("New", color = Color.Black)
                }
            }

            Spacer(Modifier.padding(8.dp))
            Divider(color = CodeMeshColors.PanelBorder)
            Spacer(Modifier.padding(8.dp))

            // Session List
            LazyColumn {
                items(sessions) { session ->
                    val isActive = session.id == currentSessionId
                    var showMenu by remember { mutableStateOf(false) }

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                            .background(
                                if (isActive) Color(0x1Aff8800) else Color.Transparent
                            )
                            .border(
                                width = 1.dp,
                                color = if (isActive) CodeMeshColors.Warning else Color.Transparent
                            )
                            .pointerInput(Unit) {
                                detectTapGestures(
                                    onTap = { onSessionClick(session.id) },
                                    onLongPress = { showMenu = true }
                                )
                            }
                            .padding(12.dp)
                    ) {
                        Column {
                            Text(
                                text = session.title,
                                color = Color.White,
                                fontSize = 14.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = formatRelativeDate(session.createdAt),
                                color = CodeMeshColors.MutedText,
                                fontSize = 12.sp
                            )
                        }

                        DropdownMenu(
                            expanded = showMenu,
                            onDismissRequest = { showMenu = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text("Rename") },
                                onClick = {
                                    showMenu = false
                                    sessionToRename = session
                                    renameText = session.title
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Delete", color = Color.Red) },
                                onClick = {
                                    showMenu = false
                                    sessionToDelete = session
                                }
                            )
                        }
                    }
                }
            }
        }
    }

    // Rename Dialog
    if (sessionToRename != null) {
        AlertDialog(
            onDismissRequest = { sessionToRename = null },
            title = { Text("Rename Session", color = Color.White) },
            text = {
                OutlinedTextField(
                    value = renameText,
                    onValueChange = { renameText = it },
                    singleLine = true
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    onRenameSession(sessionToRename!!.id, renameText)
                    sessionToRename = null
                }) {
                    Text("Save", color = CodeMeshColors.Primary)
                }
            },
            dismissButton = {
                TextButton(onClick = { sessionToRename = null }) {
                    Text("Cancel", color = CodeMeshColors.MutedText)
                }
            },
            containerColor = CodeMeshColors.Panel
        )
    }

    // Delete Dialog
    if (sessionToDelete != null) {
        AlertDialog(
            onDismissRequest = { sessionToDelete = null },
            title = { Text("Delete Session", color = Color.White) },
            text = { Text("Are you sure you want to delete '${sessionToDelete?.title}'?", color = Color.White) },
            confirmButton = {
                TextButton(onClick = {
                    onDeleteSession(sessionToDelete!!.id)
                    sessionToDelete = null
                }) {
                    Text("Delete", color = Color.Red)
                }
            },
            dismissButton = {
                TextButton(onClick = { sessionToDelete = null }) {
                    Text("Cancel", color = CodeMeshColors.MutedText)
                }
            },
            containerColor = CodeMeshColors.Panel
        )
    }
}

private fun formatRelativeDate(epochMillis: Long): String {
    val now = System.currentTimeMillis()
    val diffMs = now - epochMillis
    val diffDays = TimeUnit.MILLISECONDS.toDays(diffMs)

    return when {
        diffDays == 0L -> "Today"
        diffDays == 1L -> "Yesterday"
        diffDays < 7L -> "$diffDays days ago"
        else -> SimpleDateFormat("MMM d", Locale.getDefault()).format(Date(epochMillis))
    }
}
