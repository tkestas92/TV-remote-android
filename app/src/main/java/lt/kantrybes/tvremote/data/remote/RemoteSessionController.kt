package lt.kantrybes.tvremote.data.remote

import android.app.Application
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
import java.util.concurrent.atomic.AtomicInteger

/**
 * One TLS session to port 6466 for the whole process. The process lifecycle
 * stops it when the app is backgrounded and opens a fresh one, with no backoff,
 * when the app returns.
 */
internal class RemoteSessionController(
    application: Application,
    private val scope: CoroutineScope,
) : DefaultLifecycleObserver {
    private val settings = SettingsRepository(application)
    private val certificates = ClientCertificateStore(File(application.filesDir, "remote-identity"))
    private val gate = Mutex()
    private val generation = AtomicInteger(0)

    private val _state = MutableStateFlow<RemoteConnectionState>(RemoteConnectionState.Disconnected)
    val state: StateFlow<RemoteConnectionState> = _state.asStateFlow()

    @Volatile private var session: RemoteSession? = null
    private var sessionHost: String? = null
    private var sessionEpoch = -1
    private var collectJob: kotlinx.coroutines.Job? = null
    @Volatile private var wanted = false
    @Volatile private var processStarted = false
    private var epoch = 0

    init {
        val lifecycle = ProcessLifecycleOwner.get().lifecycle
        lifecycle.addObserver(this)
        processStarted = lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
    }

    fun ensureRunning() {
        wanted = true
        if (processStarted && _state.value is RemoteConnectionState.Disconnected) {
            _state.value = RemoteConnectionState.Connecting
        }
        scheduleApply()
    }

    fun stop() {
        wanted = false
        generation.incrementAndGet()
        if (_state.value !is RemoteConnectionState.NeedsPairing) {
            _state.value = RemoteConnectionState.Disconnected
        }
        scheduleApply()
    }

    override fun onStart(owner: LifecycleOwner) {
        processStarted = true
        epoch++
        if (!wanted) return
        if (_state.value is RemoteConnectionState.Disconnected) {
            _state.value = RemoteConnectionState.Connecting
        }
        Log.i(TAG, "App foregrounded, reconnecting immediately")
        scheduleApply()
    }

    override fun onStop(owner: LifecycleOwner) {
        processStarted = false
        generation.incrementAndGet()
        if (_state.value !is RemoteConnectionState.NeedsPairing) {
            _state.value = RemoteConnectionState.Disconnected
        }
        Log.i(TAG, "App backgrounded, stopping remote session")
        scheduleApply()
    }

    fun sendKey(key: TvKey, press: KeyPress) {
        session?.sendKey(key, press)
    }

    suspend fun sendKeyNow(key: TvKey, press: KeyPress) {
        session?.sendKeyNow(key, press)
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
            _state.value = RemoteConnectionState.NeedsPairing
            return
        }
        if (session?.isRunning == true && sessionEpoch == epoch && sessionHost == host) return
        shutdownLocked()
        if (!wanted || !processStarted) return
        val created = RemoteSession(host, identity, scope)
        session = created
        sessionHost = host
        sessionEpoch = epoch
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

    private companion object {
        const val TAG = "TvRemoteSession"
    }
}
