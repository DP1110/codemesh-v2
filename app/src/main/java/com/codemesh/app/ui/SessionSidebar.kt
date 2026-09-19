package com.codemesh.app.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.codemesh.app.data.Session
import kotlinx.coroutines.delay
import java.util.concurrent.TimeUnit

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun SessionSidebar(
    sessions: List<Session>,
    currentSessionId: String?,
    onSessionClick: (String) -> Unit,
    onNewChat: () -> Unit,
    onRenameSession: (String, String) -> Unit,
    onDeleteSession: (String) -> Unit
) {
    ModalDrawerSheet(
        drawerContainerColor = AppTheme.colors.base,
        drawerContentColor = AppTheme.colors.text
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (AppTheme.isClaude) {
                        Text(
                            text = "✱ ",
                            color = AppTheme.colors.accent,
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Text(
                        text = "CodeMesh",
                        color = AppTheme.colors.text,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Button(
                        onClick = onNewChat,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = AppTheme.colors.accent,
                            contentColor = AppTheme.colors.buttonText
                        ),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                        modifier = Modifier.height(32.dp)
                    ) {
                        Text("+ New", fontSize = 14.sp)
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Icon(
                        imageVector = Icons.Default.Settings,
                        contentDescription = "Settings",
                        tint = AppTheme.colors.muted,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }

            HorizontalDivider(color = AppTheme.colors.divider)

            // Session List
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                itemsIndexed(
                    items = sessions,
                    key = { _, session -> session.id }
                ) { index, session ->
                    SessionRow(
                        session = session,
                        isSelected = session.id == currentSessionId,
                        index = index,
                        onClick = { onSessionClick(session.id) },
                        onRename = { newName -> onRenameSession(session.id, newName) },
                        onDelete = { onDeleteSession(session.id) }
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SessionRow(
    session: Session,
    isSelected: Boolean,
    index: Int,
    onClick: () -> Unit,
    onRename: (String) -> Unit,
    onDelete: () -> Unit
) {
    var visible by remember { mutableStateOf(false) }
    var isDeleting by remember { mutableStateOf(false) }
    var showDialog by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        delay(index * 70L)
        visible = true
    }

    AnimatedVisibility(
        visible = visible && !isDeleting,
        enter = slideInHorizontally(
            initialOffsetX = { -it },
            animationSpec = tween(300)
        ),
        exit = shrinkVertically(animationSpec = tween(200))
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(IntrinsicSize.Min)
                    .combinedClickable(
                        onClick = onClick,
                        onLongClick = { showDialog = true }
                    )
                    .background(if (isSelected) AppTheme.colors.surface else AppTheme.colors.base)
            ) {
                // Accent bar on left for selected
                if (isSelected) {
                    Box(
                        modifier = Modifier
                            .width(3.dp)
                            .fillMaxHeight()
                            .background(AppTheme.colors.accent)
                    )
                }

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(
                            start = if (isSelected) 12.dp else 16.dp,
                            end = 16.dp,
                            top = 12.dp,
                            bottom = 12.dp
                        )
                ) {
                    Text(
                        text = session.title,
                        color = if (isSelected) AppTheme.colors.accent else AppTheme.colors.text,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = formatRelativeDate(session.createdAt),
                        color = AppTheme.colors.muted,
                        fontSize = 12.sp
                    )
                }
            }
            HorizontalDivider(color = AppTheme.colors.divider, thickness = 0.5.dp)
        }
    }

    if (showDialog) {
        var newTitle by remember { mutableStateOf(session.title) }
        AlertDialog(
            onDismissRequest = { showDialog = false },
            title = { Text("Edit Session", color = AppTheme.colors.text) },
            text = {
                OutlinedTextField(
                    value = newTitle,
                    onValueChange = { newTitle = it },
                    label = { Text("Title", color = AppTheme.colors.muted) },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = AppTheme.colors.text,
                        unfocusedTextColor = AppTheme.colors.text,
                        focusedBorderColor = AppTheme.colors.accent,
                        unfocusedBorderColor = AppTheme.colors.divider
                    )
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    onRename(newTitle)
                    showDialog = false
                }) {
                    Text("Rename", color = AppTheme.colors.accent)
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    isDeleting = true
                    showDialog = false
                    onDelete()
                }) {
                    Text("Delete", color = AppTheme.colors.error)
                }
            },
            containerColor = AppTheme.colors.surface
        )
    }
}

fun formatRelativeDate(epochMillis: Long): String {
    val now = System.currentTimeMillis()
    val diffMillis = now - epochMillis

    val days = TimeUnit.MILLISECONDS.toDays(diffMillis)
    
    return when {
        days == 0L -> "Today"
        days == 1L -> "Yesterday"
        days < 7L -> "$days days ago"
        days < 30L -> "${days / 7} weeks ago"
        days < 365L -> "${days / 30} months ago"
        else -> "${days / 365} years ago"
    }
}
