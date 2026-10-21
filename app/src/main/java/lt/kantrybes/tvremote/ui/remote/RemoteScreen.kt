package lt.kantrybes.tvremote.ui.remote

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.remember
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import lt.kantrybes.tvremote.BuildConfig
import lt.kantrybes.tvremote.R
import lt.kantrybes.tvremote.TvRemoteApplication
import lt.kantrybes.tvremote.data.settings.ControlMode
import lt.kantrybes.tvremote.data.settings.PictureSlider
import lt.kantrybes.tvremote.data.remote.KeyPress
import lt.kantrybes.tvremote.data.remote.RemoteConnectionState
import lt.kantrybes.tvremote.data.remote.TvKey
import lt.kantrybes.tvremote.ui.demo.DemoChip
import lt.kantrybes.tvremote.ui.demo.VirtualTv
import lt.kantrybes.tvremote.ui.LocalNetworkAccess
import lt.kantrybes.tvremote.ui.LocalNetworkDeniedMessage
import lt.kantrybes.tvremote.ui.shortcuts.ShortcutEditDialog
import lt.kantrybes.tvremote.ui.shortcuts.ShortcutGrid

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RemoteScreen(
    network: LocalNetworkAccess,
    active: Boolean,
    onOpenSettings: () -> Unit,
    onOpenLauncher: () -> Unit,
    onOpenScreen: () -> Unit,
    viewModel: RemoteViewModel = viewModel(),
) {
    val connection by viewModel.connection.collectAsStateWithLifecycle()
    val shortcuts by viewModel.shortcuts.collectAsStateWithLifecycle()
    val showPictureMenu by viewModel.showPictureMenu.collectAsStateWithLifecycle()
    val controlMode by viewModel.controlMode.collectAsStateWithLifecycle()
    val pictureSliders by viewModel.pictureSliders.collectAsStateWithLifecycle()
    val adbMessage by viewModel.adbMessage.collectAsStateWithLifecycle()
    var keyboardOpen by rememberSaveable { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }
    var editingShortcut by rememberSaveable { mutableStateOf(-1) }
    val haptics = LocalHapticFeedback.current
    val picture by (LocalContext.current.applicationContext as TvRemoteApplication)
        .preview.picture.collectAsStateWithLifecycle()
    LaunchedEffect(active) {
        if (!active) {
            keyboardOpen = false
            editingShortcut = -1
        }
    }
    LaunchedEffect(network.granted) {
        if (network.granted) viewModel.start()
    }
    LaunchedEffect(Unit) {
        if (!network.granted) network.request()
    }
    LaunchedEffect(adbMessage) {
        val message = adbMessage
        if (message == null) {
            snackbarHostState.currentSnackbarData?.dismiss()
            return@LaunchedEffect
        }
        snackbarHostState.showSnackbar(
            message = message.text,
            withDismissAction = message.waiting,
            duration = if (message.waiting) SnackbarDuration.Indefinite else SnackbarDuration.Long,
        )
    }
    val ready = connection is RemoteConnectionState.Connected
    val waking = connection is RemoteConnectionState.Connecting ||
        connection is RemoteConnectionState.Reconnecting
    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(stringResource(R.string.app_name))
                            DemoChip()
                        }
                        Text(
                            text = connectionLabel(connection),
                            style = MaterialTheme.typography.labelMedium,
                            color = connectionColor(connection),
                        )
                    }
                },
                actions = {
                    IconButton(
                        onClick = {
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            onOpenScreen()
                        },
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_screen),
                            contentDescription = stringResource(R.string.screen),
                        )
                    }
                    IconButton(
                        onClick = {
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            keyboardOpen = true
                        },
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_keyboard),
                            contentDescription = stringResource(R.string.keyboard),
                        )
                    }
                    if (showPictureMenu) {
                        IconButton(
                            onClick = {
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                viewModel.openPictureMenu()
                            },
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ic_tune),
                                contentDescription = stringResource(R.string.picture_menu),
                            )
                        }
                    }
                    IconButton(
                        onClick = {
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            onOpenLauncher()
                        },
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_launcher_apps),
                            contentDescription = stringResource(R.string.launcher),
                        )
                    }
                    IconButton(
                        onClick = {
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            onOpenSettings()
                        },
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_tune),
                            contentDescription = stringResource(R.string.open_settings),
                        )
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (BuildConfig.IS_DEMO && picture.shown) {
                VirtualTv(
                    picture = picture,
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(16f / 9f),
                )
            }
            ShortcutGrid(
                shortcuts = shortcuts,
                onLaunch = viewModel::launchShortcut,
                onEdit = { editingShortcut = it },
            )
            pictureSliders.filter { it.shown }.forEach { slider ->
                key(slider.item) {
                    PictureValueSlider(
                        item = slider.item,
                        value = slider.value,
                        enabled = ready,
                        onRelease = { chosen ->
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            viewModel.setPictureValue(slider.item, chosen)
                        },
                    )
                }
            }
            if (network.denied && !network.granted) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    LocalNetworkDeniedMessage(network)
                }
            }
            if (connection is RemoteConnectionState.NeedsPairing) {
                Text(
                    text = stringResource(
                        if ((connection as RemoteConnectionState.NeedsPairing).rejected) {
                            R.string.pairing_rejected
                        } else {
                            R.string.connection_needs_pairing
                        },
                    ),
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center,
                )
                TextButton(onClick = onOpenSettings) {
                    Text(stringResource(R.string.pair))
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                KeyButton(stringResource(R.string.key_power), TvKey.POWER, ready || waking, viewModel::sendKey)
                KeyButton(stringResource(R.string.key_home), TvKey.HOME, ready, viewModel::sendKey)
            }
            when (controlMode) {
                ControlMode.BUTTONS -> ButtonCluster(enabled = ready, onKey = viewModel::sendKey)
                ControlMode.DPAD -> DirectionPad(enabled = ready, onKey = viewModel::sendKey)
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                KeyButton(stringResource(R.string.key_back), TvKey.BACK, ready, viewModel::sendKey)
                KeyButton(stringResource(R.string.key_play_pause), TvKey.PLAY_PAUSE, ready, viewModel::sendKey)
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                KeyButton(stringResource(R.string.key_volume_down), TvKey.VOLUME_DOWN, ready, viewModel::sendKey)
                KeyButton(stringResource(R.string.key_mute), TvKey.MUTE, ready, viewModel::sendKey)
                KeyButton(stringResource(R.string.key_volume_up), TvKey.VOLUME_UP, ready, viewModel::sendKey)
            }
        }
    }
    if (keyboardOpen) {
        KeyboardSheet(
            viewModel = viewModel,
            onDismiss = { keyboardOpen = false },
        )
    }
    val editing = shortcuts.getOrNull(editingShortcut)
    if (editing != null) {
        key(editingShortcut) {
            ShortcutEditDialog(
                shortcut = editing,
                onDismiss = { editingShortcut = -1 },
                onSave = { label, packageName ->
                    viewModel.updateShortcut(editingShortcut, label, packageName)
                    editingShortcut = -1
                },
            )
        }
    }
}

