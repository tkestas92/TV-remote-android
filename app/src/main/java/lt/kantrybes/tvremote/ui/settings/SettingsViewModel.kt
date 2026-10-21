package lt.kantrybes.tvremote.ui.settings

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import lt.kantrybes.tvremote.BuildConfig
import lt.kantrybes.tvremote.R
import lt.kantrybes.tvremote.TvRemoteApplication
import lt.kantrybes.tvremote.backend.TvPairing
import lt.kantrybes.tvremote.data.remote.CannotConnectException
import lt.kantrybes.tvremote.data.remote.InvalidPairingCodeException
import lt.kantrybes.tvremote.data.remote.InvalidTvAddressException
import lt.kantrybes.tvremote.data.remote.PairingProtocolException
import lt.kantrybes.tvremote.data.remote.RemoteConnectionState
import lt.kantrybes.tvremote.data.remote.TvAddress
import lt.kantrybes.tvremote.data.settings.ControlMode
import lt.kantrybes.tvremote.data.settings.PictureSlider
import lt.kantrybes.tvremote.data.settings.PictureSliderPref
import lt.kantrybes.tvremote.data.settings.SettingsRepository
internal class SettingsViewModel(application: Application) : AndroidViewModel(application) {
    private val settings = SettingsRepository(application)
    private val discovery = (application as TvRemoteApplication).discovery

    private val _state = MutableStateFlow(SettingsUiState())
    val state: StateFlow<SettingsUiState> = _state.asStateFlow()

    private var ipEdited = false
    private var macEdited = false
    private var saveJob: Job? = null
    private var macSaveJob: Job? = null
    private var operation: Job? = null
    private var pairingGeneration = 0
    private var session: TvPairing? = null

    init {
        viewModelScope.launch(Dispatchers.IO) {
            val snapshot = settings.read()
            val hasCertificate = discovery.hasIdentity()
            _state.update { current ->
                current.copy(
                    tvIp = when {
                        BuildConfig.IS_DEMO -> DEMO_HOST
                        ipEdited -> current.tvIp
                        else -> snapshot.tvIp.ifBlank { snapshot.pairedHost }
                    },
                    tvMac = if (macEdited) current.tvMac else snapshot.tvMac,
                    pairedHost = snapshot.pairedHost,
                    hasCertificate = hasCertificate,
                    prefsLoaded = true,
                )
            }
        }
        viewModelScope.launch {
            (application as TvRemoteApplication).remote.state.collect { connection ->
                _state.update { it.copy(remoteConnection = connection) }
            }
        }
        viewModelScope.launch(Dispatchers.IO) {
            settings.migrateControlMode()
            settings.controlMode().collect { mode ->
                _state.update { it.copy(controlMode = mode) }
            }
        }
        viewModelScope.launch(Dispatchers.IO) {
            settings.showPictureMenuButton().collect { show ->
                _state.update { it.copy(showPictureMenuButton = show) }
            }
        }
        viewModelScope.launch(Dispatchers.IO) {
            settings.showMediaControls().collect { show ->
                _state.update { it.copy(showMediaControls = show) }
            }
        }
        viewModelScope.launch(Dispatchers.IO) {
            settings.pictureSliders().collect { sliders ->
                _state.update { it.copy(pictureSliders = sliders) }
            }
        }
    }

    fun setShowPictureMenuButton(show: Boolean) {
        viewModelScope.launch { settings.setShowPictureMenuButton(show) }
    }

    fun setShowMediaControls(show: Boolean) {
        viewModelScope.launch { settings.setShowMediaControls(show) }
    }

    fun setControlMode(mode: ControlMode) {
        viewModelScope.launch { settings.setControlMode(mode) }
    }

    fun setPictureSliderShown(item: String, shown: Boolean) {
        viewModelScope.launch { settings.setPictureSliderShown(item, shown) }
    }

