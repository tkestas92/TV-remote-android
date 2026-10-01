package lt.kantrybes.tvremote.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import lt.kantrybes.tvremote.ui.remote.RemoteScreen
import lt.kantrybes.tvremote.ui.screen.ScreenScreen
import lt.kantrybes.tvremote.ui.settings.PairingState
import lt.kantrybes.tvremote.ui.settings.SettingsScreen
import lt.kantrybes.tvremote.ui.settings.SettingsViewModel

@Composable
internal fun TvRemoteApp() {
    val settingsViewModel: SettingsViewModel = viewModel()
    val settings by settingsViewModel.state.collectAsStateWithLifecycle()
    var showRemote by rememberSaveable { mutableStateOf(false) }
    var showScreen by rememberSaveable { mutableStateOf(false) }
    var decided by rememberSaveable { mutableStateOf(false) }
    val network = rememberLocalNetworkAccess()

    LaunchedEffect(settings.prefsLoaded) {
        if (settings.prefsLoaded && !decided) {
            showRemote = settings.pairedHost.isNotBlank()
            decided = true
        }
    }
    LaunchedEffect(settings.pairingState) {
        if (settings.pairingState is PairingState.Succeeded) {
            showRemote = true
            showScreen = false
            decided = true
        }
    }

    if (!decided) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }
    if (showRemote && showScreen) {
        ScreenScreen(onClose = { showScreen = false })
    } else if (showRemote) {
        RemoteScreen(
            network = network,
            onOpenSettings = {
                showScreen = false
                showRemote = false
            },
            onOpenScreen = { showScreen = true },
        )
    } else {
        SettingsScreen(
            viewModel = settingsViewModel,
            network = network,
            onOpenRemote = { showRemote = true },
        )
    }
}
