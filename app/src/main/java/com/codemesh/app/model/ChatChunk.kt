package com.codemesh.app.model

data class ChatChunk(
    val content: String? = null,
    val error: String? = null,
    val done: Boolean = false
)