@Composable
private fun ButtonCluster(
    enabled: Boolean,
    onKey: (TvKey, KeyPress) -> Unit,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        KeyButton(stringResource(R.string.key_up), TvKey.UP, enabled, onKey)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            KeyButton(stringResource(R.string.key_left), TvKey.LEFT, enabled, onKey)
            KeyButton(
                label = stringResource(R.string.key_ok),
                key = TvKey.OK,
                enabled = enabled,
                onKey = onKey,
                size = 96.dp,
            )
            KeyButton(stringResource(R.string.key_right), TvKey.RIGHT, enabled, onKey)
        }
        KeyButton(stringResource(R.string.key_down), TvKey.DOWN, enabled, onKey)
    }
}

@Composable
internal fun KeyButton(
    label: String,
    key: TvKey,
    enabled: Boolean,
    onKey: (TvKey, KeyPress) -> Unit,
    size: Dp = 76.dp,
) {
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val longPressMs = LocalViewConfiguration.current.longPressTimeoutMillis
    Box(
        modifier = Modifier
            .size(size)
            .alpha(if (enabled) 1f else 0.4f)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.secondaryContainer)
            .semantics {
                role = Role.Button
                contentDescription = label
            }
            .pointerInput(key, enabled, longPressMs) {
                if (!enabled) return@pointerInput
                detectTapGestures(
                    onPress = {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        var held = false
                        val hold = scope.launch {
                            delay(longPressMs)
                            held = true
                            onKey(key, KeyPress.START_LONG)
                        }
                        val released = tryAwaitRelease()
                        hold.cancel()
                        if (held) {
                            onKey(key, KeyPress.END_LONG)
                        } else if (released) {
                            onKey(key, KeyPress.SHORT)
                        }
                    },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
        )
    }
}

@Composable
private fun PictureValueSlider(
    item: String,
    value: Int,
    enabled: Boolean,
    onRelease: (Int) -> Unit,
) {
    var drag by remember(item) { mutableStateOf<Float?>(null) }
    val shown = drag ?: value.toFloat()
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = stringResource(
                R.string.picture_slider_value,
                stringResource(pictureSliderLabel(item)),
                shown.roundToInt(),
            ),
            style = MaterialTheme.typography.titleMedium,
        )
        Slider(
            value = shown,
            onValueChange = { drag = it },
            onValueChangeFinished = {
                val chosen = (drag ?: value.toFloat()).roundToInt()
                drag = null
                onRelease(chosen)
            },
            valueRange = 0f..100f,
            enabled = enabled,
        )
    }
}

private fun pictureSliderLabel(item: String): Int {
    return when (item) {
        PictureSlider.BRIGHTNESS -> R.string.brightness
        PictureSlider.COLOR_SATURATION -> R.string.color_saturation
        PictureSlider.SHARPNESS -> R.string.sharpness
        else -> R.string.contrast
    }
}

@Composable
private fun connectionLabel(state: RemoteConnectionState): String {
    return when (state) {
        RemoteConnectionState.Connected -> stringResource(
            if (BuildConfig.IS_DEMO) R.string.connection_connected_demo else R.string.connection_connected,
        )
        RemoteConnectionState.Connecting,
        RemoteConnectionState.Reconnecting -> stringResource(R.string.connection_waking)
        RemoteConnectionState.Disconnected -> stringResource(R.string.connection_disconnected)
        is RemoteConnectionState.NeedsPairing -> stringResource(
            if (state.rejected) R.string.pairing_rejected else R.string.connection_needs_pairing,
        )
    }
}

@Composable
private fun connectionColor(state: RemoteConnectionState): androidx.compose.ui.graphics.Color {
    return when (state) {
        RemoteConnectionState.Connected -> MaterialTheme.colorScheme.primary
        is RemoteConnectionState.NeedsPairing -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
}
