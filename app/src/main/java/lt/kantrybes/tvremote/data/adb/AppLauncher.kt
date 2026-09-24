package lt.kantrybes.tvremote.data.adb

import android.util.Log
import dadb.AdbAuthException
import dadb.AdbConnectionClosedException
import dadb.Dadb
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import lt.kantrybes.tvremote.data.settings.AppShortcut
import lt.kantrybes.tvremote.data.settings.Shortcuts

/**
 * Launches a TV app. App links stay implemented but are used only when
 * [supportsAppLinks] is true for this TV. Otherwise monkey runs on the warm ADB connection.
 */
internal class AppLauncher(private val adb: AdbClient) {
    suspend fun openPictureMenu(
        host: String,
        onWaiting: () -> Unit,
        onClear: () -> Unit,
        onError: () -> Unit,
    ) {
        val startedAt = System.currentTimeMillis()
        if (host.isBlank()) {
            Log.w(TAG, "No TV address for the picture menu")
            onError()
            return
        }
        if (connect(host, onWaiting, onClear, onError) == null) return
        val output = try {
            shellRetrying(host, PictureMenu.COMMAND)
        } catch (error: CancellationException) {
            throw error
        } catch (_: AdbAuthException) {
            onWaiting()
            Log.w(TAG, "ADB authorization pending for the picture menu")
            return
        } catch (error: Exception) {
            Log.w(TAG, "Picture menu failed", error)
            onError()
            return
        }
        val elapsed = System.currentTimeMillis() - startedAt
        if (PictureMenu.started(output)) {
            Log.i(TAG, "Picture menu opened in $elapsed ms")
        } else {
            Log.w(TAG, "Picture menu failed in $elapsed ms: ${output.trim()}")
            onError()
        }
    }

    suspend fun typeAscii(
        host: String,
        chunk: String,
        onWaiting: () -> Unit,
        onClear: () -> Unit,
        onError: () -> Unit,
    ): Boolean {
        val command = TextSync.inputCommand(chunk) ?: return false
        if (host.isBlank()) {
            onError()
            return false
        }
        if (connect(host, onWaiting, onClear, onError) == null) return false
        return try {
            shellRetrying(host, command)
            true
        } catch (error: CancellationException) {
            throw error
        } catch (_: AdbAuthException) {
            onWaiting()
            false
        } catch (error: Exception) {
            Log.w(TAG, "ADB text failed", error)
            onError()
            false
        }
    }

    suspend fun openYouTube(host: String, url: String, startedAt: Long): Boolean {
        if (host.isBlank()) {
            Log.w(TAG, "No TV address for share")
            return false
        }
        val output = try {
            if (connect(host, onWaiting = {}, onClear = {}, onError = {}) == null) {
                Log.w(TAG, "Share failed: ADB not connected")
                return false
            }
            shellRetrying(host, YouTubeShare.command(url))
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Log.w(TAG, "Share failed", error)
            return false
        }
        val elapsed = System.currentTimeMillis() - startedAt
        return if (YouTubeShare.started(output)) {
            Log.i(TAG, "Share launched in $elapsed ms")
            true
        } else {
            Log.w(TAG, "Share failed in $elapsed ms: ${output.trim()}")
            false
        }
    }

    suspend fun ensureConnected(
        host: String,
        onWaiting: () -> Unit,
        onClear: () -> Unit,
        onError: () -> Unit,
    ) {
        if (host.isBlank() || adb.isReady(host)) return
        connect(host, onWaiting, onClear, onError)
    }

    suspend fun launch(
        shortcut: AppShortcut,
        host: String,
        supportsAppLinks: Boolean,
        sendAppLink: () -> Boolean,
        onWaiting: () -> Unit,
        onClear: () -> Unit,
        onError: () -> Unit,
    ) {
        try {
            launchOnce(shortcut, host, supportsAppLinks, sendAppLink, onWaiting, onClear, onError)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Log.e(TAG, "Launch failed for ${shortcut.packageName}", error)
            onError()
        }
    }

