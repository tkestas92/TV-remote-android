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

internal data class NowPlaying(
    val packageName: String,
    val appName: String,
    val state: Int,
    val positionMs: Long,
    val speed: Float,
    val title: String,
    val artist: String?,
)

/**
 * Parses `dumpsys media_session` plus `/proc/uptime`. The first session whose
 * playback state is playing, paused, or buffering wins. Position advances only
 * while playing: position + (uptimeMs - updated) * speed.
 */
internal object NowPlayingDump {
    const val STATE_PAUSED = 2
    const val STATE_PLAYING = 3
    const val STATE_BUFFERING = 6
    const val COMMAND = "dumpsys media_session; cat /proc/uptime"

    private val activeStates = setOf(STATE_PAUSED, STATE_PLAYING, STATE_BUFFERING)
    private val stateNumber = Regex("""state=(\d+)""")
    private val positionNumber = Regex("""(?<!buffered )position=(-?\d+)""")
    private val speedNumber = Regex("""speed=(-?\d+(?:\.\d+)?)""")
    private val updatedNumber = Regex("""updated=(-?\d+)""")
    private val uptimeLine = Regex("""\d+(?:\.\d+)?\s+\d+(?:\.\d+)?""")

    fun uptimeMs(output: String): Long? {
        val line = output.lineSequence().map { it.trim() }.lastOrNull { uptimeLine.matches(it) } ?: return null
        val seconds = line.substringBefore(' ').toDoubleOrNull() ?: return null
        return (seconds * 1000.0).toLong()
    }

    fun parse(dump: String, uptimeMs: Long?): NowPlaying? {
        var packageName: String? = null
        var state: Int? = null
        var positionMs = 0L
        var speed = 1f
        var updatedMs = 0L
        var title: String? = null
        var artist: String? = null
        var sawState = false

        fun finish(): NowPlaying? {
            val code = state ?: return null
            val pkg = packageName ?: return null
            if (code !in activeStates) return null
            val name = appName(pkg)
            val shownTitle = title?.takeUnless { it.isBlank() || it == "null" } ?: name
            val shownArtist = artist?.takeUnless { it.isBlank() || it == "null" }
            val speedShown = if (code == STATE_PLAYING) speed else 0f
            return NowPlaying(
                packageName = pkg,
                appName = name,
                state = code,
                positionMs = extrapolatedPosition(code, positionMs, updatedMs, speed, uptimeMs),
                speed = speedShown,
                title = shownTitle,
                artist = shownArtist,
            )
        }

        for (raw in dump.lines()) {
            val line = raw.trim()
            if (line.contains("package=")) {
                if (sawState) finish()?.let { return it }
                packageName = Regex("""package=([^\s,]+)""").find(line)?.groupValues?.get(1)
                state = null
                positionMs = 0L
                speed = 1f
                updatedMs = 0L
                title = null
                artist = null
                sawState = false
            }
            if (!sawState && "position=" in line && stateNumber.containsMatchIn(line)) {
                state = stateNumber.find(line)?.groupValues?.get(1)?.toIntOrNull()
                positionMs = positionNumber.find(line)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
                speed = speedNumber.find(line)?.groupValues?.get(1)?.toFloatOrNull() ?: 1f
                updatedMs = updatedNumber.find(line)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
                sawState = state != null
            }
            if ("description=" in line) {
                val parsed = description(line.substringAfter("description=").trim())
                title = parsed.first
                artist = parsed.second
            }
        }
        return finish()
    }

    fun appName(packageName: String): String = when (packageName) {
        "com.google.android.youtube.tv" -> "YouTube"
        "lt.go3.android.tv" -> "Go3"
        "com.stremio.one" -> "Stremio"
        "com.netflix.ninja" -> "Netflix"
        else -> packageName
    }

    fun extrapolatedPosition(
        state: Int,
        positionMs: Long,
        updatedMs: Long,
        speed: Float,
        uptimeMs: Long?,
    ): Long {
        if (state != STATE_PLAYING || uptimeMs == null) return positionMs.coerceAtLeast(0)
        val delta = ((uptimeMs - updatedMs).toDouble() * speed.toDouble()).toLong()
        return (positionMs + delta).coerceAtLeast(0)
    }

    private fun description(raw: String): Pair<String?, String?> {
        if (raw.isEmpty() || raw == "null") return null to null
        val parts = raw.split(", ").map { it.trim() }
        if (parts.size == 1) return blankToNull(parts[0]) to null
        if (parts.size == 2) return blankToNull(parts[0]) to blankToNull(parts[1])
        val title = blankToNull(parts.dropLast(2).joinToString(", "))
        val artist = blankToNull(parts[parts.size - 2])
        return title to artist
    }

    private fun blankToNull(value: String): String? = value.takeUnless { it.isBlank() || it == "null" }
}

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
