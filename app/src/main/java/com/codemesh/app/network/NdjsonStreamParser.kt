package com.codemesh.app.network

import com.codemesh.app.model.ChatChunk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader

object NdjsonStreamParser {
    fun parseStream(inputStream: InputStream): Flow<ChatChunk> = flow {
        val reader = BufferedReader(InputStreamReader(inputStream, Charsets.UTF_8))
        try {
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                currentCoroutineContext().ensureActive()
                val trimmed = line!!.trim()
                if (trimmed.isEmpty()) continue
                
                try {
                    val json = JSONObject(trimmed)
                    
                    // Check for error
                    val error = if (json.has("error")) json.getString("error") else null
                    val done = if (json.has("done")) json.getBoolean("done") else false
                    
                    // Extract content from message.content
                    val content = if (json.has("message")) {
                        val message = json.getJSONObject("message")
                        if (message.has("content")) message.getString("content") else null
                    } else null
                    
                    emit(ChatChunk(content = content, error = error, done = done))
                    
                    if (done) break
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    // Skip malformed lines
                    continue
                }
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            emit(ChatChunk(error = "Stream error: ${e.message}", done = true))
        } finally {
            try { reader.close() } catch (_: Exception) {}
        }
    }.flowOn(Dispatchers.IO)
}
