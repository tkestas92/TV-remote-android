package lt.kantrybes.tvremote.backend

import android.content.Intent
import android.util.Log
import android.view.Surface
import androidx.core.content.ContextCompat
import java.io.IOException
import java.net.DatagramSocket
import java.net.ServerSocket
import java.net.Socket
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import lt.kantrybes.tvremote.TvRemoteApplication
import lt.kantrybes.tvremote.data.adb.NowPlaying
import lt.kantrybes.tvremote.data.adb.NowPlayingDump
import lt.kantrybes.tvremote.data.remote.InvalidPairingCodeException
import lt.kantrybes.tvremote.data.remote.KeyPress
import lt.kantrybes.tvremote.data.remote.RemoteConnectionState
import lt.kantrybes.tvremote.data.remote.TvKey
import lt.kantrybes.tvremote.data.scrcpy.MirrorPreset
import lt.kantrybes.tvremote.data.scrcpy.MirrorStage
import lt.kantrybes.tvremote.data.scrcpy.MirrorState
import lt.kantrybes.tvremote.data.settings.AppShortcut
import lt.kantrybes.tvremote.data.settings.SettingsRepository
import lt.kantrybes.tvremote.media.TvMediaService

internal fun createTvBackend(app: TvRemoteApplication): TvBackend {
    DemoSockets.block()
    val tv = DemoTv(app)
    val remote = DemoRemote(app, tv)
    val nowPlaying = DemoNowPlaying(app, remote, tv)
    return TvBackend(
        remote = remote,
        adb = DemoAdb(tv),
        mirror = DemoMirror(app, tv),
        nowPlaying = nowPlaying,
        discovery = DemoDiscovery(),
        preview = tv,
    )
}

private object DemoSockets {
    fun block() {
        try {
            Socket.setSocketImplFactory {
                throw IOException("Demo build does not use the network")
            }
        } catch (error: Exception) {
            Log.d(TAG, "Socket block already installed: ${error.message}")
        }
        try {
            ServerSocket.setSocketFactory {
                throw IOException("Demo build does not use the network")
            }
        } catch (error: Exception) {
            Log.d(TAG, "Server socket block already installed: ${error.message}")
        }
        try {
            DatagramSocket.setDatagramSocketImplFactory {
                throw IOException("Demo build does not use the network")
            }
        } catch (error: Exception) {
            Log.d(TAG, "Datagram block already installed: ${error.message}")
        }
    }

    private const val TAG = "TvRemoteDemo"
}

private class DemoTv(app: TvRemoteApplication) : TvPreview {
    private val _picture = MutableStateFlow(homePicture())
    override val picture: StateFlow<TvPicture> = _picture.asStateFlow()

    private val _now = MutableStateFlow<NowPlaying?>(null)
    val nowPlaying: StateFlow<NowPlaying?> = _now.asStateFlow()

    private var sessionUp = false
    private var playing = true
    private var positionMs = 0L
    private var track = 1

    init {
        app.appScope.launch {
            while (isActive) {
                delay(250)
                if (!sessionUp || !playing) continue
                positionMs = (positionMs + 250).coerceAtMost(TRACK_MS)
                if (positionMs >= TRACK_MS) playing = false
                publish()
            }
        }
    }

    fun setSessionUp(up: Boolean) {
        sessionUp = up
        if (!up) {
            _now.value = null
            return
        }
        publish()
    }

    fun key(key: TvKey, press: KeyPress) {
        if (press == KeyPress.END_LONG) return
        val current = _picture.value
        if (!current.powerOn && key != TvKey.POWER) return
        when (key) {
            TvKey.POWER -> edit { it.copy(powerOn = !it.powerOn) }
            TvKey.HOME -> edit { homePicture().copy(powerOn = true, volume = it.volume, muted = it.muted, preset = it.preset) }
            TvKey.BACK -> back()
            TvKey.OK -> openFocused()
            TvKey.UP, TvKey.DOWN, TvKey.LEFT, TvKey.RIGHT -> move(key)
            TvKey.VOLUME_UP -> volume(1)
            TvKey.VOLUME_DOWN -> volume(-1)
            TvKey.MUTE -> edit { it.copy(muted = !it.muted) }
            TvKey.PLAY_PAUSE -> {
                playing = !playing
                publish()
            }
            TvKey.NEXT -> {
                track = if (track == 1) 2 else 1
                positionMs = 0
                playing = true
                publish()
            }
            TvKey.DEL -> edit {
                val next = it.search?.dropLast(1)
                it.copy(search = next?.takeIf { text -> text.isNotEmpty() })
            }
            else -> Unit
        }
    }