    fun onTvIpChange(value: String) {
        ipEdited = true
        _state.update { current ->
            current.copy(
                tvIp = value,
                testState = TestState.Idle,
                pairingState = if (current.pairingState is PairingState.Failed) {
                    PairingState.Idle
                } else {
                    current.pairingState
                },
            )
        }
        saveJob?.cancel()
        saveJob = viewModelScope.launch {
            delay(SAVE_DEBOUNCE_MS)
            settings.setTvIp(value)
        }
    }

    fun onTvMacChange(value: String) {
        macEdited = true
        _state.update { it.copy(tvMac = value) }
        macSaveJob?.cancel()
        macSaveJob = viewModelScope.launch {
            delay(SAVE_DEBOUNCE_MS)
            settings.setTvMac(value)
        }
    }

    fun testConnection() {
        if (session != null || operation?.isActive == true) return
        operation = viewModelScope.launch {
            _state.update { it.copy(testState = TestState.Running) }
            try {
                flushIp()
                val host = requireHost()
                withContext(Dispatchers.IO) { discovery.test(host) }
                _state.update {
                    it.copy(
                        testState = TestState.Result(
                            success = true,
                            message = if (BuildConfig.IS_DEMO) {
                            getApplication<Application>().getString(R.string.connection_connected_demo)
                        } else {
                            getApplication<Application>().getString(R.string.test_success, host)
                        },
                        ),
                    )
                }
            } catch (error: Exception) {
                Log.w(TAG, "Connection test failed", error)
                _state.update { it.copy(testState = TestState.Result(false, messageFor(error))) }
            }
        }
    }

    fun startPairing() {
        if (session != null || operation?.isActive == true) return
        Log.i(
            TAG,
            "Pairing started from the settings button; certificateExists=${discovery.hasIdentity()}",
        )
        val generation = ++pairingGeneration
        operation = viewModelScope.launch {
            _state.update {
                it.copy(pairingState = PairingState.Connecting, code = "", codeError = null)
            }
            try {
                flushIp()
                val host = requireHost()
                val opened = withContext(Dispatchers.IO) {
                    discovery.openPairing(host).also { it.openAndRequestCode() }
                }
                if (generation != pairingGeneration) {
                    opened.close()
                    return@launch
                }
                session = opened
                _state.update { it.copy(pairingState = PairingState.WaitingForCode) }
            } catch (error: Exception) {
                if (generation != pairingGeneration) return@launch
                Log.w(TAG, "Pairing failed to start", error)
                _state.update { it.copy(pairingState = PairingState.Failed(messageFor(error))) }
            }
        }
    }

    fun onCodeChange(value: String) {
        val filtered = value.uppercase().filter { it.isDigit() || it in 'A'..'F' }.take(6)
        _state.update { it.copy(code = filtered, codeError = null) }
    }

    fun submitCode() {
        val current = session ?: return
        if (operation?.isActive == true) return
        val generation = pairingGeneration
        val code = _state.value.code
        operation = viewModelScope.launch {
            _state.update { it.copy(pairingState = PairingState.SubmittingCode, codeError = null) }
            try {
                withContext(Dispatchers.IO) { current.submitCode(code) }
                if (generation != pairingGeneration) return@launch
                val host = _state.value.tvIp.trim()
                current.serverFingerprint()?.let { fingerprint ->
                    settings.clearServerPin(host)
                    settings.setServerPin(host, fingerprint)
                }
                settings.setPairedHost(host)
                (getApplication() as TvRemoteApplication).remote.ensureRunning()
                session = null
                _state.update {
                    it.copy(
                        pairedHost = host,
                        hasCertificate = true,
                        pairingState = PairingState.Succeeded,
                        code = "",
                        codeError = null,
                    )
                }
            } catch (error: InvalidPairingCodeException) {
                if (generation != pairingGeneration) return@launch
                _state.update {
                    it.copy(
                        pairingState = PairingState.WaitingForCode,
                        codeError = messageFor(error),
                    )
                }
            } catch (error: Exception) {
                if (generation != pairingGeneration) return@launch
                current.close()
                session = null
                Log.w(TAG, "Pairing failed", error)
                _state.update {
                    it.copy(pairingState = PairingState.Failed(messageFor(error)), code = "")
                }
            }
        }
    }

