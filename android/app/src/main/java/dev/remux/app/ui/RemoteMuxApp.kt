@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package dev.remux.app.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun RemoteMuxApp(viewModel: MainViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbarHost = remember { SnackbarHostState() }
    val keyboard = LocalSoftwareKeyboardController.current
    val density = LocalDensity.current
    val imeVisible = WindowInsets.ime.getBottom(density) > 0
    val pairingLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { reader ->
                    reader.readText()
                } ?: error("Cannot open pairing file")
            }.onSuccess(viewModel::importPairing)
                .onFailure { viewModel.reportError(it.message ?: "Cannot import pairing") }
        }
    }

    LaunchedEffect(state.message) {
        val message = state.message ?: return@LaunchedEffect
        snackbarHost.showSnackbar(message)
        viewModel.clearMessage()
    }

    BackHandler(enabled = state.screen != AppScreen.SETUP && state.screen != AppScreen.MACHINES) {
        when {
            state.screen == AppScreen.TERMINALS && imeVisible -> keyboard?.hide()
            state.screen == AppScreen.TERMINALS -> viewModel.leaveTerminals()
            state.screen == AppScreen.MACHINE_DETAIL -> viewModel.showMachines()
            state.screen == AppScreen.SETTINGS -> viewModel.showMachines()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHost) },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        if (state.loadingConfig) {
            Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }
            return@Scaffold
        }

        when (state.screen) {
            AppScreen.SETUP -> RelaySetupScreen(
                state = state,
                modifier = Modifier.padding(padding),
                onSave = viewModel::configureRelay,
            )
            AppScreen.MACHINES -> MachineListScreen(
                state = state,
                modifier = Modifier.padding(padding),
                onSearch = viewModel::setSearch,
                onOnlineOnly = viewModel::setOnlineOnly,
                onMachine = viewModel::openMachine,
                onFavorite = viewModel::toggleFavorite,
                onImportPairing = {
                    pairingLauncher.launch(arrayOf("text/*", "application/octet-stream"))
                },
                onSettings = viewModel::showSettings,
                onTerminals = viewModel::showTerminals,
            )
            AppScreen.MACHINE_DETAIL -> MachineDetailScreen(
                state = state,
                viewModel = viewModel,
                modifier = Modifier.padding(padding),
            )
            AppScreen.TERMINALS -> TerminalWorkspaceScreen(
                state = state,
                viewModel = viewModel,
                modifier = Modifier.padding(padding),
            )
            AppScreen.SETTINGS -> SettingsScreen(
                state = state,
                modifier = Modifier.padding(padding),
                onBack = viewModel::showMachines,
                onSave = viewModel::configureRelay,
                onTmuxPrefix = viewModel::updateTmuxPrefix,
                onRemovePairing = viewModel::removePairing,
                onImportPairing = {
                    pairingLauncher.launch(arrayOf("text/*", "application/octet-stream"))
                },
            )
        }
    }
}
