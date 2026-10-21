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
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import lt.kantrybes.tvremote.TvRemoteApplication
import lt.kantrybes.tvremote.ui.demo.DemoIntroDialog
import lt.kantrybes.tvremote.ui.launcher.LauncherScreen
import lt.kantrybes.tvremote.ui.remote.RemoteScreen
import lt.kantrybes.tvremote.ui.screen.ScreenScreen
import lt.kantrybes.tvremote.ui.settings.PairingState
import lt.kantrybes.tvremote.ui.settings.SettingsScreen
import lt.kantrybes.tvremote.ui.settings.SettingsViewModel

@Composable
internal fun TvRemoteApp() {
    val settingsViewModel: SettingsViewModel = viewModel()
    val settings by settingsViewModel.state.collectAsStateWithLifecycle()
    var place by rememberSaveable { mutableStateOf(Place.Remote) }
    var showScreen by rememberSaveable { mutableStateOf(false) }
    val network = rememberLocalNetworkAccess()
    val app = LocalContext.current.applicationContext as TvRemoteApplication

    LaunchedEffect(settings.prefsLoaded, settings.pairedHost, network.granted) {
        if (settings.prefsLoaded && settings.pairedHost.isNotBlank() && network.granted) {
            app.remote.ensureRunning()
        }
    }
    LaunchedEffect(settings.pairingState) {
        if (settings.pairingState is PairingState.Succeeded) {
            place = Place.Remote
            showScreen = false
        }
    }

    if (!settings.prefsLoaded) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }
    DemoIntroDialog()
    Box(modifier = Modifier.fillMaxSize()) {
        RemoteScreen(
            network = network,
            active = place == Place.Remote && !showScreen,
            onOpenSettings = { place = Place.Settings },
            onOpenLauncher = { place = Place.Launcher },
            onOpenScreen = { showScreen = true },
        )
        when {
            showScreen -> ScreenScreen(onClose = { showScreen = false })
            place == Place.Launcher -> LauncherScreen(
                network = network,
                onClose = { place = Place.Remote },
            )
            place == Place.Settings -> SettingsScreen(
                viewModel = settingsViewModel,
                network = network,
                onClose = { place = Place.Remote },
            )
        }
    }
}

private enum class Place {
    Remote,
    Launcher,
    Settings,
}