    fun openApp(label: String) {
        edit {
            it.copy(
                powerOn = true,
                home = false,
                appTitle = label,
                appColor = colorFor(label),
                search = null,
                banner = null,
            )
        }
    }

    fun showSearch(text: String) {
        edit { it.copy(powerOn = true, search = text.ifEmpty { null }) }
    }

    fun appendSearch(text: String) {
        edit { it.copy(powerOn = true, search = (it.search.orEmpty() + text).ifEmpty { null }) }
    }

    fun showBanner(text: String) {
        edit { it.copy(powerOn = true, banner = text) }
    }

    fun showShare(text: String) {
        edit {
            it.copy(
                powerOn = true,
                home = false,
                appTitle = "YouTube",
                appColor = colorFor("YouTube"),
                search = text,
                banner = null,
            )
        }
    }

    fun setPreset(label: String) {
        edit { it.copy(preset = label) }
    }

    fun moveFocus(index: Int) {
        edit { it.copy(home = true, appTitle = null, focus = index.coerceIn(0, CELLS.lastIndex), search = null) }
    }

    private fun back() {
        val current = _picture.value
        when {
            current.search != null -> edit { it.copy(search = null) }
            !current.home -> edit { homePicture().copy(powerOn = true, volume = it.volume, muted = it.muted, preset = it.preset) }
        }
    }

    private fun openFocused() {
        val current = _picture.value
        if (!current.home) return
        val cell = CELLS[current.focus]
        openApp(cell.label)
    }

    private fun move(key: TvKey) {
        val current = _picture.value
        if (!current.home) return
        val column = current.focus % COLUMNS
        val row = current.focus / COLUMNS
        val nextColumn = when (key) {
            TvKey.LEFT -> (column + COLUMNS - 1) % COLUMNS
            TvKey.RIGHT -> (column + 1) % COLUMNS
            else -> column
        }
        val nextRow = when (key) {
            TvKey.UP -> (row + ROWS - 1) % ROWS
            TvKey.DOWN -> (row + 1) % ROWS
            else -> row
        }
        edit { it.copy(focus = nextRow * COLUMNS + nextColumn) }
    }

    private fun volume(delta: Int) {
        edit {
            val next = (it.volume + delta).coerceIn(0, 20)
            it.copy(volume = next, volumeTick = it.volumeTick + 1, muted = if (delta > 0) false else it.muted)
        }
    }

    private fun publish() {
        if (!sessionUp) return
        val title = if (track == 1) "Demo video – 3:12" else "Demo video 2 – 3:12"
        _now.value = NowPlaying(
            packageName = "demo.player",
            appName = "Demo",
            state = if (playing) NowPlayingDump.STATE_PLAYING else NowPlayingDump.STATE_PAUSED,
            positionMs = positionMs,
            speed = if (playing) 1f else 0f,
            title = title,
            artist = null,
        )
    }

    private fun edit(block: (TvPicture) -> TvPicture) {
        _picture.value = block(_picture.value)
    }

    private fun homePicture(): TvPicture {
        return TvPicture(shown = true, cells = CELLS.map { TvCell(it.label, it.color) })
    }

    private data class Cell(val label: String, val color: Long)

