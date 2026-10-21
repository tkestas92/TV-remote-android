package lt.kantrybes.tvremote.data.remote

import android.app.Application
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import lt.kantrybes.tvremote.data.settings.SettingsRepository
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * One TLS session to port 6466 for the whole process. The process lifecycle
 * stops it when the app is backgrounded, unless the media card is keeping the
 * session and ADB connection alive. Foreground and Wi-Fi availability cancel
 * any in-flight connect and start a fresh one immediately.
 */
internal class RemoteSessionController(
    application: Application,
    private val scope: CoroutineScope,
    private val onSessionReady: (String) -> Unit = {},
    private val onForegroundStop: () -> Unit = {},
) : DefaultLifecycleObserver {
    private val settings = SettingsRepository(application)
    private val certificates = ClientCertificateStore(File(application.filesDir, "remote-identity"))
    private val wifi = WifiNetworks()
    private val connectivity = application.getSystemService(ConnectivityManager::class.java)
    private val gate = Mutex()
    private val generation = AtomicInteger(0)
    private val pendingPower = AtomicBoolean(false)

    private val _state = MutableStateFlow<RemoteConnectionState>(RemoteConnectionState.Disconnected)
    val state: StateFlow<RemoteConnectionState> = _state.asStateFlow()

    @Volatile private var session: RemoteSession? = null
    private var sessionHost: String? = null
    private var sessionEpoch = -1
    private val epoch = AtomicInteger(0)
    private var collectJob: kotlinx.coroutines.Job? = null
    @Volatile private var wanted = false
    @Volatile private var credentialsRejected = false
    @Volatile private var processStarted = false
    @Volatile private var keepAlive = false

    internal val processInForeground: Boolean
        get() = processStarted

    private val wifiCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            val changed = wifi.network != network
            wifi.network = network
            restartNow(force = changed)
        }

        override fun onLost(network: Network) {
            if (wifi.network == network) wifi.network = null
        }
    }

    init {
        val lifecycle = ProcessLifecycleOwner.get().lifecycle
        lifecycle.addObserver(this)
        processStarted = lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
        scope.launch {
            rememberCurrentWifi()
            val request = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .build()
            connectivity.registerNetworkCallback(request, wifiCallback)
        }
    }

    fun ensureRunning() {
        credentialsRejected = false
        wanted = true
        if (processStarted && _state.value is RemoteConnectionState.Disconnected) {
            _state.value = RemoteConnectionState.Connecting
        }
        scheduleApply()
    }

    fun stop() {
        wanted = false
        if (keepAlive) return
        generation.incrementAndGet()
        if (_state.value !is RemoteConnectionState.NeedsPairing) {
            _state.value = RemoteConnectionState.Disconnected
        }
        scheduleApply()
    }

    fun setMediaKeepAlive(keep: Boolean) {
        val was = keepAlive
        keepAlive = keep
        if (was && !keep) {
            if (!processStarted) onForegroundStop()
            scheduleApply()
        }
    }

    override fun onStart(owner: LifecycleOwner) {
        processStarted = true
        if (keepAlive && session?.isRunning == true) {
            Log.i(TAG, "App foregrounded, media session already up")
            return
        }
        epoch.incrementAndGet()
        if (!wanted) return
        if (_state.value is RemoteConnectionState.Disconnected) {
            _state.value = RemoteConnectionState.Connecting
        }
        Log.i(TAG, "App foregrounded, reconnecting immediately")
        scheduleApply()
    }

    override fun onStop(owner: LifecycleOwner) {
        processStarted = false
        if (keepAlive) {
            Log.i(TAG, "App backgrounded, media card keeps the session")
            return
        }
        onForegroundStop()
        generation.incrementAndGet()
        if (_state.value !is RemoteConnectionState.NeedsPairing) {
            _state.value = RemoteConnectionState.Disconnected
        }
        Log.i(TAG, "App backgrounded, stopping remote session")
        scheduleApply()
    }

    fun sendKey(key: TvKey, press: KeyPress) {
        if (queuePower(key, press)) return
        session?.sendKey(key, press)
    }

    suspend fun sendKeyNow(key: TvKey, press: KeyPress) {
        if (queuePower(key, press)) return
        session?.sendKeyNow(key, press)
    }

    private fun queuePower(key: TvKey, press: KeyPress): Boolean {
        if (key != TvKey.POWER || session?.isReady == true) return false
        if (press == KeyPress.SHORT || press == KeyPress.START_LONG) pendingPower.set(true)
        return true
    }

    fun imeEpoch(): Int = session?.imeEpoch() ?: 0

    suspend fun sendImeText(text: String): Boolean = session?.sendImeText(text) == true

    fun imeAvailable(): Boolean = session?.hasIme() == true

    suspend fun awaitImeSince(token: Int, timeoutMs: Long): Boolean {
        val current = session ?: return false
        return current.awaitImeSince(token, timeoutMs)
    }

    fun sendAppLink(packageName: String): Boolean = session?.sendAppLink(packageName) == true

    private fun scheduleApply() {
        scope.launch {
            try {
                gate.withLock { applyLocked() }
            } catch (error: kotlinx.coroutines.CancellationException) {
                throw error
            } catch (error: Exception) {
                Log.e(TAG, "Remote session update failed", error)
                if (_state.value !is RemoteConnectionState.NeedsPairing) {
                    _state.value = RemoteConnectionState.Disconnected
                }
            }
        }
    }

    private suspend fun applyLocked() {
        if ((!wanted || !processStarted) && keepAlive && session?.isRunning == true) return
        if (!wanted || !processStarted) {
            shutdownLocked()
            if (_state.value !is RemoteConnectionState.NeedsPairing) {
                _state.value = RemoteConnectionState.Disconnected
            }
            return
        }
        val host = settings.read().pairedHost
        val identity = certificates.loadExisting()
        if (!wanted || !processStarted) {
            shutdownLocked()
            return
        }
        if (host.isBlank() || identity == null) {
            shutdownLocked()
            _state.value = RemoteConnectionState.NeedsPairing()
            return
        }
        if (credentialsRejected) {
            shutdownLocked()
            _state.value = RemoteConnectionState.NeedsPairing(rejected = true)
            return
        }
        if (session?.isRunning == true && sessionEpoch == epoch.get() && sessionHost == host) return
        shutdownLocked()
        if (!wanted || !processStarted) return
        val created = RemoteSession(
            host = host,
            identity = identity,
            scope = scope,
            wifi = wifi,
            macAddress = { WakeOnLan.resolveMac(settings.read().tvMac) },
            consumePendingPower = { pendingPower.compareAndSet(true, false) },
            onReady = { onSessionReady(host) },
            onCredentialsRejected = {
                credentialsRejected = true
                _state.value = RemoteConnectionState.NeedsPairing(rejected = true)
                scheduleApply()
            },
        )
        session = created
        sessionHost = host
        sessionEpoch = epoch.get()
        val token = generation.get()
        collectJob = scope.launch {
            created.state.collect { next ->
                if (token == generation.get()) _state.value = next
            }
        }
        created.start()
    }

    private suspend fun shutdownLocked() {
        generation.incrementAndGet()
        collectJob?.cancel()
        collectJob = null
        val current = session
        session = null
        sessionHost = null
        current?.awaitStopped()
    }

    private fun restartNow(force: Boolean = false) {
        if (!wanted || !processStarted) return
        if (!force && session?.isReady == true) return
        Log.i(TAG, "Wi-Fi available, reconnecting immediately")
        epoch.incrementAndGet()
        scheduleApply()
    }

    private fun rememberCurrentWifi() {
        val active = connectivity.activeNetwork ?: return
        val capabilities = connectivity.getNetworkCapabilities(active) ?: return
        if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
            wifi.network = active
        }
    }

    private companion object {
        const val TAG = "TvRemoteSession"
    }
}
