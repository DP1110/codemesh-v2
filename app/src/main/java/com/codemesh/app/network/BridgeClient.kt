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
        baseUrl = if (url.startsWith("http")) url else "http://$url"
    }

    /**
     * POST /chat with messages[] array. Returns Flow<ChatChunk> for streaming.
     * The caller collects this flow to get tokens as they arrive.
     */
    fun chatStream(
        messages: List<Map<String, String>>,
        model: String
    ): Flow<ChatChunk> = flow {
        val json = JSONObject().apply {
            put("model", model)
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
        val request = Request.Builder()
            .url("$baseUrl/chat")
            .post(body)
            .build()

        val call = chatClient.newCall(request)
        try {
            val response = call.execute()
            
            if (!response.isSuccessful) {
                val errorBody = response.body?.string() ?: "HTTP ${response.code}"
                emit(ChatChunk(error = "Bridge error: $errorBody", done = true))
                response.close()
                return@flow
            }

            val inputStream = response.body?.byteStream()
            if (inputStream == null) {
                emit(ChatChunk(error = "Empty response from bridge", done = true))
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
                call.cancel()
                throw e
            } else {
                emit(ChatChunk(error = "Network error: ${e.message}", done = true))
            }
        }
    }.flowOn(Dispatchers.IO)

    /**
     * GET /status → Status data class
     */
    suspend fun getStatus(): Result<Status> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder().url("$baseUrl/status").get().build()
            val response = quickClient.newCall(request).execute()
            val body = response.body?.string() ?: throw IOException("Empty response")
            val json = JSONObject(body)
            Result.success(Status(
                model = json.optString("model", "unknown"),
                ip = json.optString("ip", "unknown"),
                freeMem = json.optLong("free_mem", 0)
            ))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * GET /models → List<ModelTag>
     */
    suspend fun getModels(): Result<List<ModelTag>> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder().url("$baseUrl/models").get().build()
            val response = quickClient.newCall(request).execute()
            val body = response.body?.string() ?: throw IOException("Empty response")
            val json = JSONObject(body)
            
            // Check for error
            if (json.has("error")) {
                return@withContext Result.failure(IOException(json.getString("error")))
            }
            
            val models = mutableListOf<ModelTag>()
            val modelsArray = json.optJSONArray("models") ?: JSONArray()
            for (i in 0 until modelsArray.length()) {
                val m = modelsArray.getJSONObject(i)
                models.add(ModelTag(
                    name = m.getString("name"),
                    size = m.optLong("size"),
                    modifiedAt = m.optString("modified_at")
                ))
            }
            Result.success(models)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * POST /config with ip and model params (form-urlencoded)
     */
    suspend fun postConfig(ip: String, model: String): Result<Unit> = withContext(Dispatchers.IO) {
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
            if (response.isSuccessful) Result.success(Unit)
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
