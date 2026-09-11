package com.codemesh.app.viewmodel

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.codemesh.app.data.AppDatabase
import com.codemesh.app.data.HistoryRepository
import com.codemesh.app.data.Message
import com.codemesh.app.data.Session
import com.codemesh.app.model.ModelTag
import com.codemesh.app.model.Status
import com.codemesh.app.network.BridgeClient
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

data class ChatUiState(
    val messages: List<Message> = emptyList(),
    val isStreaming: Boolean = false,
    val streamingContent: String = "",
    val currentSession: Session? = null,
    val sessions: List<Session> = emptyList(),
    val models: List<ModelTag> = emptyList(),
    val selectedModel: String = "llama3",
    val bridgeStatus: Status? = null,
    val connectionState: ConnectionState = ConnectionState.UNKNOWN,
    val error: String? = null
)

enum class ConnectionState { ONLINE, OFFLINE, UNKNOWN }

class ChatViewModel(application: Application) : AndroidViewModel(application) {
    private val db = AppDatabase.getInstance(application)
    private val repository = HistoryRepository(db)
    private val bridgeClient = BridgeClient()

    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    // Preferences
    private var bridgeIp = "192.168.4.1"
    private var laptopIp = "192.168.4.2"
    private var systemPrompt = "You are CodeMesh, a helpful coding assistant."
    private var historyDepth = 20

    private var streamingJob: Job? = null
    private var statusPollJob: Job? = null
    private var messagesJob: Job? = null

    init {
        loadPreferences()
        // Collect sessions reactively
        viewModelScope.launch {
            repository.getAllSessions().collect { sessions ->
                _uiState.update { it.copy(sessions = sessions) }
                // Auto-load first session if none is selected
                if (sessions.isNotEmpty() && _uiState.value.currentSession == null) {
                    loadSession(sessions.first().id)
                }
            }
        }
        // Create initial session if DB is empty
        viewModelScope.launch {
            delay(500) // Allow sessions collection to populate
            if (_uiState.value.sessions.isEmpty()) {
                createNewSession()
            }
        }
        startStatusPolling()
        refreshModels()
    }

    private fun loadPreferences() {
        val prefs = getApplication<Application>().getSharedPreferences("codemesh_prefs", Context.MODE_PRIVATE)
        bridgeIp = prefs.getString("bridge_ip", "192.168.4.1") ?: "192.168.4.1"
        laptopIp = prefs.getString("laptop_ip", "192.168.4.2") ?: "192.168.4.2"
        systemPrompt = prefs.getString("system_prompt", "You are CodeMesh, a helpful coding assistant.") ?: "You are CodeMesh, a helpful coding assistant."
        historyDepth = prefs.getInt("history_depth", 20)

        val savedModel = prefs.getString("model", "llama3") ?: "llama3"
        _uiState.update { it.copy(selectedModel = savedModel) }

        bridgeClient.updateBaseUrl(bridgeIp)
    }

    private fun savePreferences() {
        val prefs = getApplication<Application>().getSharedPreferences("codemesh_prefs", Context.MODE_PRIVATE)
        prefs.edit()
            .putString("bridge_ip", bridgeIp)
            .putString("laptop_ip", laptopIp)
            .putString("model", _uiState.value.selectedModel)
            .putString("system_prompt", systemPrompt)
            .putInt("history_depth", historyDepth)
            .apply()
    }

    fun updateSettings(newBridgeIp: String, newLaptopIp: String, newModel: String, newSystemPrompt: String, newHistoryDepth: Int) {
        bridgeIp = newBridgeIp
        laptopIp = newLaptopIp
        systemPrompt = newSystemPrompt
        historyDepth = newHistoryDepth
        _uiState.update { it.copy(selectedModel = newModel) }

        savePreferences()
        bridgeClient.updateBaseUrl(bridgeIp)

        viewModelScope.launch {
            bridgeClient.postConfig(laptopIp, newModel)
            refreshStatus()
        }
    }

