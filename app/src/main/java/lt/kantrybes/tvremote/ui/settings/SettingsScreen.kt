package lt.kantrybes.tvremote.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import lt.kantrybes.tvremote.R
import lt.kantrybes.tvremote.data.settings.ControlMode
import lt.kantrybes.tvremote.data.settings.PictureSlider
import lt.kantrybes.tvremote.ui.LocalNetworkAccess
import lt.kantrybes.tvremote.ui.LocalNetworkDeniedMessage

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SettingsScreen(
    network: LocalNetworkAccess,
    onOpenRemote: () -> Unit,
    viewModel: SettingsViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    SettingsContent(
        state = state,
        network = network,
        onTvIpChange = viewModel::onTvIpChange,
        onTvMacChange = viewModel::onTvMacChange,
        onTestConnection = { network.request(viewModel::testConnection) },
        onStartPairing = { network.request(viewModel::startPairing) },
        onCodeChange = viewModel::onCodeChange,
        onSubmitCode = viewModel::submitCode,
        onCancelPairing = viewModel::cancelPairing,
        onOpenRemote = onOpenRemote,
        onShowPictureMenuChange = viewModel::setShowPictureMenuButton,
        onShowMediaControlsChange = viewModel::setShowMediaControls,
        onControlModeChange = viewModel::setControlMode,
        onPictureSliderShown = viewModel::setPictureSliderShown,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SettingsContent(
    state: SettingsUiState,
    network: LocalNetworkAccess,
    onTvIpChange: (String) -> Unit,
    onTvMacChange: (String) -> Unit,
    onTestConnection: () -> Unit,
    onStartPairing: () -> Unit,
    onCodeChange: (String) -> Unit,
    onSubmitCode: () -> Unit,
    onCancelPairing: () -> Unit,
    onOpenRemote: () -> Unit,
    onShowPictureMenuChange: (Boolean) -> Unit,
    onShowMediaControlsChange: (Boolean) -> Unit,
    onControlModeChange: (ControlMode) -> Unit,
    onPictureSliderShown: (String, Boolean) -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    val busy = state.testState is TestState.Running || state.pairingInProgress
    val canAct = state.prefsLoaded && state.tvIp.isNotBlank() && !busy
    Scaffold(
        topBar = {
            TopAppBar(title = { Text(stringResource(R.string.open_settings)) })
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (!state.prefsLoaded) {
                CircularProgressIndicator()
                return@Column
            }
            if (network.denied && !network.granted) {
                LocalNetworkDeniedMessage(network)
            }
            OutlinedTextField(
                value = state.tvIp,
                onValueChange = onTvIpChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.tv_ip_label)) },
                supportingText = { Text(stringResource(R.string.tv_ip_supporting)) },
                singleLine = true,
                enabled = !state.pairingInProgress,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Decimal,
                    imeAction = ImeAction.Done,
                ),
            )
            OutlinedTextField(
                value = state.tvMac,
                onValueChange = onTvMacChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.tv_mac_label)) },
                supportingText = { Text(stringResource(R.string.tv_mac_supporting)) },
                singleLine = true,
                enabled = !state.pairingInProgress,
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Characters,
                    keyboardType = KeyboardType.Ascii,
                    imeAction = ImeAction.Done,
                ),
            )
            Button(
                onClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    onTestConnection()
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = canAct,
            ) {
                if (state.testState is TestState.Running) {
                    WorkingIndicator()
                } else {
                    Text(stringResource(R.string.test_connection))
                }
            }
            val testResult = state.testState as? TestState.Result
            if (testResult != null) {
                Text(
                    text = testResult.message,
                    color = if (testResult.success) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.error
                    },
                )
            }
            Button(
                onClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    onStartPairing()
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = canAct,
            ) {
                if (state.pairingState is PairingState.Connecting) {
                    WorkingIndicator()
                } else {
                    Text(
                        stringResource(
                            if (state.isPaired) R.string.pair_again else R.string.pair,
                        ),
                    )
                }
            }
            if (state.pairedHost.isNotBlank()) {
                TextButton(onClick = onOpenRemote, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.open_remote))
                }
            }
            Text(
                text = stringResource(R.string.control_mode),
                modifier = Modifier.fillMaxWidth(),
                style = MaterialTheme.typography.titleMedium,
            )
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                ControlMode.entries.forEachIndexed { index, mode ->
                    SegmentedButton(
                        selected = state.controlMode == mode,
                        onClick = {
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            onControlModeChange(mode)
                        },
                        shape = SegmentedButtonDefaults.itemShape(index, ControlMode.entries.size),
                        icon = {},
                    ) {
                        Text(stringResource(controlModeLabel(mode)))
                    }
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = stringResource(R.string.show_picture_menu),
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyLarge,
                )
                Switch(
                    checked = state.showPictureMenuButton,
                    onCheckedChange = onShowPictureMenuChange,
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = stringResource(R.string.show_media_controls),
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyLarge,
                )
                Switch(
                    checked = state.showMediaControls,
                    onCheckedChange = onShowMediaControlsChange,
                )
            }
            state.pictureSliders.forEach { slider ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        text = stringResource(pictureSliderLabel(slider.item)),
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Switch(
                        checked = slider.shown,
                        onCheckedChange = { shown -> onPictureSliderShown(slider.item, shown) },
                    )
                }
            }
            Text(
                text = PairingStatus(state),
                color = when {
                    state.pairingState is PairingState.Failed -> MaterialTheme.colorScheme.error
                    state.isPaired || state.pairingState is PairingState.Succeeded ->
                        MaterialTheme.colorScheme.primary
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
    }
    if (
        state.pairingState is PairingState.WaitingForCode ||
        state.pairingState is PairingState.SubmittingCode
    ) {
        PairingCodeDialog(
            code = state.code,
            error = state.codeError,
            submitting = state.pairingState is PairingState.SubmittingCode,
            onCodeChange = onCodeChange,
            onConfirm = onSubmitCode,
            onDismiss = onCancelPairing,
        )
    }
}

