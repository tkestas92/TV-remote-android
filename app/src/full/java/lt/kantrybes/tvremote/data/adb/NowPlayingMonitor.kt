package lt.kantrybes.tvremote.data.adb

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Polls the warm ADB connection while the remote session is up.
 * [onSample] runs for every poll, including repeats, so callers can time out.
 */
internal class NowPlayingMonitor(
    private val adb: AdbClient,
    private val scope: CoroutineScope,
    private val onSample: (NowPlaying?, Boolean) -> Unit,
) {
    private val _now = MutableStateFlow<NowPlaying?>(null)
    val nowPlaying: StateFlow<NowPlaying?> = _now

    private var job: Job? = null
    private var host: String? = null
    private var lastLogged: String? = null
    private var reportedUnreachable = false

    fun start(host: String) {
        if (host.isBlank()) return
        if (this.host == host && job?.isActive == true) return
        job?.cancel()
        this.host = host
        lastLogged = null
        reportedUnreachable = false
        job = scope.launch { loop(host) }
    }

    fun stop() {
        job?.cancel()
        job = null
        host = null
    }

    private suspend fun loop(host: String) {
        val launcher = AppLauncher(adb)
        while (currentCoroutineContext().isActive && this.host == host) {
            val started = System.currentTimeMillis()
            val output = try {
                launcher.exec(host, NowPlayingDump.COMMAND)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Log.w(TAG, "Now playing poll failed", error)
                null
            }
            if (!currentCoroutineContext().isActive || this.host != host) return
            if (output == null) {
                if (!reportedUnreachable) {
                    reportedUnreachable = true
                    Log.w(TAG, "TV unreachable")
                }
                onSample(_now.value, false)
            } else {
                reportedUnreachable = false
                val parsed = NowPlayingDump.parse(output, NowPlayingDump.uptimeMs(output))
                logChange(parsed)
                _now.value = parsed
                onSample(parsed, true)
            }
            val wait = POLL_MS - (System.currentTimeMillis() - started)
            if (wait > 0) delay(wait)
        }
    }

    private fun logChange(item: NowPlaying?) {
        val key = item?.let { "${it.packageName}|${it.state}|${it.title}|${it.artist}|${it.speed}" } ?: "nothing"
        if (key == lastLogged) return
        lastLogged = key
        if (item == null) {
            Log.i(TAG, "Now playing: nothing")
            return
        }
        val artist = item.artist?.let { " / $it" }.orEmpty()
        Log.i(
            TAG,
            "Now playing: ${item.appName} state=${item.state} \"${item.title}\"$artist " +
                "position=${item.positionMs} speed=${item.speed}",
        )
    }

    private companion object {
        const val TAG = "TvRemoteMedia"
        const val POLL_MS = 3_000L
    }
}
