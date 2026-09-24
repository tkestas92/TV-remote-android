package lt.kantrybes.tvremote.ui.remote

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import lt.kantrybes.tvremote.R
import lt.kantrybes.tvremote.TvRemoteApplication
import lt.kantrybes.tvremote.data.adb.AppLauncher
import lt.kantrybes.tvremote.data.adb.TextSync
import lt.kantrybes.tvremote.data.remote.RemoteConnectionState
import lt.kantrybes.tvremote.data.remote.KeyPress
import lt.kantrybes.tvremote.data.remote.TvKey
import lt.kantrybes.tvremote.data.settings.AppShortcut
import lt.kantrybes.tvremote.data.settings.SettingsRepository
import lt.kantrybes.tvremote.data.settings.Shortcuts
import java.io.IOException
import java.util.concurrent.atomic.AtomicReference

internal data class AdbMessage(
    val text: String,
    val waiting: Boolean,
)

internal class RemoteViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as TvRemoteApplication
    private val settings = SettingsRepository(application)
    private val remote = app.remote
    private val launcher = AppLauncher(app.adb)
    private val launches = Mutex()
    private var screenVisible = false
    private val processObserver = object : DefaultLifecycleObserver {
        override fun onStart(owner: LifecycleOwner) {
            if (screenVisible) warmAdb()
        }
    }

    val connection: StateFlow<RemoteConnectionState> = remote.state

    private val _shortcuts = MutableStateFlow(Shortcuts.defaults)
    val shortcuts: StateFlow<List<AppShortcut>> = _shortcuts.asStateFlow()

    private val _showPictureMenu = MutableStateFlow(true)
    val showPictureMenu: StateFlow<Boolean> = _showPictureMenu.asStateFlow()

    private val _adbMessage = MutableStateFlow<AdbMessage?>(null)
    val adbMessage: StateFlow<AdbMessage?> = _adbMessage.asStateFlow()

    private val _keyboardHint = MutableStateFlow(false)
    val keyboardHint: StateFlow<Boolean> = _keyboardHint.asStateFlow()

    private val keyboardLock = Mutex()
    private val pendingKeyboard = AtomicReference<String?>(null)
    private var keyboardCommitted = ""
    private var imeIgnored = false

    private val crashGuard = CoroutineExceptionHandler { _, error ->
        if (error is CancellationException) return@CoroutineExceptionHandler
        Log.e(TAG, "Uncaught coroutine error", error)
        if (error is IOException) {
            _adbMessage.value = AdbMessage(failedText(), waiting = false)
        }
    }

    init {
        ProcessLifecycleOwner.get().lifecycle.addObserver(processObserver)
        launchGuarded {
            settings.shortcuts().collect { _shortcuts.value = it }
        }
        launchGuarded {
            settings.showPictureMenuButton().collect { _showPictureMenu.value = it }
        }
    }

    fun start() {
        screenVisible = true
        remote.ensureRunning()
        warmAdb()
    }

    fun stop() {
        screenVisible = false
        remote.stop()
    }

    fun sendKey(key: TvKey, press: KeyPress) {
        remote.sendKey(key, press)
    }

    fun launchShortcut(shortcut: AppShortcut) {
        launchGuarded {
            if (!launches.tryLock()) return@launchGuarded
            try {
                val host = settings.read().pairedHost
                launcher.launch(
                    shortcut = shortcut,
                    host = host,
                    supportsAppLinks = settings.supportsAppLinks(host),
                    sendAppLink = { remote.sendAppLink(shortcut.packageName) },
                    onWaiting = {
                        _adbMessage.value = AdbMessage(allowText(), waiting = true)
                    },
                    onClear = { _adbMessage.value = null },
                    onError = {
                        _adbMessage.value = AdbMessage(failedText(), waiting = false)
                    },
                )
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Log.e(TAG, "Shortcut launch failed", error)
                _adbMessage.value = AdbMessage(failedText(), waiting = false)
            } finally {
                launches.unlock()
            }
        }
    }

    fun beginKeyboard() {
        keyboardCommitted = ""
        imeIgnored = false
        pendingKeyboard.set(null)
        _keyboardHint.value = false
    }

    fun syncKeyboard(text: String) {
        pendingKeyboard.set(text)
        launchGuarded {
            keyboardLock.withLock {
                while (true) {
                    val next = pendingKeyboard.getAndSet(null) ?: return@withLock
                    syncKeyboardLocked(next)
                }
            }
        }
    }

    fun sendEnter() {
        launchGuarded {
            keyboardLock.withLock {
                remote.sendKeyNow(TvKey.ENTER, KeyPress.SHORT)
            }
        }
    }

    private suspend fun syncKeyboardLocked(text: String) {
        if (pendingKeyboard.get() != null) return
        if (!imeIgnored) {
            val token = remote.imeEpoch()
            val sent = remote.sendImeText(text)
            if (sent) {
                val confirmed = if (text.isEmpty()) {
                    true
                } else {
                    remote.awaitImeSince(token, IME_ACK_MS)
                }
                if (confirmed || text.isEmpty()) {
                    keyboardCommitted = text
                    return
                }
                Log.i(TAG, "IME ignored, falling back to ADB")
                imeIgnored = true
                _keyboardHint.value = true
                keyboardCommitted = ""
                if (pendingKeyboard.get() != null) return
            } else if (remote.imeAvailable()) {
                return
            } else if (text.isNotEmpty() && connection.value is RemoteConnectionState.Connected) {
                Log.i(TAG, "IME unavailable, falling back to ADB")
                imeIgnored = true
                _keyboardHint.value = true
            } else {
                return
            }
        }
        applyAdbText(text)
    }

    private suspend fun applyAdbText(text: String) {
        val plan = TextSync.plan(keyboardCommitted, text)
        if (plan.needsIme) _keyboardHint.value = true
        if (plan.deletes > 0) {
            repeat(plan.deletes) {
                remote.sendKeyNow(TvKey.DEL, KeyPress.SHORT)
            }
            keyboardCommitted = keyboardCommitted.dropLast(
                plan.deletes.coerceAtMost(keyboardCommitted.length),
            )
        }
        val append = plan.append
        if (append != null) {
            val host = settings.read().pairedHost
            val typed = launcher.typeAscii(
                host = host,
                chunk = append,
                onWaiting = { _adbMessage.value = AdbMessage(allowText(), waiting = true) },
                onClear = { _adbMessage.value = null },
                onError = { _adbMessage.value = AdbMessage(failedText(), waiting = false) },
            )
            if (!typed) return
            keyboardCommitted += append
        }
    }

    fun openPictureMenu() {
        launchGuarded {
            val host = settings.read().pairedHost
            launcher.openPictureMenu(
                host = host,
                onWaiting = { _adbMessage.value = AdbMessage(allowText(), waiting = true) },
                onClear = { _adbMessage.value = null },
                onError = { _adbMessage.value = AdbMessage(pictureMenuFailedText(), waiting = false) },
            )
        }
    }

    fun updateShortcut(index: Int, label: String, packageName: String) {
        val shortcut = AppShortcut(label.trim(), packageName.trim())
        if (!Shortcuts.isValid(shortcut)) return
        launchGuarded { settings.setShortcut(index, shortcut) }
    }

    private fun warmAdb() {
        launchGuarded {
            val host = settings.read().pairedHost
            launcher.ensureConnected(
                host = host,
                onWaiting = { _adbMessage.value = AdbMessage(allowText(), waiting = true) },
                onClear = { _adbMessage.value = null },
                onError = { _adbMessage.value = AdbMessage(failedText(), waiting = false) },
            )
        }
    }

    override fun onCleared() {
        ProcessLifecycleOwner.get().lifecycle.removeObserver(processObserver)
    }

    private fun launchGuarded(block: suspend () -> Unit): Job {
        return viewModelScope.launch(crashGuard) {
            try {
                block()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Log.e(TAG, "View model coroutine failed", error)
            }
        }
    }

    private fun allowText(): String {
        return getApplication<Application>().getString(R.string.adb_auth_pending)
    }

    private fun failedText(): String {
        return getApplication<Application>().getString(R.string.adb_failed)
    }

    private fun pictureMenuFailedText(): String {
        return getApplication<Application>().getString(R.string.picture_menu_failed)
    }

    private companion object {
        const val TAG = "TvRemote"
        const val IME_ACK_MS = 800L
    }
}