@Composable
private fun PairingCodeDialog(
    code: String,
    error: String?,
    submitting: Boolean,
    onCodeChange: (String) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }
    AlertDialog(
        onDismissRequest = { if (!submitting) onDismiss() },
        title = { Text(stringResource(R.string.code_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.code_body))
                OutlinedTextField(
                    value = code,
                    onValueChange = onCodeChange,
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focusRequester),
                    label = { Text(stringResource(R.string.code_label)) },
                    singleLine = true,
                    enabled = !submitting,
                    isError = error != null,
                    supportingText = {
                        if (error != null) {
                            Text(error)
                        }
                    },
                    textStyle = MaterialTheme.typography.titleLarge.copy(fontFamily = FontFamily.Monospace),
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Characters,
                        keyboardType = KeyboardType.Ascii,
                        imeAction = ImeAction.Done,
                    ),
                    keyboardActions = KeyboardActions(
                        onDone = { if (code.length == 6 && !submitting) onConfirm() },
                    ),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                enabled = code.length == 6 && !submitting,
            ) {
                if (submitting) {
                    WorkingIndicator()
                } else {
                    Text(stringResource(R.string.confirm))
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !submitting) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}

private fun controlModeLabel(mode: ControlMode): Int {
    return when (mode) {
        ControlMode.BUTTONS -> R.string.control_mode_buttons
        ControlMode.DPAD -> R.string.control_mode_dpad
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
private fun WorkingIndicator() {
    val description = stringResource(R.string.working)
    CircularProgressIndicator(
        modifier = Modifier
            .size(20.dp)
            .semantics { contentDescription = description },
        strokeWidth = 2.dp,
    )
}

@Composable
private fun PairingStatus(state: SettingsUiState): String {
    return when (val pairing = state.pairingState) {
        PairingState.Connecting -> stringResource(R.string.status_connecting)
        PairingState.WaitingForCode, PairingState.SubmittingCode ->
            stringResource(R.string.status_waiting_for_code)
        is PairingState.Failed -> pairing.message
        PairingState.Succeeded, PairingState.Idle -> if (state.isPaired) {
            stringResource(R.string.status_paired)
        } else {
            stringResource(R.string.status_not_paired)
        }
    }
}