    private suspend fun launchOnce(
        shortcut: AppShortcut,
        host: String,
        supportsAppLinks: Boolean,
        sendAppLink: () -> Boolean,
        onWaiting: () -> Unit,
        onClear: () -> Unit,
        onError: () -> Unit,
    ) {
        if (!Shortcuts.isPackageName(shortcut.packageName)) {
            Log.w(TAG, "Refusing launch of invalid package ${shortcut.packageName}")
            return
        }
        if (host.isBlank()) {
            Log.w(TAG, "No TV address for ${shortcut.packageName}")
            return
        }
        val startedAt = System.currentTimeMillis()
        if (supportsAppLinks) {
            val wasForeground = if (adb.isReady(host)) {
                try {
                    isResumed(host, shortcut.packageName)
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    false
                }
            } else {
                false
            }
            val sent = withContext(Dispatchers.IO) { sendAppLink() }
            val connected = connect(host, onWaiting, onClear, onError) != null
            if (sent && connected && waitUntilResumed(host, shortcut.packageName)) {
                val method = if (wasForeground) "already in foreground after app link" else "launched via app link"
                Log.i(TAG, "${shortcut.label} (${shortcut.packageName}) $method")
                return
            }
            if (!connected) return
            Log.i(TAG, "${shortcut.label} (${shortcut.packageName}) app link ignored, trying ADB")
        }
        if (connect(host, onWaiting, onClear, onError) == null) return
        val output = try {
            runMonkey(host, shortcut.packageName)
        } catch (error: CancellationException) {
            throw error
        } catch (_: AdbAuthException) {
            onWaiting()
            Log.w(TAG, "ADB authorization pending for ${shortcut.packageName}")
            return
        } catch (error: Exception) {
            Log.w(TAG, "ADB monkey failed for ${shortcut.packageName}", error)
            onError()
            return
        }
        val elapsed = System.currentTimeMillis() - startedAt
        if ("Events injected: 1" in output) {
            Log.i(TAG, "${shortcut.label} (${shortcut.packageName}) launched via adb in $elapsed ms")
        } else {
            Log.w(TAG, "ADB monkey did not launch ${shortcut.packageName} in $elapsed ms: ${output.trim()}")
            onError()
        }
    }

    private suspend fun runMonkey(host: String, packageName: String): String {
        return shellRetrying(host, Shortcuts.monkeyCommand(packageName))
    }

    private suspend fun shellRetrying(host: String, command: String): String {
        return try {
            shell(host, command)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Log.w(TAG, "ADB command failed, reconnecting", error)
            adb.drop()
            shell(host, command)
        }
    }

    private suspend fun connect(
        host: String,
        onWaiting: () -> Unit,
        onClear: () -> Unit,
        onError: () -> Unit,
    ): Dadb? {
        if (!adb.isReady(host)) onWaiting()
        return try {
            handshake(host).also { onClear() }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Log.w(TAG, "ADB handshake failed", error)
            adb.drop()
            if (error is AdbAuthException) return null
            if (!isRetryableHandshake(error)) {
                onError()
                return null
            }
            Log.i(TAG, "Retrying ADB handshake once")
            try {
                handshake(host).also { onClear() }
            } catch (retry: CancellationException) {
                throw retry
            } catch (retry: Exception) {
                Log.w(TAG, "ADB handshake retry failed", retry)
                adb.drop()
                if (retry !is AdbAuthException && !isRetryableHandshake(retry)) onError()
                null
            }
        }
    }

    private suspend fun handshake(host: String): Dadb {
        return withContext(Dispatchers.IO) { adb.connection(host) }
    }

    private suspend fun waitUntilResumed(host: String, packageName: String): Boolean {
        return try {
            delay(FIRST_CHECK_MS)
            if (isResumed(host, packageName)) return true
            delay(SECOND_CHECK_MS)
            isResumed(host, packageName)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Log.w(TAG, "Foreground check failed for $packageName", error)
            false
        }
    }

    private suspend fun isResumed(host: String, packageName: String, dadb: Dadb? = null): Boolean {
        val dump = shell(host, FOREGROUND_DUMP, dadb)
        return ForegroundApp.isPackageResumed(dump, packageName)
    }

    private suspend fun shell(host: String, command: String, existing: Dadb? = null): String {
        return try {
            withContext(Dispatchers.IO) {
                AdbShell.exec(existing ?: adb.connection(host), command)
            }
        } catch (_: AdbConnectionClosedException) {
            adb.drop()
            withContext(Dispatchers.IO) {
                AdbShell.exec(adb.connection(host), command)
            }
        }
    }

    private companion object {
        const val TAG = "TvRemoteLaunch"
        const val FIRST_CHECK_MS = 1_200L
        const val SECOND_CHECK_MS = 1_200L
        const val FOREGROUND_DUMP = "dumpsys activity activities"
    }
}
