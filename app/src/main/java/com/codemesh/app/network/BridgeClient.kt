package com.codemesh.app.network

import com.codemesh.app.model.ChatChunk
import com.codemesh.app.model.ModelTag
import com.codemesh.app.model.Status
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

class BridgeClient(private var baseUrl: String = "http://192.168.4.1") {

    @Volatile
    private var isDirectOllama: Boolean = false

    private val chatClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)  // 3 min for long code generation
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private val quickClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .build()

    fun updateBaseUrl(url: String) {
        var clean = url.trim()
        if (!clean.startsWith("http://") && !clean.startsWith("https://")) {
            clean = "http://$clean"
        }
        // Remove trailing slashes
        clean = clean.trimEnd('/')
        baseUrl = clean
    }

    /**
     * POST /chat or /api/chat with messages[] array. Returns Flow<ChatChunk> for streaming.
     * Supports both ESP8266 bridge and direct Ollama endpoints.
     */
    fun chatStream(
        messages: List<Map<String, String>>,
        model: String
    ): Flow<ChatChunk> = flow {
        val json = JSONObject().apply {
            put("model", model)
            put("stream", true) // Required for Ollama direct and bridge
            val msgsArray = JSONArray()
            for (msg in messages) {
                val msgObj = JSONObject()
                msgObj.put("role", msg["role"] ?: "user")
                msgObj.put("content", msg["content"] ?: "")
                msgsArray.put(msgObj)
            }
            put("messages", msgsArray)
        }

        val body = json.toString().toRequestBody("application/json".toMediaType())

        // Choose endpoint: Direct Ollama uses /api/chat, ESP bridge uses /chat
        val primaryEndpoint = if (isDirectOllama || baseUrl.contains(":11434")) {
            "$baseUrl/api/chat"
        } else {
            "$baseUrl/chat"
        }
        val fallbackEndpoint = if (primaryEndpoint.endsWith("/api/chat")) {
            "$baseUrl/chat"
        } else {
            "$baseUrl/api/chat"
        }

        var activeCall: Call? = null
        try {
            var request = Request.Builder().url(primaryEndpoint).post(body).build()
            var call = chatClient.newCall(request)
            activeCall = call
            var response = call.execute()

            // If primary returned 404, try the fallback route
            if (response.code == 404) {
                response.close()
                request = Request.Builder().url(fallbackEndpoint).post(body).build()
                call = chatClient.newCall(request)
                activeCall = call
                response = call.execute()
            }

            if (!response.isSuccessful) {
                val errorBody = response.body?.string() ?: "HTTP ${response.code}"
                emit(ChatChunk(error = "Connection error: $errorBody", done = true))
                response.close()
                return@flow
            }

            val inputStream = response.body?.byteStream()
            if (inputStream == null) {
                emit(ChatChunk(error = "Empty response from server", done = true))
                response.close()
                return@flow
            }

            // Parse the NDJSON stream
            NdjsonStreamParser.parseStream(inputStream).collect { chunk ->
                emit(chunk)
            }

            response.close()
        } catch (e: Exception) {
            if (e is CancellationException) {
                activeCall?.cancel()
                throw e
            } else {
                emit(ChatChunk(error = "Network error: ${e.message}", done = true))
            }
        }
    }.flowOn(Dispatchers.IO)

    /**
     * GET /status (ESP bridge) or /api/tags (Ollama) → Status data class
     */
    suspend fun getStatus(): Result<Status> = withContext(Dispatchers.IO) {
        // 1. Try ESP8266 /status first
        try {
            val request = Request.Builder().url("$baseUrl/status").get().build()
            val response = quickClient.newCall(request).execute()
            if (response.isSuccessful) {
                val body = response.body?.string() ?: ""
                val json = JSONObject(body)
                if (json.has("free_mem") || json.has("model")) {
                    isDirectOllama = false
                    return@withContext Result.success(
                        Status(
                            model = json.optString("model", "unknown"),
                            ip = json.optString("ip", baseUrl),
                            freeMem = json.optLong("free_mem", 0)
                        )
                    )
                }
            }
            response.close()
        } catch (_: Exception) {}

        // 2. Try Direct Ollama endpoint (/api/tags)
        try {
            val tagsReq = Request.Builder().url("$baseUrl/api/tags").get().build()
            val tagsResp = quickClient.newCall(tagsReq).execute()
            if (tagsResp.isSuccessful) {
                isDirectOllama = true
                val body = tagsResp.body?.string() ?: ""
                tagsResp.close()
                val json = JSONObject(body)
                val modelsArray = json.optJSONArray("models")
                val firstModel = if (modelsArray != null && modelsArray.length() > 0) {
                    modelsArray.getJSONObject(0).optString("name", "ollama")
                } else {
                    "ollama"
                }
                return@withContext Result.success(
                    Status(
                        model = firstModel,
                        ip = baseUrl,
                        freeMem = 0
                    )
                )
            }
            tagsResp.close()
        } catch (_: Exception) {}

        // 3. Try Direct Ollama version endpoint (/api/version)
        try {
            val verReq = Request.Builder().url("$baseUrl/api/version").get().build()
            val verResp = quickClient.newCall(verReq).execute()
            if (verResp.isSuccessful) {
                isDirectOllama = true
                verResp.close()
                return@withContext Result.success(
                    Status(
                        model = "ollama",
                        ip = baseUrl,
                        freeMem = 0
                    )
                )
            }
            verResp.close()
        } catch (e: Exception) {
            return@withContext Result.failure(e)
        }

        Result.failure(IOException("Cannot reach bridge or Ollama at $baseUrl"))
    }

    /**
     * GET /models (ESP bridge) or /api/tags (Ollama) → List<ModelTag>
     */
    suspend fun getModels(): Result<List<ModelTag>> = withContext(Dispatchers.IO) {
        val primaryUrl = if (isDirectOllama || baseUrl.contains(":11434")) {
            "$baseUrl/api/tags"
        } else {
            "$baseUrl/models"
        }
        val fallbackUrl = if (primaryUrl.endsWith("/api/tags")) {
            "$baseUrl/models"
        } else {
            "$baseUrl/api/tags"
        }

        for (url in listOf(primaryUrl, fallbackUrl)) {
            try {
                val request = Request.Builder().url(url).get().build()
                val response = quickClient.newCall(request).execute()
                if (!response.isSuccessful) {
                    response.close()
                    continue
                }
                val body = response.body?.string() ?: throw IOException("Empty response")
                response.close()
                val json = JSONObject(body)

                if (json.has("error")) {
                    return@withContext Result.failure(IOException(json.getString("error")))
                }

                val models = mutableListOf<ModelTag>()
                val modelsArray = json.optJSONArray("models") ?: JSONArray()
                for (i in 0 until modelsArray.length()) {
                    val m = modelsArray.getJSONObject(i)
                    models.add(
                        ModelTag(
                            name = m.getString("name"),
                            size = m.optLong("size"),
                            modifiedAt = m.optString("modified_at")
                        )
                    )
                }
                return@withContext Result.success(models)
            } catch (_: Exception) {
                // Try fallback URL
            }
        }
        Result.failure(IOException("Failed to fetch models from $baseUrl"))
    }

    /**
     * POST /config with ip and model params (form-urlencoded)
     */
    suspend fun postConfig(ip: String, model: String): Result<Unit> = withContext(Dispatchers.IO) {
        if (isDirectOllama) {
            // Direct Ollama does not need /config
            return@withContext Result.success(Unit)
        }
        try {
            val formBody = FormBody.Builder()
                .add("ip", ip)
                .add("model", model)
                .build()
            val request = Request.Builder()
                .url("$baseUrl/config")
                .post(formBody)
                .build()
            val response = quickClient.newCall(request).execute()
            val isOk = response.isSuccessful
            response.close()
            if (isOk) Result.success(Unit)
            else Result.failure(IOException("Config update failed: ${response.code}"))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Cancel ongoing requests (call when stopping generation)
     */
    fun cancelAll() {
        chatClient.dispatcher.cancelAll()
        quickClient.dispatcher.cancelAll()
    }
}
