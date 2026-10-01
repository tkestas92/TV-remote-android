package lt.kantrybes.tvremote.ui.screen

import android.app.Application
import android.view.Surface
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus
import lt.kantrybes.tvremote.TvRemoteApplication
import lt.kantrybes.tvremote.data.remote.KeyPress
import lt.kantrybes.tvremote.data.remote.RemoteConnectionState
import lt.kantrybes.tvremote.data.remote.TvKey
import lt.kantrybes.tvremote.data.scrcpy.MirrorState
import lt.kantrybes.tvremote.data.scrcpy.ScrcpyMirror
import lt.kantrybes.tvremote.data.settings.SettingsRepository

internal class ScreenViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as TvRemoteApplication
    private val settings = SettingsRepository(application)
    private val remote = app.remote
    private val mirror = ScrcpyMirror(application, app.adb, app.appScope)
    private var visible = false
    private val processObserver = object : DefaultLifecycleObserver {
        override fun onStart(owner: LifecycleOwner) {
            if (visible) begin()
        }

        override fun onStop(owner: LifecycleOwner) {
            scheduleStop()
        }
    }

    val connection: StateFlow<RemoteConnectionState> = remote.state
    val mirrorState: StateFlow<MirrorState> = mirror.state

    init {
        ProcessLifecycleOwner.get().lifecycle.addObserver(processObserver)
    }

    fun start() {
        visible = true
        remote.ensureRunning()
        viewModelScope.launch {
            delay(300)
            if (visible) remote.ensureRunning()
        }
        begin()
    }

    fun stop() {
        visible = false
        scheduleStop()
    }

    private fun scheduleStop() {
        (app.appScope + Dispatchers.IO + NonCancellable).launch {
            mirror.stop()
        }
    }

    fun setSurface(surface: Surface?) {
        mirror.setSurface(surface)
    }

    fun touch(action: Int, viewX: Float, viewY: Float, viewWidth: Int, viewHeight: Int) {
        mirror.touch(action, viewX, viewY, viewWidth, viewHeight)
    }

    fun sendKey(key: TvKey, press: KeyPress) {
        remote.sendKey(key, press)
    }

    private fun begin() {
        viewModelScope.launch(Dispatchers.IO) {
            val host = settings.read().pairedHost
            mirror.start(host)
        }
    }

    override fun onCleared() {
        ProcessLifecycleOwner.get().lifecycle.removeObserver(processObserver)
        scheduleStop()
    }
}
