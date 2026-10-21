package lt.kantrybes.tvremote.ui.remote

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import lt.kantrybes.tvremote.R
import lt.kantrybes.tvremote.TvRemoteApplication
import lt.kantrybes.tvremote.data.adb.TextSync
import lt.kantrybes.tvremote.data.remote.RemoteConnectionState
import lt.kantrybes.tvremote.data.remote.KeyPress
import lt.kantrybes.tvremote.data.remote.TvKey
import lt.kantrybes.tvremote.data.settings.AppShortcut
import lt.kantrybes.tvremote.data.settings.ControlMode
import lt.kantrybes.tvremote.data.settings.PictureSlider
import lt.kantrybes.tvremote.data.settings.PictureSliderPref
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
    private val launcher = app.adb
    private val openingTicket = java.util.concurrent.atomic.AtomicInteger(0)
    private val launches = Mutex()

    val connection: StateFlow<RemoteConnectionState> = remote.state

    private val _shortcuts = MutableStateFlow(Shortcuts.defaults)
    val shortcuts: StateFlow<List<AppShortcut>> = _shortcuts.asStateFlow()

    private val _showPictureMenu = MutableStateFlow(true)
    val showPictureMenu: StateFlow<Boolean> = _showPictureMenu.asStateFlow()

    private val _controlMode = MutableStateFlow(ControlMode.DPAD)
    val controlMode: StateFlow<ControlMode> = _controlMode.asStateFlow()

    private val _pictureSliders = MutableStateFlow(defaultPictureSliders())
    val pictureSliders: StateFlow<List<PictureSliderPref>> = _pictureSliders.asStateFlow()
    private val pictureLock = Mutex()
    private val pictureApplies = mutableMapOf<String, Int>()
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
        launchGuarded {
            settings.shortcuts().collect { _shortcuts.value = it }
        }
        launchGuarded {
            settings.migrateControlMode()
            settings.controlMode().collect { _controlMode.value = it }
        }
        launchGuarded {
            settings.showPictureMenuButton().collect { _showPictureMenu.value = it }
        }
        launchGuarded {
            settings.pictureSliders().collect { stored ->
                _pictureSliders.value = stored.map { slider ->
                    if ((pictureApplies[slider.item] ?: 0) > 0) {
                        val current = _pictureSliders.value.firstOrNull { it.item == slider.item }
                        if (current != null) slider.copy(value = current.value) else slider
                    } else {
                        slider
                    }
                }
            }
        }
    }

    fun start() {
        remote.ensureRunning()
    }

    fun sendKey(key: TvKey, press: KeyPress) {
        remote.sendKey(key, press)
    }

    fun launchShortcut(shortcut: AppShortcut) {
        val opening = getApplication<Application>().getString(R.string.shortcut_opening, shortcut.label)
        val ticket = openingTicket.incrementAndGet()
        _adbMessage.value = AdbMessage(opening, waiting = true)
        launchGuarded {
            launches.withLock {
                val host = settings.read().pairedHost
                val launched = launcher.launch(
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
                if (launched && openingTicket.get() == ticket) {
                    _adbMessage.value = null
                }
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

    fun setPictureValue(item: String, value: Int) {
        if (!PictureSlider.isItem(item)) return
        val clamped = value.coerceIn(0, 100)
        val previous = _pictureSliders.value.firstOrNull { it.item == item }?.value ?: clamped
        pictureApplies[item] = (pictureApplies[item] ?: 0) + 1
        restorePictureValue(item, clamped)
        launchGuarded {
            try {
                pictureLock.withLock {
                    settings.setPictureValue(item, clamped)
                    val applied = launcher.setPicture(item, clamped)
                    if (!applied) {
                        settings.setPictureValue(item, previous)
                        restorePictureValue(item, previous)
                        _adbMessage.value = AdbMessage(pictureFailedText(), waiting = false)
                    }
                }
            } finally {
                val remaining = (pictureApplies[item] ?: 1) - 1
                if (remaining <= 0) pictureApplies.remove(item) else pictureApplies[item] = remaining
            }
        }
    }

    private fun restorePictureValue(item: String, value: Int) {
        _pictureSliders.value = _pictureSliders.value.map { slider ->
            if (slider.item == item) slider.copy(value = value) else slider
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

    private fun launchGuarded(block: suspend () -> Unit): Job {
        return viewModelScope.launch(crashGuard + Dispatchers.IO) {
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

    private fun pictureFailedText(): String {
        return getApplication<Application>().getString(R.string.picture_failed)
    }

    private companion object {
        const val TAG = "TvRemote"
        const val IME_ACK_MS = 800L

        fun defaultPictureSliders(): List<PictureSliderPref> {
            return PictureSlider.items.map { item ->
                PictureSliderPref(
                    item = item,
                    shown = PictureSlider.shownByDefault(item),
                    value = 50,
                )
            }
        }
    }
}
