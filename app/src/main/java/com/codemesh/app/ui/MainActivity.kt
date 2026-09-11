package com.codemesh.app.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Surface
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.codemesh.app.viewmodel.ChatViewModel
import com.codemesh.app.viewmodel.ConnectionState
import com.codemesh.app.viewmodel.SettingsViewModel
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val chatViewModel: ChatViewModel by viewModels()
    private val settingsViewModel: SettingsViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            CodeMeshTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = CodeMeshColors.Background
                ) {
                    AppNavigation(chatViewModel, settingsViewModel)
                }
            }
        }
    }
}

@Composable
fun AppNavigation(chatViewModel: ChatViewModel, settingsViewModel: SettingsViewModel) {
    // Connection check: use the ViewModel's bridge status to determine if connected
    var hasSkipped by remember { mutableStateOf(false) }
    var isChecking by remember { mutableStateOf(true) }
    var isReachable by remember { mutableStateOf(false) }

    // Check bridge connection on launch
    LaunchedEffect(Unit) {
        isChecking = true
        isReachable = chatViewModel.checkBridgeConnection()
        isChecking = false
    }

    if (!isReachable && !hasSkipped) {
        SetupScreen(
            onRetry = {
                // Re-trigger connection check
                isChecking = true
                isReachable = false
            },
            onSkip = {
                hasSkipped = true
            },
            isChecking = isChecking
        )

        // Handle retry
        LaunchedEffect(isChecking) {
            if (isChecking) {
                isReachable = chatViewModel.checkBridgeConnection()
                isChecking = false
            }
        }
    } else {
        // Main app with sidebar + navigation
        val navController = rememberNavController()
        val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
        val scope = rememberCoroutineScope()
        val uiState by chatViewModel.uiState.collectAsState()

        ModalNavigationDrawer(
            drawerState = drawerState,
            drawerContent = {
                SessionSidebar(
                    sessions = uiState.sessions,
                    currentSessionId = uiState.currentSession?.id,
                    onSessionClick = { id ->
                        chatViewModel.loadSession(id)
                        scope.launch { drawerState.close() }
                    },
                    onNewChat = {
                        chatViewModel.createNewSession()
                        scope.launch { drawerState.close() }
                    },
                    onRenameSession = { id, newName -> chatViewModel.renameSession(id, newName) },
                    onDeleteSession = { id -> chatViewModel.deleteSession(id) }
                )
            }
        ) {
            NavHost(navController = navController, startDestination = "chat") {
                composable("chat") {
                    ChatScreen(
                        viewModel = chatViewModel,
                        onOpenSidebar = { scope.launch { drawerState.open() } },
                        onOpenSettings = { navController.navigate("settings") }
                    )
                }
                composable("settings") {
                    SettingsScreen(
                        viewModel = settingsViewModel,
                        onBack = { navController.popBackStack() }
                    )
                }
            }
        }
    }
}
