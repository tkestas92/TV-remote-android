package lt.kantrybes.tvremote.data.adb

import android.util.Log
import dadb.AdbKeyPair
import dadb.AdbStream
import dadb.Dadb
import lt.kantrybes.tvremote.data.remote.TvAddress
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import java.io.File
import java.net.SocketTimeoutException
import java.util.concurrent.Executors

/**
 * One ADB connection to the TV. The RSA key lives in app-private storage,
 * never in the shared ~/.android directory.
 */
internal class AdbClient(private val keyDirectory: File) {
    val dispatcher: CoroutineDispatcher = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "TvRemoteAdb").apply { isDaemon = true }
    }.asCoroutineDispatcher()

    private val lock = Any()
    private var dadb: Dadb? = null
    private var host: String? = null
    private var keyPair: AdbKeyPair? = null

    fun isReady(host: String): Boolean = synchronized(lock) {
        val ip = runCatching { TvAddress.requireIpv4(host) }.getOrNull()
        dadb != null && ip != null && this.host == ip
    }

    /**
     * A second ADB connection for a long-lived stream. The shared connection's
     * read timeout would drop a mirror that sits on a still screen.
     */
    fun openDedicated(host: String, socketTimeoutMs: Int): Dadb {
        val ip = TvAddress.requireIpv4(host)
        val keys = synchronized(lock) { loadKeyPair() }
        return Dadb.create(ip, PORT, keys, CONNECT_TIMEOUT_MS, socketTimeoutMs, true)
    }

    fun connection(host: String, socketTimeoutMs: Int = HANDSHAKE_READ_TIMEOUT_MS): Dadb = synchronized(lock) {
        val ip = TvAddress.requireIpv4(host)
        val existing = dadb
        if (existing != null && this.host == ip) return existing
        try {
            existing?.close()
        } catch (error: Exception) {
            Log.w(TAG, "Closing the previous ADB connection failed", error)
        }
        dadb = null
        this.host = null
        val created = Dadb.create(
            ip,
            PORT,
            loadKeyPair(),
            CONNECT_TIMEOUT_MS,
            socketTimeoutMs,
            true,
        )
        dadb = created
        this.host = ip
        created
    }

    fun drop() = synchronized(lock) {
        try {
            dadb?.close()
        } catch (error: Exception) {
            Log.w(TAG, "Closing ADB connection failed", error)
        }
        dadb = null
        host = null
    }

    private fun loadKeyPair(): AdbKeyPair {
        keyPair?.let { return it }
        val privateKey = File(keyDirectory, "adbkey")
        val publicKey = File(keyDirectory, "adbkey.pub")
        if (privateKey.isFile && privateKey.length() > 0L && publicKey.isFile && publicKey.length() > 0L) {
            Log.i(TAG, "Reusing persisted ADB key")
            return AdbKeyPair.read(privateKey, publicKey).also { keyPair = it }
        }
        if (privateKey.isFile && privateKey.length() > 0L) {
            error("ADB public key is missing; refusing to replace the saved private key")
        }
        Log.i(TAG, "Generating ADB key")
        keyDirectory.mkdirs()
        AdbKeyPair.generate(privateKey, publicKey)
        return AdbKeyPair.read(privateKey, publicKey).also { keyPair = it }
    }

    private companion object {
        const val TAG = "TvRemoteAdb"
        const val PORT = 5555
        const val CONNECT_TIMEOUT_MS = 3_000
        const val HANDSHAKE_READ_TIMEOUT_MS = 5_000
    }
}

internal fun isRetryableHandshake(error: Throwable): Boolean {
    var current: Throwable? = error
    while (current != null) {
        if (current is SocketTimeoutException) return true
        if ("timed out" in current.message.orEmpty().lowercase()) return true
        current = current.cause
    }
    return false
}

internal object AdbShell {
    fun exec(dadb: Dadb, command: String): String {
        return if (dadb.supportsFeature("shell_v2")) {
            dadb.shell(command).allOutput
        } else {
            dadb.open("shell:$command").use { stream -> stream.readText() }
        }
    }

    private fun AdbStream.readText(): String = source.readUtf8()
}