    fun cancelPairing() {
        pairingGeneration++
        session?.close()
        session = null
        _state.update { it.copy(pairingState = PairingState.Idle, code = "", codeError = null) }
    }

    override fun onCleared() {
        session?.close()
        session = null
    }

    private suspend fun flushIp() {
        saveJob?.cancel()
        if (!BuildConfig.IS_DEMO) settings.setTvIp(_state.value.tvIp)
    }

    private fun requireHost(): String {
        return if (BuildConfig.IS_DEMO) DEMO_HOST else TvAddress.requireIpv4(_state.value.tvIp)
    }

    private fun messageFor(error: Exception): String {
        val app = getApplication<Application>()
        return when (error) {
            is InvalidTvAddressException -> when (error.kind) {
                InvalidTvAddressException.Kind.EMPTY -> app.getString(R.string.error_ip_empty)
                InvalidTvAddressException.Kind.INVALID,
                InvalidTvAddressException.Kind.NOT_PRIVATE,
                -> app.getString(R.string.error_ip_invalid)
            }
            is CannotConnectException -> when (error.kind) {
                CannotConnectException.Kind.TIMEOUT -> app.getString(R.string.error_timeout)
                CannotConnectException.Kind.REFUSED -> app.getString(R.string.error_refused)
                CannotConnectException.Kind.TLS -> app.getString(R.string.error_tls)
                CannotConnectException.Kind.OTHER -> app.getString(R.string.error_connect)
            }
            is InvalidPairingCodeException -> when (error.reason) {
                InvalidPairingCodeException.Reason.FORMAT -> app.getString(R.string.error_code_format)
                InvalidPairingCodeException.Reason.MISMATCH -> app.getString(R.string.error_code_mismatch)
            }
            is PairingProtocolException -> when (error.kind) {
                PairingProtocolException.Kind.BAD_SECRET -> app.getString(R.string.error_bad_secret)
                PairingProtocolException.Kind.CLOSED -> app.getString(R.string.error_closed)
                PairingProtocolException.Kind.UNEXPECTED -> app.getString(R.string.error_protocol)
            }
            else -> app.getString(R.string.error_generic)
        }
    }

    private companion object {
        const val TAG = "TvRemoteSettings"
        const val SAVE_DEBOUNCE_MS = 250L
        const val DEMO_HOST = "demo"
    }
}

internal data class SettingsUiState(
    val tvIp: String = "",
    val tvMac: String = "",
    val pairedHost: String = "",
    val hasCertificate: Boolean = false,
    val remoteConnection: RemoteConnectionState = RemoteConnectionState.Disconnected,
    val prefsLoaded: Boolean = false,
    val testState: TestState = TestState.Idle,
    val pairingState: PairingState = PairingState.Idle,
    val code: String = "",
    val codeError: String? = null,
    val showPictureMenuButton: Boolean = true,
    val showMediaControls: Boolean = true,
    val controlMode: ControlMode = ControlMode.DPAD,
    val pictureSliders: List<PictureSliderPref> = PictureSlider.items.map { item ->
        PictureSliderPref(item, PictureSlider.shownByDefault(item), value = 50)
    },
) {
    val isPaired: Boolean
        get() = pairedHost.isNotBlank() && hasCertificate

    val credentialsRejected: Boolean
        get() = (remoteConnection as? RemoteConnectionState.NeedsPairing)?.rejected == true

    val certificateChanged: Boolean
        get() = (remoteConnection as? RemoteConnectionState.NeedsPairing)?.certificateChanged == true

    val pairingInProgress: Boolean
        get() = pairingState is PairingState.Connecting ||
            pairingState is PairingState.WaitingForCode ||
            pairingState is PairingState.SubmittingCode
}

internal sealed interface TestState {
    data object Idle : TestState
    data object Running : TestState
    data class Result(val success: Boolean, val message: String) : TestState
}

internal sealed interface PairingState {
    data object Idle : PairingState
    data object Connecting : PairingState
    data object WaitingForCode : PairingState
    data object SubmittingCode : PairingState
    data object Succeeded : PairingState
    data class Failed(val message: String) : PairingState
}