    private companion object {
        const val COLUMNS = 3
        const val ROWS = 4
        const val TRACK_MS = 192_000L
        val CELLS = listOf(
            Cell("YouTube", 0xFFC62828),
            Cell("Netflix", 0xFFB71C1C),
            Cell("Stremio", 0xFF6A1B9A),
            Cell("Go3", 0xFF1565C0),
            Cell("Music", 0xFF6D4C41),
            Cell("Photos", 0xFFEF6C00),
            Cell("Games", 0xFF2E7D32),
            Cell("Live", 0xFF0277BD),
            Cell("Files", 0xFF455A64),
            Cell("Weather", 0xFF00838F),
            Cell("News", 0xFF5E35B1),
            Cell("Radio", 0xFFAD1457),
        )
        val EXTRA = listOf(0xFFC62828, 0xFF1565C0, 0xFF6A1B9A, 0xFF2E7D32, 0xFFEF6C00, 0xFF00838F)

        fun colorFor(label: String): Long {
            CELLS.firstOrNull { it.label.equals(label, ignoreCase = true) }?.let { return it.color }
            return EXTRA[(label.lowercase().hashCode() and Int.MAX_VALUE) % EXTRA.size]
        }
    }
}

private class DemoRemote(
    private val app: TvRemoteApplication,
    private val tv: DemoTv,
) : TvRemote {
    private val settings = SettingsRepository(app)
    private val _state = MutableStateFlow<RemoteConnectionState>(RemoteConnectionState.Disconnected)
    override val state: StateFlow<RemoteConnectionState> = _state.asStateFlow()
    override val processInForeground: Boolean = true
    private var keepAlive = false

    override fun ensureRunning() {
        app.appScope.launch {
            val host = settings.read().pairedHost
            if (host.isBlank()) {
                _state.value = RemoteConnectionState.NeedsPairing()
                tv.setSessionUp(false)
            } else {
                _state.value = RemoteConnectionState.Connected
                tv.setSessionUp(true)
            }
        }
    }

    override fun stop() {
        if (keepAlive) return
        _state.value = RemoteConnectionState.Disconnected
        tv.setSessionUp(false)
    }

    override fun setMediaKeepAlive(keep: Boolean) {
        keepAlive = keep
    }

    override fun sendKey(key: TvKey, press: KeyPress) {
        if (_state.value !is RemoteConnectionState.Connected) return
        tv.key(key, press)
    }

    override suspend fun sendKeyNow(key: TvKey, press: KeyPress) = sendKey(key, press)

    override fun imeEpoch(): Int = 1

    override suspend fun sendImeText(text: String): Boolean {
        tv.showSearch(text)
        return true
    }

    override fun imeAvailable(): Boolean = true

    override suspend fun awaitImeSince(token: Int, timeoutMs: Long): Boolean = true

    override fun sendAppLink(packageName: String): Boolean = false
}

private class DemoAdb(private val tv: DemoTv) : TvAdb {
    override val dispatcher: CoroutineDispatcher = Dispatchers.IO
    override fun isReady(host: String): Boolean = true
    override suspend fun warm(host: String) = Unit
    override fun drop() = Unit

    override suspend fun launch(
        shortcut: AppShortcut,
        host: String,
        supportsAppLinks: Boolean,
        sendAppLink: () -> Boolean,
        onWaiting: () -> Unit,
        onClear: () -> Unit,
        onError: () -> Unit,
    ): Boolean {
        tv.openApp(shortcut.label)
        return true
    }

    override suspend fun typeAscii(
        host: String,
        chunk: String,
        onWaiting: () -> Unit,
        onClear: () -> Unit,
        onError: () -> Unit,
    ): Boolean {
        tv.appendSearch(chunk)
        return true
    }

    override suspend fun openYouTube(host: String, url: String, startedAt: Long): Boolean {
        tv.showShare(url)
        return true
    }

    override suspend fun openPictureMenu(
        host: String,
        onWaiting: () -> Unit,
        onClear: () -> Unit,
        onError: () -> Unit,
    ) {
        tv.showBanner("Picture menu")
    }

    override suspend fun exec(host: String, command: String): String? = ""

    override suspend fun prefetch(host: String, packages: List<String>) = Unit

    override suspend fun setPicture(item: String, value: Int): Boolean {
        tv.showBanner(pictureBanner(item, value))
        return true
    }

    private fun pictureBanner(item: String, value: Int): String {
        val label = when (item) {
            "brightness" -> "Brightness"
            "contrast" -> "Contrast"
            "color_saturation" -> "Color"
            "sharpness" -> "Sharpness"
            else -> item
        }
        return "$label $value"
    }
}

