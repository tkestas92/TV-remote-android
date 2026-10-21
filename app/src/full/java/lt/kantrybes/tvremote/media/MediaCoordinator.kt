package lt.kantrybes.tvremote.media

import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import lt.kantrybes.tvremote.TvRemoteApplication
import lt.kantrybes.tvremote.backend.TvNowPlaying
import lt.kantrybes.tvremote.backend.TvRemote
import lt.kantrybes.tvremote.data.adb.AdbClient
import lt.kantrybes.tvremote.data.adb.NowPlaying
import lt.kantrybes.tvremote.data.adb.NowPlayingMonitor
import lt.kantrybes.tvremote.data.remote.RemoteConnectionState
import lt.kantrybes.tvremote.data.settings.SettingsRepository

/**
 * Starts the media card once the remote session is up and something is playing.
 * Stops it after 10 minutes of silence, 60 seconds without the TV, a settings
 * toggle, or the user dismissing the card.
 */
internal class MediaCoordinator(
    private val app: TvRemoteApplication,
    adb: AdbClient,
    private val remote: TvRemote,
) : TvNowPlaying {
    private val settings = SettingsRepository(app)
    private val monitor = NowPlayingMonitor(adb, app.appScope, ::onSample)
    override val nowPlaying: StateFlow<NowPlaying?> = monitor.nowPlaying

    private var enabled = true
    private var sawConnected = false
    private var serviceRunning = false
    private var idleSince: Long? = null
    private var unreachableSince: Long? = null

    override fun start() {
        app.appScope.launch {
            combine(settings.showMediaControls(), remote.state) { show, state -> show to state }
                .collect { (show, state) -> onConnection(show, state) }
        }
    }

    override fun stop(reason: String) {
        if (!serviceRunning) return
        Log.i(TAG, "Service stop: $reason")
        serviceRunning = false
        idleSince = null
        unreachableSince = null
        remote.setMediaKeepAlive(false)
        app.stopService(Intent(app, TvMediaService::class.java))
    }

    override fun onServiceGone(reason: String) {
        if (!serviceRunning) return
        stop(reason)
    }

    private suspend fun onConnection(show: Boolean, state: RemoteConnectionState) {
        enabled = show
        if (!show) {
            sawConnected = false
            monitor.stop()
            stop("disabled")
            return
        }
        if (state is RemoteConnectionState.Connected) sawConnected = true
        val sessionUp = sawConnected &&
            state !is RemoteConnectionState.Disconnected &&
            state !is RemoteConnectionState.NeedsPairing
        if (!sessionUp) {
            if (
                state is RemoteConnectionState.Disconnected ||
                state is RemoteConnectionState.NeedsPairing
            ) {
                sawConnected = false
                monitor.stop()
            }
            return
        }
        val host = settings.read().pairedHost
        if (host.isNotBlank()) monitor.start(host)
    }

    private fun onSample(current: NowPlaying?, reachable: Boolean) {
        if (!enabled) return
        if (!reachable) {
            if (!serviceRunning) return
            val since = unreachableSince ?: System.currentTimeMillis().also { unreachableSince = it }
            if (System.currentTimeMillis() - since >= UNREACHABLE_MS) stop("unreachable")
            return
        }
        unreachableSince = null
        if (current != null) {
            idleSince = null
            maybeStart()
        } else if (serviceRunning) {
            val since = idleSince ?: System.currentTimeMillis().also { idleSince = it }
            if (System.currentTimeMillis() - since >= IDLE_MS) stop("idle 10 min")
        }
    }

    private fun maybeStart() {
        if (serviceRunning) return
        if (!remote.processInForeground) {
            Log.i(TAG, "Not starting media service from the background")
            return
        }
        Log.i(TAG, "Service start: playing")
        serviceRunning = true
        remote.setMediaKeepAlive(true)
        try {
            ContextCompat.startForegroundService(app, Intent(app, TvMediaService::class.java))
        } catch (error: Exception) {
            Log.w(TAG, "Service start failed", error)
            serviceRunning = false
            remote.setMediaKeepAlive(false)
        }
    }

    private companion object {
        const val TAG = "TvRemoteMedia"
        const val IDLE_MS = 10 * 60 * 1000L
        const val UNREACHABLE_MS = 60_000L
    }
}
