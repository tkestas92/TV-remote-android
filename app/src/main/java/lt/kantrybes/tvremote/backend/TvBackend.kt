package lt.kantrybes.tvremote.backend

import android.view.Surface
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.StateFlow
import lt.kantrybes.tvremote.data.adb.NowPlaying
import lt.kantrybes.tvremote.data.remote.KeyPress
import lt.kantrybes.tvremote.data.remote.RemoteConnectionState
import lt.kantrybes.tvremote.data.remote.TvKey
import lt.kantrybes.tvremote.data.scrcpy.MirrorPreset
import lt.kantrybes.tvremote.data.scrcpy.MirrorStage
import lt.kantrybes.tvremote.data.scrcpy.MirrorState
import lt.kantrybes.tvremote.data.settings.AppShortcut

internal interface TvRemote {
    val state: StateFlow<RemoteConnectionState>
    val processInForeground: Boolean
    fun ensureRunning()
    fun stop()
    fun setMediaKeepAlive(keep: Boolean)
    fun sendKey(key: TvKey, press: KeyPress)
    suspend fun sendKeyNow(key: TvKey, press: KeyPress)
    fun imeEpoch(): Int
    suspend fun sendImeText(text: String): Boolean
    fun imeAvailable(): Boolean
    suspend fun awaitImeSince(token: Int, timeoutMs: Long): Boolean
    fun sendAppLink(packageName: String): Boolean
}

internal interface TvAdb {
    val dispatcher: CoroutineDispatcher
    fun isReady(host: String): Boolean
    suspend fun warm(host: String)
    fun drop()
    suspend fun launch(
        shortcut: AppShortcut,
        host: String,
        supportsAppLinks: Boolean,
        sendAppLink: () -> Boolean,
        onWaiting: () -> Unit,
        onClear: () -> Unit,
        onError: () -> Unit,
    ): Boolean
    suspend fun typeAscii(
        host: String,
        chunk: String,
        onWaiting: () -> Unit,
        onClear: () -> Unit,
        onError: () -> Unit,
    ): Boolean
    suspend fun openYouTube(host: String, url: String, startedAt: Long): Boolean
    suspend fun openPictureMenu(
        host: String,
        onWaiting: () -> Unit,
        onClear: () -> Unit,
        onError: () -> Unit,
    )
    suspend fun exec(host: String, command: String): String?
    suspend fun prefetch(host: String, packages: List<String>)
    suspend fun setPicture(item: String, value: Int): Boolean
}

internal interface TvMirror {
    val state: StateFlow<MirrorState>
    val stage: StateFlow<MirrorStage>
    val stalled: StateFlow<Boolean>
    val captureBlocked: StateFlow<Boolean>
    fun sessionRunning(): Boolean
    fun start(host: String, preset: MirrorPreset = MirrorPreset.Smooth, presetChange: Boolean = false)
    fun resume()
    fun leave()
    fun stop()
    fun setSurface(next: Surface?)
    fun touch(action: Int, viewX: Float, viewY: Float, viewWidth: Int, viewHeight: Int)
}

internal interface TvNowPlaying {
    val nowPlaying: StateFlow<NowPlaying?>
    fun start()
    fun stop(reason: String)
    fun onServiceGone(reason: String)
}

internal interface TvPairing {
    fun openAndRequestCode()
    fun submitCode(pairingCode: String)
    fun close()
}

internal interface TvDiscovery {
    val names: StateFlow<List<String>>
    fun hasIdentity(): Boolean
    fun test(host: String)
    fun openPairing(host: String): TvPairing
}

internal data class TvCell(val label: String, val color: Long)

internal data class TvPicture(
    val shown: Boolean = false,
    val powerOn: Boolean = true,
    val home: Boolean = true,
    val focus: Int = 0,
    val cells: List<TvCell> = emptyList(),
    val appTitle: String? = null,
    val appColor: Long = 0xFF37474F,
    val volume: Int = 10,
    val volumeTick: Int = 0,
    val muted: Boolean = false,
    val search: String? = null,
    val banner: String? = null,
    val preset: String = "",
)

internal interface TvPreview {
    val picture: StateFlow<TvPicture>
}

internal class TvBackend(
    val remote: TvRemote,
    val adb: TvAdb,
    val mirror: TvMirror,
    val nowPlaying: TvNowPlaying,
    val discovery: TvDiscovery,
    val preview: TvPreview,
)