private class DemoMirror(
    private val app: TvRemoteApplication,
    private val tv: DemoTv,
) : TvMirror {
    private val _state = MutableStateFlow<MirrorState>(MirrorState.Idle)
    private val _stage = MutableStateFlow<MirrorStage>(MirrorStage.Idle)
    override val state: StateFlow<MirrorState> = _state.asStateFlow()
    override val stage: StateFlow<MirrorStage> = _stage.asStateFlow()
    override val stalled: StateFlow<Boolean> = MutableStateFlow(false)
    override val captureBlocked: StateFlow<Boolean> = MutableStateFlow(false)
    private var running = false
    private var generation = 0

    override fun sessionRunning(): Boolean = running

    override fun start(host: String, preset: MirrorPreset, presetChange: Boolean) {
        val token = ++generation
        running = true
        tv.setPreset(preset.name)
        _state.value = MirrorState.Connecting
        _stage.value = MirrorStage.WaitingFirstFrame
        app.appScope.launch {
            delay(800)
            if (token != generation) return@launch
            _state.value = MirrorState.Streaming(1280, 720)
            _stage.value = MirrorStage.Streaming(1280, 720)
        }
    }

    override fun resume() {
        if (_state.value is MirrorState.Streaming) return
        start("", MirrorPreset.Smooth, presetChange = false)
    }

    override fun leave() {
        generation++
        running = false
        _state.value = MirrorState.Idle
        _stage.value = MirrorStage.Idle
    }

    override fun stop() = leave()

    override fun setSurface(next: Surface?) = Unit

    override fun touch(action: Int, viewX: Float, viewY: Float, viewWidth: Int, viewHeight: Int) {
        if (action != 1 || viewWidth <= 0 || viewHeight <= 0) return
        val column = ((viewX / viewWidth) * 3).toInt().coerceIn(0, 2)
        val row = ((viewY / viewHeight) * 4).toInt().coerceIn(0, 3)
        tv.moveFocus(row * 3 + column)
    }
}

private class DemoNowPlaying(
    private val app: TvRemoteApplication,
    private val remote: TvRemote,
    private val tv: DemoTv,
) : TvNowPlaying {
    private val settings = SettingsRepository(app)
    override val nowPlaying: StateFlow<NowPlaying?> = tv.nowPlaying
    private var serviceRunning = false

    override fun start() {
        app.appScope.launch {
            combine(settings.showMediaControls(), remote.state) { show, state -> show to state }
                .collect { (show, state) ->
                    if (!show) {
                        tv.setSessionUp(false)
                        stop("disabled")
                        return@collect
                    }
                    val up = state is RemoteConnectionState.Connected
                    tv.setSessionUp(up)
                    if (up && nowPlaying.value != null) maybeStart()
                }
        }
    }

    override fun stop(reason: String) {
        if (!serviceRunning) return
        serviceRunning = false
        remote.setMediaKeepAlive(false)
        app.stopService(Intent(app, TvMediaService::class.java))
    }

    override fun onServiceGone(reason: String) {
        if (!serviceRunning) return
        stop(reason)
    }

    private fun maybeStart() {
        if (serviceRunning) return
        serviceRunning = true
        remote.setMediaKeepAlive(true)
        try {
            ContextCompat.startForegroundService(app, Intent(app, TvMediaService::class.java))
        } catch (error: Exception) {
            Log.w("TvRemoteDemo", "Media card failed to start", error)
            serviceRunning = false
            remote.setMediaKeepAlive(false)
        }
    }
}

private class DemoDiscovery : TvDiscovery {
    override val names: StateFlow<List<String>> = MutableStateFlow(listOf("TCL BeyondTV (Demo)"))
    override fun hasIdentity(): Boolean = true

    override fun test(host: String) = Unit

    override fun openPairing(host: String): TvPairing {
        return object : TvPairing {
            override fun openAndRequestCode() {
                Thread.sleep(800)
            }

            override fun submitCode(pairingCode: String) {
                Thread.sleep(800)
                if (pairingCode.length != 6 || pairingCode.any { !it.isDigit() }) {
                    throw InvalidPairingCodeException(InvalidPairingCodeException.Reason.FORMAT)
                }
            }

            override fun close() = Unit
        }
    }
}
