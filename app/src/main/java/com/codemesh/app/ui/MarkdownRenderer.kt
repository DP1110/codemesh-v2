package com.codemesh.app.ui

import android.widget.TextView
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import io.noties.markwon.Markwon
import io.noties.markwon.ext.tables.TablePlugin

sealed class MarkdownSegment {
    data class Text(val content: String) : MarkdownSegment()
    data class CodeBlock(val code: String, val language: String) : MarkdownSegment()
}

fun parseMarkdownSegments(markdown: String): List<MarkdownSegment> {
    val segments = mutableListOf<MarkdownSegment>()
    val pattern = Regex("```(\\w*)\\n([\\s\\S]*?)```")
    var lastIndex = 0

    pattern.findAll(markdown).forEach { matchResult ->
        val textBefore = markdown.substring(lastIndex, matchResult.range.first)
        if (textBefore.isNotBlank()) {
            segments.add(MarkdownSegment.Text(textBefore))
        }

        val language = matchResult.groupValues[1]
        val code = matchResult.groupValues[2]
        segments.add(MarkdownSegment.CodeBlock(code, language))

        lastIndex = matchResult.range.last + 1
    }

    val textAfter = markdown.substring(lastIndex)
    if (textAfter.isNotBlank()) {
        segments.add(MarkdownSegment.Text(textAfter))
    }

    return segments
}

@Composable
fun MarkdownText(
    markdown: String,
    modifier: Modifier = Modifier
) {
    val segments = remember(markdown) { parseMarkdownSegments(markdown) }

    Column(modifier = modifier) {
        for (segment in segments) {
            when (segment) {
                is MarkdownSegment.Text -> {
                    MarkwonText(segment.content)
                }
                is MarkdownSegment.CodeBlock -> {
                    CodeBlockView(
                        code = segment.code,
                        language = segment.language
                    )
                }
            }
        }
    }
}

@Composable
fun MarkwonText(content: String) {
    val context = LocalContext.current
    val markwon = remember {
        Markwon.builder(context)
            .usePlugin(TablePlugin.create(context))
            .build()
    }

    AndroidView(
        modifier = Modifier.padding(vertical = 4.dp),
        factory = { ctx ->
            TextView(ctx).apply {
                setTextColor(Color(0xFFE0E0E0).toArgb())
                textSize = 14f
                setLineSpacing(4f, 1.1f)
                setTextIsSelectable(true)
            }
        },
        update = { textView ->
            markwon.setMarkdown(textView, content)
        }
    )
}

@Composable
fun CodeBlockView(code: String, language: String) {
    val clipboardManager = LocalClipboardManager.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
            .background(CodeMeshColors.CodeBlockBg, RoundedCornerShape(8.dp))
    ) {
        // Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = language.ifEmpty { "code" },
                color = CodeMeshColors.MutedText,
                fontSize = 12.sp
            )
            IconButton(
                onClick = { clipboardManager.setText(AnnotatedString(code)) },
                modifier = Modifier.size(24.dp)
            ) {
                Icon(
                    Icons.Default.ContentCopy,
                    contentDescription = "Copy",
                    tint = CodeMeshColors.MutedText,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
        
        // Code Content
        SelectionContainer {
            Text(
                text = code,
                color = Color(0xFFE0E0E0),
                fontFamily = FontFamily.Monospace,
                fontSize = 13.sp,
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            )
        }
    }
}
