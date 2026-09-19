package com.codemesh.app.viewmodel

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.codemesh.app.network.BridgeClient
import com.codemesh.app.ui.ThemeStyle
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class SettingsViewModel(application: Application) : AndroidViewModel(application) {
    private val prefs = application.getSharedPreferences("codemesh_prefs", Context.MODE_PRIVATE)
    private val bridgeClient = BridgeClient()

    // Expose current values as simple properties for SettingsScreen initial state
    var bridgeIp: String = prefs.getString("bridge_ip", "192.168.4.1") ?: "192.168.4.1"
        private set
    var laptopIp: String = prefs.getString("laptop_ip", "192.168.4.2") ?: "192.168.4.2"
        private set
    var model: String = prefs.getString("model", "llama3") ?: "llama3"
        private set
    var systemPrompt: String = prefs.getString("system_prompt", "You are CodeMesh, a helpful coding assistant.") ?: "You are CodeMesh, a helpful coding assistant."
        private set
    var historyDepth: Int = prefs.getInt("history_depth", 20)
        private set

    // Theme state
    private val _themeStyle = MutableStateFlow(
        when (prefs.getString("app_theme", "claude")) {
            "chatgpt" -> ThemeStyle.CHATGPT
            else -> ThemeStyle.CLAUDE
        }
    )
    val themeStyle: StateFlow<ThemeStyle> = _themeStyle.asStateFlow()

    data class SaveState(
        val isSaving: Boolean = false,
        val saveResult: String? = null
    )

    private val _saveState = MutableStateFlow(SaveState())
    val saveState: StateFlow<SaveState> = _saveState.asStateFlow()

    fun setTheme(style: ThemeStyle) {
        _themeStyle.value = style
        prefs.edit()
            .putString("app_theme", if (style == ThemeStyle.CHATGPT) "chatgpt" else "claude")
            .apply()
    }

    /**
     * Save all settings. Called from SettingsScreen with the current field values.
     */
    fun save(
        bridgeIp: String,
        laptopIp: String,
        model: String,
        systemPrompt: String,
        historyDepth: Int
    ) {
        // Update local properties
        this.bridgeIp = bridgeIp
        this.laptopIp = laptopIp
        this.model = model
        this.systemPrompt = systemPrompt
        this.historyDepth = historyDepth

        viewModelScope.launch {
            _saveState.update { it.copy(isSaving = true) }

            // Persist to SharedPreferences
            prefs.edit()
                .putString("bridge_ip", bridgeIp)
                .putString("laptop_ip", laptopIp)
                .putString("model", model)
                .putString("system_prompt", systemPrompt)
                .putInt("history_depth", historyDepth)
                .apply()

            // Push config to bridge
            bridgeClient.updateBaseUrl(bridgeIp)
            val result = bridgeClient.postConfig(laptopIp, model)

            _saveState.update {
                it.copy(
                    isSaving = false,
                    saveResult = if (result.isSuccess) "Settings saved & bridge updated"
                                 else "Saved locally (bridge unreachable)"
                )
            }

            delay(3000)
            _saveState.update { it.copy(saveResult = null) }
        }
    }
}
