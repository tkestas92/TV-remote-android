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
import lt.kantrybes.tvremote.data.settings.SettingsRepository
import lt.kantrybes.tvremote.data.settings.Shortcuts

/**
 * Launches a TV app. App links stay implemented but are used only when
 * [supportsAppLinks] is true for this TV. Otherwise the cached launcher
 * activity is started with `am start`, and monkey is only the fallback.
 */
internal class AppLauncher(
    private val adb: AdbClient,
    private val settings: SettingsRepository? = null,
) {
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

    /** Resolves any shortcut that is not already cached. Safe to run after ADB is warm. */
    suspend fun prefetch(host: String, packages: List<String>) {
        val store = settings ?: return
        if (host.isBlank() || packages.isEmpty()) return
        if (connect(host, onWaiting = {}, onClear = {}, onError = {}) == null) return
        for (packageName in packages) {
            if (!Shortcuts.isPackageName(packageName)) continue
            if (store.launcherComponent(packageName) != null) continue
            val component = try {
                resolve(host, packageName)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Log.w(TAG, "Resolve failed for $packageName: ${error.message}")
                continue
            } ?: continue
            store.setLauncherComponent(packageName, component)
            Log.i(TAG, "Resolved $packageName to $component")
        }
    }

    /** @return true when the TV app was started. */
    suspend fun launch(
        shortcut: AppShortcut,
        host: String,
        supportsAppLinks: Boolean,
        sendAppLink: () -> Boolean,
        onWaiting: () -> Unit,
        onClear: () -> Unit,
        onError: () -> Unit,
    ): Boolean {
        try {
            return launchOnce(shortcut, host, supportsAppLinks, sendAppLink, onWaiting, onClear, onError)
        } catch (error: CancellationException) {
            throw error
        } catch (_: AdbNeedsAuth) {
            return false
        } catch (error: Exception) {
            Log.e(TAG, "Launch failed for ${shortcut.packageName}", error)
            onError()
            return false
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
    ): Boolean {
        if (!Shortcuts.isPackageName(shortcut.packageName)) {
            Log.w(TAG, "Refusing launch of invalid package ${shortcut.packageName}")
            return false
        }
        if (host.isBlank()) {
            Log.w(TAG, "No TV address for ${shortcut.packageName}")
            return false
        }
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
                return true
            }
            if (!connected) return false
            Log.i(TAG, "${shortcut.label} (${shortcut.packageName}) app link ignored, trying ADB")
        }
        if (connect(host, onWaiting, onClear, onError) == null) return false
        if (startWithActivity(shortcut, host, onWaiting)) return true
        return startWithMonkey(shortcut, host, onWaiting, onError)
    }

    private suspend fun startWithActivity(
        shortcut: AppShortcut,
        host: String,
        onWaiting: () -> Unit,
    ): Boolean {
        val store = settings ?: return false
        val cached = store.launcherComponent(shortcut.packageName)
        val component = cached ?: resolveAndStore(host, shortcut.packageName) ?: return false
        if (startComponent(host, shortcut, component, onWaiting)) return true
        store.clearLauncherComponent(shortcut.packageName)
        val fresh = resolveAndStore(host, shortcut.packageName) ?: return false
        if (fresh == component) {
            store.clearLauncherComponent(shortcut.packageName)
            return false
        }
        return startComponent(host, shortcut, fresh, onWaiting).also { started ->
            if (!started) store.clearLauncherComponent(shortcut.packageName)
        }
    }

    private suspend fun startComponent(
        host: String,
        shortcut: AppShortcut,
        component: String,
        onWaiting: () -> Unit,
    ): Boolean {
        val startedAt = System.nanoTime()
        val output = try {
            shellRetrying(host, LauncherActivity.startCommand(component))
        } catch (error: CancellationException) {
            throw error
        } catch (_: AdbAuthException) {
            onWaiting()
            Log.w(TAG, "ADB authorization pending for ${shortcut.packageName}")
            throw AdbNeedsAuth()
        } catch (error: Exception) {
            Log.w(TAG, "am start failed for ${shortcut.packageName}", error)
            return false
        }
        if (LauncherActivity.startFailed(output)) {
            Log.w(TAG, "am start failed for $component: ${output.trim()}")
            return false
        }
        val elapsed = (System.nanoTime() - startedAt) / 1_000_000
        Log.i(TAG, "${shortcut.label} launched via am in $elapsed ms")
        return true
    }

    private suspend fun startWithMonkey(
        shortcut: AppShortcut,
        host: String,
        onWaiting: () -> Unit,
        onError: () -> Unit,
    ): Boolean {
        val startedAt = System.nanoTime()
        val output = try {
            runMonkey(host, shortcut.packageName)
        } catch (error: CancellationException) {
            throw error
        } catch (_: AdbAuthException) {
            onWaiting()
            Log.w(TAG, "ADB authorization pending for ${shortcut.packageName}")
            return false
        } catch (error: Exception) {
            Log.w(TAG, "ADB monkey failed for ${shortcut.packageName}", error)
            onError()
            return false
        }
        val elapsed = (System.nanoTime() - startedAt) / 1_000_000
        return if ("Events injected: 1" in output) {
            Log.i(TAG, "${shortcut.label} launched via monkey fallback in $elapsed ms")
            true
        } else {
            Log.w(TAG, "ADB monkey did not launch ${shortcut.packageName} in $elapsed ms: ${output.trim()}")
            onError()
            false
        }
    }

    private suspend fun resolveAndStore(host: String, packageName: String): String? {
        val store = settings ?: return null
        val component = resolve(host, packageName) ?: return null
        store.setLauncherComponent(packageName, component)
        return component
    }

    private suspend fun resolve(host: String, packageName: String): String? {
        val leanback = shellRetrying(host, LauncherActivity.resolveCommand(packageName, LauncherActivity.LEANBACK))
        LauncherActivity.componentFromResolve(leanback, packageName)?.let { return it }
        val launcher = shellRetrying(host, LauncherActivity.resolveCommand(packageName, LauncherActivity.LAUNCHER))
        return LauncherActivity.componentFromResolve(launcher, packageName)
    }

    /** Runs one shell command on the warm ADB connection. Null when the TV cannot be reached. */
    internal suspend fun exec(host: String, command: String): String? {
        if (host.isBlank()) return null
        if (connect(host, onWaiting = {}, onClear = {}, onError = {}) == null) return null
        return try {
            shellRetrying(host, command)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Log.w(TAG, "ADB command failed", error)
            null
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
        val waiting = !adb.isReady(host)
        if (waiting) onWaiting()
        return try {
            handshake(host).also { if (waiting) onClear() }
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
        return withContext(adb.dispatcher) { adb.connection(host) }
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
            withContext(adb.dispatcher) {
                AdbShell.exec(existing ?: adb.connection(host), command)
            }
        } catch (_: AdbConnectionClosedException) {
            adb.drop()
            withContext(adb.dispatcher) {
                AdbShell.exec(adb.connection(host), command)
            }
        }
    }

    private class AdbNeedsAuth : Exception()

    private companion object {
        const val TAG = "TvRemoteLaunch"
        const val FIRST_CHECK_MS = 1_200L
        const val SECOND_CHECK_MS = 1_200L
        const val FOREGROUND_DUMP = "dumpsys activity activities"
    }
}
