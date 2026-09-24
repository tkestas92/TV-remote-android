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
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import javax.net.ssl.SSLSocket

internal sealed interface RemoteConnectionState {
    data object Disconnected : RemoteConnectionState
    data object Connecting : RemoteConnectionState
    data object Connected : RemoteConnectionState
    data object Reconnecting : RemoteConnectionState
    data object NeedsPairing : RemoteConnectionState
}

/**
 * Long-lived TLS session to the TV command port. Pings are answered on the reader
 * thread. Dropped connections reconnect with exponential backoff.
 */
internal class RemoteSession(
    private val host: String,
    private val identity: ClientIdentity,
    private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow<RemoteConnectionState>(RemoteConnectionState.Connecting)
    val state: StateFlow<RemoteConnectionState> = _state.asStateFlow()

    private val writeLock = Any()
    private var socket: SSLSocket? = null
    private var output: OutputStream? = null
    @Volatile private var ready = false
    @Volatile private var features = RemoteProtocol.DEFAULT_FEATURES
    @Volatile private var imeCounter = 0
    @Volatile private var imeFieldCounter = 0
    private val imeEpoch = AtomicInteger(0)
    private val lastMessageAt = AtomicLong(0)
    private val closedForLiveness = AtomicBoolean(false)
    private var job: Job? = null
    private var closeJob: Job? = null

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
        if (!ready || features and RemoteProtocol.APP_LINK == 0) return false
        return try {
            write(RemoteProtocol.appLink(packageName))
            true
        } catch (error: IOException) {
            Log.w(TAG, "App link send failed", error)
            false
        }
    }

    fun imeEpoch(): Int = imeEpoch.get()

    fun hasIme(): Boolean = ready && features and RemoteProtocol.IME != 0

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
            if (!ready) return@withContext
            try {
                write(RemoteProtocol.keyInject(key, press))
            } catch (error: IOException) {
                Log.w(TAG, "Key send failed", error)
            }
        }
    }

    fun sendKey(key: TvKey, press: KeyPress) {
        if (!ready) return
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
        var backoffMs = 0L
        var connectedOnce = false
        try {
            while (currentCoroutineContext().isActive) {
                if (backoffMs == 0L) {
                    Log.d(TAG, "Connecting immediately")
                } else {
                    delay(backoffMs)
                }
                if (!currentCoroutineContext().isActive) return
                _state.value = if (connectedOnce) {
                    RemoteConnectionState.Reconnecting
                } else {
                    RemoteConnectionState.Connecting
                }
                ready = false
                features = RemoteProtocol.DEFAULT_FEATURES
                imeCounter = 0
                imeFieldCounter = 0
                try {
                    if (connectAndServe()) {
                        connectedOnce = true
                        backoffMs = 0L
                        continue
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    if (!currentCoroutineContext().isActive) {
                        // Stopped.
                    } else if (closedForLiveness.getAndSet(false)) {
                        Log.i(TAG, "Reconnecting after 60s with no message")
                    } else {
                        Log.w(TAG, "Remote connection lost", error)
                    }
                }
                closeSocket()
                if (!currentCoroutineContext().isActive) return
                backoffMs = if (backoffMs == 0L) {
                    INITIAL_BACKOFF_MS
                } else {
                    (backoffMs * 2).coerceAtMost(MAX_BACKOFF_MS)
                }
            }
        } finally {
            withContext(NonCancellable) {
                closeSocket()
            }
            if (_state.value !is RemoteConnectionState.NeedsPairing) {
                _state.value = RemoteConnectionState.Disconnected
            }
        }
    }

    private fun connectAndServe(): Boolean {
        val connection = try {
            connectTls(
                host = host,
                port = COMMAND_PORT,
                identity = identity,
                connectTimeoutMs = CONNECT_TIMEOUT_MS,
                readTimeoutMs = READ_TIMEOUT_MS,
            )
        } catch (error: CannotConnectException) {
            if (error.kind == CannotConnectException.Kind.TLS) {
                Log.w(TAG, "Command TLS failed; keeping the stored certificate and reconnecting", error)
            }
            throw error
        }
        socket = connection.socket
        synchronized(writeLock) {
            output = connection.socket.outputStream
        }
        Log.d(TAG, "TLS connected for commands")
        lastMessageAt.set(System.currentTimeMillis())
        val watch = scope.launch {
            while (isActive) {
                delay(LIVENESS_POLL_MS)
                val idle = System.currentTimeMillis() - lastMessageAt.get()
                if (idle >= LIVENESS_MS) {
                    Log.i(TAG, "No remote message for ${idle}ms, reconnecting")
                    closedForLiveness.set(true)
                    closeSocket()
                    return@launch
                }
            }
        }
        var becameReady = false
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
                    becameReady = true
                    ready = true
                    _state.value = RemoteConnectionState.Connected
                    Log.d(TAG, "Remote session ready")
                }
                outcome.reply?.let { write(it) }
            }
        } finally {
            watch.cancel()
        }
        return becameReady
    }

    private fun write(message: RemoteMessage) {
        synchronized(writeLock) {
            val current = output ?: return
            current.write(ProtobufFraming.frame(message.toByteArray()))
            current.flush()
        }
    }

    private fun closeSocket() {
        ready = false
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
        const val CONNECT_TIMEOUT_MS = 5_000
        const val READ_TIMEOUT_MS = 0
        const val LIVENESS_MS = 60_000L
        const val LIVENESS_POLL_MS = 5_000L
        const val INITIAL_BACKOFF_MS = 100L
        const val MAX_BACKOFF_MS = 30_000L
    }
}
