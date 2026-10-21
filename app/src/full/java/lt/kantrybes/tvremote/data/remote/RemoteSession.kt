package lt.kantrybes.tvremote.data.remote

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import remote.Remotemessage.RemoteMessage
import java.io.IOException
import java.io.OutputStream
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Long-lived TLS session to the TV command port. Pings are answered on the reader
 * thread. While the app is in the foreground a dropped connection retries every
 * 2 seconds, after a wake-on-LAN packet, until the process is stopped.
 */
internal class RemoteSession(
    private val host: String,
    private val identity: ClientIdentity,
    private val scope: CoroutineScope,
    private val wifi: WifiNetworks,
    private val macAddress: suspend () -> String,
    private val consumePendingPower: () -> Boolean,
    private val onReady: () -> Unit,
    private val onCredentialsRejected: () -> Unit = {},
) {
    private val _state = MutableStateFlow<RemoteConnectionState>(RemoteConnectionState.Connecting)
    val state: StateFlow<RemoteConnectionState> = _state.asStateFlow()

    private val writeLock = Any()
    private var socket: Socket? = null
    private var output: OutputStream? = null
    @Volatile var isReady: Boolean = false
        private set
    @Volatile private var features = RemoteProtocol.DEFAULT_FEATURES
    @Volatile private var imeCounter = 0
    @Volatile private var imeFieldCounter = 0
    private val imeEpoch = AtomicInteger(0)
    private val lastMessageAt = AtomicLong(0)
    private val closedForLiveness = AtomicBoolean(false)
    private var job: Job? = null
    private var closeJob: Job? = null
    private var wakeJob: Job? = null

    val isRunning: Boolean
        get() = job?.isActive == true

    fun start() {
        if (job?.isActive == true) return
        job = scope.launch(Dispatchers.IO) { maintain() }
    }

    /**
     * Cancels the reader and closes the socket on [Dispatchers.IO]. Safe to call from
     * the main thread: the socket is never touched on the caller thread.
     * [scope] must outlive the screen so the close still runs after dispose.
     */
    fun stop() {
        val running = job
        job = null
        running?.cancel()
        closeJob = scope.launch(Dispatchers.IO) {
            withContext(NonCancellable) {
                closeSocket()
                running?.join()
            }
        }
    }

    suspend fun awaitStopped() {
        val running = job
        job = null
        running?.cancel()
        withContext(Dispatchers.IO + NonCancellable) {
            closeSocket()
            closeJob?.join()
            running?.join()
        }
    }

    /** @return true when the app-link message was written on an active session that negotiated app links. */
    fun sendAppLink(packageName: String): Boolean {
        if (!isReady || features and RemoteProtocol.APP_LINK == 0) return false
        return try {
            write(RemoteProtocol.appLink(packageName))
            true
        } catch (error: IOException) {
            Log.w(TAG, "App link send failed", error)
            false
        }
    }

    fun imeEpoch(): Int = imeEpoch.get()

    fun hasIme(): Boolean = isReady && features and RemoteProtocol.IME != 0

    /** @return false when the session is down, IME was not negotiated, or the write failed. */
    suspend fun sendImeText(text: String): Boolean = withContext(Dispatchers.IO) {
        if (!hasIme()) return@withContext false
        try {
            write(RemoteProtocol.imeText(text, imeCounter, imeFieldCounter))
            true
        } catch (error: IOException) {
            Log.w(TAG, "IME send failed", error)
            false
        }
    }

    suspend fun awaitImeSince(token: Int, timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (imeEpoch.get() != token) return true
            delay(40)
        }
        return imeEpoch.get() != token
    }

    suspend fun sendKeyNow(key: TvKey, press: KeyPress) {
        withContext(Dispatchers.IO) {
            if (!isReady) return@withContext
            try {
                write(RemoteProtocol.keyInject(key, press))
            } catch (error: IOException) {
                Log.w(TAG, "Key send failed", error)
            }
        }
    }

    fun sendKey(key: TvKey, press: KeyPress) {
        if (!isReady) return
        val message = RemoteProtocol.keyInject(key, press)
        scope.launch(Dispatchers.IO) {
            try {
                write(message)
            } catch (error: IOException) {
                Log.w(TAG, "Key send failed", error)
            }
        }
    }

    private suspend fun maintain() {
        var attempt = 0
        var connectedOnce = false
        var retryImmediately = false
        try {
            while (currentCoroutineContext().isActive) {
                attempt++
                _state.value = if (connectedOnce) {
                    RemoteConnectionState.Reconnecting
                } else {
                    RemoteConnectionState.Connecting
                }
                isReady = false
                features = RemoteProtocol.DEFAULT_FEATURES
                imeCounter = 0
                imeFieldCounter = 0
                startWaking()
                val wol = sendWakeOnLan()
                val startedAt = System.nanoTime()
                val established = AtomicBoolean(false)
                try {
                    connectAndServe(attempt, startedAt, wol, established)
                    if (_state.value is RemoteConnectionState.NeedsPairing) return
                    connectedOnce = true
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    if (!currentCoroutineContext().isActive) {
                        // Stopped.
                    } else if (!established.get()) {
                        val ms = elapsedMs(startedAt)
                        Log.i(TAG, "attempt $attempt, $wol, connect ${connectResult(error)}/${ms}ms")
                    } else if (closedForLiveness.getAndSet(false)) {
                        Log.i(TAG, "No remote message for ${LIVENESS_MS}ms, reconnecting")
                        retryImmediately = true
                    } else if (error is SocketTimeoutException) {
                        Log.i(TAG, "No remote message for ${LIVENESS_MS}ms, reconnecting")
                        retryImmediately = true
                    } else {
                        Log.w(TAG, "Remote connection lost: ${error.message}")
                        retryImmediately = true
                    }
                }
                closeSocket()
                if (!currentCoroutineContext().isActive) return
                if (retryImmediately) {
                    retryImmediately = false
                } else {
                    delay(RETRY_INTERVAL_MS)
                }
            }
        } finally {
            stopWaking()
            withContext(NonCancellable) {
                closeSocket()
            }
            if (_state.value !is RemoteConnectionState.NeedsPairing) {
                _state.value = RemoteConnectionState.Disconnected
            }
        }
    }

    private fun connectAndServe(
        attempt: Int,
        startedAtNs: Long,
        wol: String,
        established: AtomicBoolean,
    ) {
        val connection = try {
            connectTls(
                host = host,
                port = COMMAND_PORT,
                identity = identity,
                connectTimeoutMs = CONNECT_TIMEOUT_MS,
                handshakeTimeoutMs = HANDSHAKE_TIMEOUT_MS,
                readTimeoutMs = READ_TIMEOUT_MS,
                socketFactory = wifi.socketFactory(),
                onSocket = ::trackSocket,
            )
        } catch (error: CannotConnectException) {
            if (error.kind == CannotConnectException.Kind.TLS && tlsRejectsClientCertificate(error)) {
                Log.w(TAG, "TV rejected the stored certificate")
                _state.value = RemoteConnectionState.NeedsPairing(rejected = true)
                onCredentialsRejected()
                return
            }
            if (error.kind == CannotConnectException.Kind.TLS) {
                Log.w(TAG, "Command TLS failed; keeping the stored certificate and reconnecting")
            }
            throw error
        }
        trackSocket(connection.socket)
        synchronized(writeLock) {
            output = connection.socket.outputStream
        }
        established.set(true)
        Log.i(TAG, "attempt $attempt, $wol, connect ok/${elapsedMs(startedAtNs)}ms")
        lastMessageAt.set(System.currentTimeMillis())
        val watch = scope.launch(Dispatchers.IO) {
            while (isActive) {
                delay(LIVENESS_POLL_MS)
                val idle = System.currentTimeMillis() - lastMessageAt.get()
                if (idle >= LIVENESS_MS) {
                    closedForLiveness.set(true)
                    withContext(NonCancellable) { closeSocket() }
                    return@launch
                }
            }
        }
        try {
            val input = connection.socket.inputStream
            while (true) {
                val payload = ProtobufFraming.readPayload(input)
                val receivedAt = System.currentTimeMillis()
                lastMessageAt.set(receivedAt)
                val incoming = RemoteMessage.parseFrom(payload)
                if (incoming.hasRemotePingRequest()) {
                    Log.i(
                        TAG,
                        "Ping received val1=${incoming.remotePingRequest.val1} at $receivedAt",
                    )
                }
                val outcome = RemoteProtocol.handle(incoming, features)
                features = outcome.features
                outcome.imeCounters?.let { counters ->
                    imeCounter = counters.ime
                    imeFieldCounter = counters.field
                }
                if (outcome.imeActivity) imeEpoch.incrementAndGet()
                if (outcome.sessionReady) {
                    isReady = true
                    stopWaking()
                    _state.value = RemoteConnectionState.Connected
                    Log.i(TAG, "Remote session ready")
                    onReady()
                    if (consumePendingPower()) {
                        write(RemoteProtocol.keyInject(TvKey.POWER, KeyPress.SHORT))
                        Log.i(TAG, "Sent queued power key")
                    }
                }
                outcome.reply?.let { write(it) }
            }
        } finally {
            watch.cancel()
        }
    }

    private fun startWaking() {
        if (wakeJob?.isActive == true) return
        wakeJob = scope.launch(Dispatchers.IO) {
            delay(RETRY_INTERVAL_MS)
            while (isActive) {
                sendWakeOnLan()
                delay(RETRY_INTERVAL_MS)
            }
        }
    }

    private fun stopWaking() {
        wakeJob?.cancel()
        wakeJob = null
    }

    private suspend fun sendWakeOnLan(): String {
        val sent = withContext(Dispatchers.IO) {
            WakeOnLan.send(macAddress()) { socket -> wifi.bind(socket) }
        }
        return if (sent) "WoL sent" else "WoL failed"
    }

    private fun trackSocket(next: Socket) {
        synchronized(writeLock) {
            socket = next
        }
    }

    private fun connectResult(error: Exception): String {
        val kind = (error as? CannotConnectException)?.kind
        return when (kind) {
            CannotConnectException.Kind.TIMEOUT -> "timeout"
            CannotConnectException.Kind.REFUSED -> "refused"
            CannotConnectException.Kind.TLS -> "tls"
            CannotConnectException.Kind.OTHER -> "error"
            null -> if (error is SocketTimeoutException) "timeout" else "error"
        }
    }

    private fun elapsedMs(startedAtNs: Long): Long {
        return (System.nanoTime() - startedAtNs) / 1_000_000
    }

    private fun write(message: RemoteMessage) {
        synchronized(writeLock) {
            val current = output ?: return
            current.write(ProtobufFraming.frame(message.toByteArray()))
            current.flush()
        }
    }

    private fun closeSocket() {
        isReady = false
        val current = synchronized(writeLock) {
            output = null
            val open = socket
            socket = null
            open
        }
        try {
            current?.close()
        } catch (_: IOException) {
        }
    }

    private companion object {
        const val TAG = "TvRemoteSession"
        const val CONNECT_TIMEOUT_MS = 3_000
        const val HANDSHAKE_TIMEOUT_MS = 5_000
        const val READ_TIMEOUT_MS = 12_000
        const val LIVENESS_MS = 12_000L
        const val LIVENESS_POLL_MS = 1_000L
        const val RETRY_INTERVAL_MS = 2_000L
    }
}

internal fun tlsRejectsClientCertificate(error: Throwable): Boolean {
    var current: Throwable? = error
    while (current != null) {
        val message = current.message?.lowercase().orEmpty()
        if (
            message.contains("certificate_unknown") ||
            message.contains("bad_certificate") ||
            message.contains("unknown_ca") ||
            message.contains("certificate unknown") ||
            message.contains("certificate_expired")
        ) {
            return true
        }
        current = current.cause
    }
    return false
}