    fun sendMessage(text: String) {
        viewModelScope.launch {
            var session = _uiState.value.currentSession
            if (session == null) {
                val title = if (text.length > 40) text.take(40) + "..." else text
                session = repository.createSession(title)
                _uiState.update { it.copy(currentSession = session) }
                collectMessages(session.id)
            }

            val sessionId = session.id

            // Auto-title if session was "New Session"
            if (session.title == "New Session") {
                val newTitle = if (text.length > 40) text.take(40) + "..." else text
                repository.renameSession(sessionId, newTitle)
            }

            repository.addMessage(sessionId, "user", text)

            _uiState.update { it.copy(isStreaming = true, streamingContent = "", error = null) }

            val payload = repository.buildMessagesPayload(sessionId, systemPrompt, historyDepth)
            val model = _uiState.value.selectedModel

            streamingJob = launch {
                try {
                    bridgeClient.chatStream(payload, model).collect { chunk ->
                        if (chunk.error != null) {
                            _uiState.update { it.copy(error = chunk.error) }
                        } else {
                            _uiState.update { it.copy(streamingContent = it.streamingContent + (chunk.content ?: "")) }
                        }
                    }

                    val finalContent = _uiState.value.streamingContent
                    if (finalContent.isNotEmpty()) {
                        repository.addMessage(sessionId, "assistant", finalContent)
                    }
                } catch (e: CancellationException) {
                    throw e  // Rethrow cancellation
                } catch (e: Exception) {
                    _uiState.update { it.copy(error = e.message ?: "Unknown error") }
                } finally {
                    _uiState.update { it.copy(isStreaming = false, streamingContent = "") }
                }
            }
        }
    }

    fun stopGeneration() {
        streamingJob?.cancel()
        bridgeClient.cancelAll()

        viewModelScope.launch {
            val content = _uiState.value.streamingContent
            val session = _uiState.value.currentSession
            if (content.isNotEmpty() && session != null) {
                repository.addMessage(session.id, "assistant", content)
            }
            _uiState.update { it.copy(isStreaming = false, streamingContent = "") }
        }
    }

    fun createNewSession() {
        viewModelScope.launch {
            val session = repository.createSession("New Session")
            _uiState.update { it.copy(currentSession = session) }
            collectMessages(session.id)
        }
    }

    fun loadSession(sessionId: String) {
        viewModelScope.launch {
            val session = _uiState.value.sessions.find { it.id == sessionId }
            if (session != null) {
                _uiState.update { it.copy(currentSession = session) }
                collectMessages(sessionId)
            }
        }
    }

    fun deleteSession(sessionId: String) {
        viewModelScope.launch {
            repository.deleteSession(sessionId)
            val current = _uiState.value.currentSession
            if (current?.id == sessionId) {
                val remaining = _uiState.value.sessions.filter { it.id != sessionId }
                if (remaining.isNotEmpty()) {
                    loadSession(remaining.first().id)
                } else {
                    createNewSession()
                }
            }
        }
    }

    fun renameSession(sessionId: String, title: String) {
        viewModelScope.launch {
            repository.renameSession(sessionId, title)
        }
    }

    fun refreshStatus() {
        viewModelScope.launch {
            val statusResult = bridgeClient.getStatus()
            statusResult.fold(
                onSuccess = { status ->
                    _uiState.update { it.copy(bridgeStatus = status, connectionState = ConnectionState.ONLINE) }
                },
                onFailure = {
                    _uiState.update { it.copy(bridgeStatus = null, connectionState = ConnectionState.OFFLINE) }
                }
            )
        }
    }

    fun refreshModels() {
        viewModelScope.launch {
            val modelsResult = bridgeClient.getModels()
            modelsResult.onSuccess { models ->
                _uiState.update { it.copy(models = models) }
            }
        }
    }

    fun selectModel(model: String) {
        _uiState.update { it.copy(selectedModel = model) }
        viewModelScope.launch {
            bridgeClient.postConfig(laptopIp, model)
            savePreferences()
        }
    }

    private fun startStatusPolling() {
        statusPollJob?.cancel()
        statusPollJob = viewModelScope.launch {
            while (isActive) {
                refreshStatus()
                delay(15_000)
            }
        }
    }

    private fun collectMessages(sessionId: String) {
        messagesJob?.cancel()
        messagesJob = viewModelScope.launch {
            repository.getMessages(sessionId).collect { messages ->
                _uiState.update { it.copy(messages = messages) }
            }
        }
    }

    /**
     * Check if the bridge is reachable. Used by setup screen.
     */
    suspend fun checkBridgeConnection(): Boolean {
        return bridgeClient.getStatus().isSuccess
    }

    override fun onCleared() {
        super.onCleared()
        statusPollJob?.cancel()
        streamingJob?.cancel()
        messagesJob?.cancel()
    }
}
