package lt.kantrybes.tvremote.ui.screen

import android.app.Application
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.util.Log
import android.view.Surface
import android.view.TextureView
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus
import lt.kantrybes.tvremote.TvRemoteApplication
import lt.kantrybes.tvremote.data.adb.NowPlayingDump
import lt.kantrybes.tvremote.data.remote.KeyPress
import lt.kantrybes.tvremote.data.remote.RemoteConnectionState
import lt.kantrybes.tvremote.data.remote.TvKey
import lt.kantrybes.tvremote.data.adb.NowPlaying
import lt.kantrybes.tvremote.data.scrcpy.MirrorPreset
import lt.kantrybes.tvremote.data.scrcpy.MirrorStage
import lt.kantrybes.tvremote.data.scrcpy.MirrorState
import lt.kantrybes.tvremote.data.scrcpy.mirrorPresetWhilePlaying
import lt.kantrybes.tvremote.data.settings.SettingsRepository

internal class ScreenViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as TvRemoteApplication
    private val settings = SettingsRepository(application)
    private val remote = app.remote
    private val mirror = app.mirror
    private var visible = false
    private var boundView: TextureView? = null
    private var videoTexture: SurfaceTexture? = null
    private var videoSurface: Surface? = null
    private var layoutWidth = 0
    private var layoutHeight = 0
    private var frameVideoWidth = 0
    private var frameVideoHeight = 0
    private var host = ""
    private var savedPreset = MirrorPreset.Smooth
    private var runningPreset: MirrorPreset? = null
    private var opened = false
    private var autoLight = false
    private var beginJob: Job? = null
    private val _fullscreen = MutableStateFlow(false)
    private val _preset = MutableStateFlow(MirrorPreset.Smooth)
    private val _videoLightHint = MutableStateFlow(false)
    private val _pictureChip = MutableStateFlow(PictureChip.None)
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
    val stage: StateFlow<MirrorStage> = mirror.stage
    val fullscreen: StateFlow<Boolean> = _fullscreen.asStateFlow()
    val preset: StateFlow<MirrorPreset> = _preset.asStateFlow()
    val videoLightHint: StateFlow<Boolean> = _videoLightHint.asStateFlow()
    val pictureChip: StateFlow<PictureChip> = _pictureChip.asStateFlow()

    private val textureListener = object : TextureView.SurfaceTextureListener {
        override fun onSurfaceTextureAvailable(
            texture: SurfaceTexture,
            width: Int,
            height: Int,
        ) {
            if (videoSurface != null) return
            videoTexture = texture
            val surface = Surface(texture)
            videoSurface = surface
            mirror.setSurface(surface)
        }

        override fun onSurfaceTextureSizeChanged(
            texture: SurfaceTexture,
            width: Int,
            height: Int,
        ) = Unit

        override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean = false

        override fun onSurfaceTextureUpdated(texture: SurfaceTexture) = Unit
    }

    init {
        ProcessLifecycleOwner.get().lifecycle.addObserver(processObserver)
        viewModelScope.launch(Dispatchers.IO) {
            _fullscreen.value = settings.mirrorFullscreen().first()
        }
        viewModelScope.launch {
            mirror.state.collect { state ->
                if (!visible && state is MirrorState.Idle) releaseVideo()
            }
        }
        viewModelScope.launch {
            combine(mirror.stalled, mirror.captureBlocked, app.media.nowPlaying, ::pictureChipFor)
                .collect { _pictureChip.value = it }
        }
        viewModelScope.launch {
            app.media.nowPlaying.collect { item ->
                if (!visible || !opened) return@collect
                val playing = item?.state == NowPlayingDump.STATE_PLAYING
                _videoLightHint.value = autoLight && playing && runningPreset == MirrorPreset.Light
            }
        }
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

    fun leave() {
        visible = false
        opened = false
        beginJob?.cancel()
        boundView = null
        mirror.leave()
    }

    fun stop() {
        visible = false
        opened = false
        beginJob?.cancel()
        releaseVideo()
        scheduleStop()
    }

    fun selectPreset(preset: MirrorPreset) {
        viewModelScope.launch(Dispatchers.IO) {
            beginJob?.cancel()
            savedPreset = preset
            settings.setMirrorPreset(preset)
            autoLight = false
            _videoLightHint.value = false
            logPreset(preset, "chosen")
            startMirror(preset)
            opened = visible
        }
    }

    fun setFullscreen(enabled: Boolean) {
        _fullscreen.value = enabled
        viewModelScope.launch(Dispatchers.IO) { settings.setMirrorFullscreen(enabled) }
    }

    fun bindVideo(view: TextureView, videoWidth: Int, videoHeight: Int) {
        frameVideoWidth = videoWidth
        frameVideoHeight = videoHeight
        if (boundView !== view) {
            boundView = view
            view.isOpaque = false
            view.surfaceTextureListener = textureListener
            view.addOnLayoutChangeListener { changed, _, _, _, _, _, _, _, _ ->
                val textureView = changed as TextureView
                layoutWidth = textureView.width
                layoutHeight = textureView.height
                applyLetterbox(textureView)
            }
            val retained = videoTexture
            if (retained != null && view.surfaceTexture !== retained) {
                try {
                    view.setSurfaceTexture(retained)
                } catch (error: RuntimeException) {
                    Log.w(TAG, "Keeping the mirror surface failed", error)
                }
            }
        }
        videoSurface?.let { mirror.setSurface(it) }
        applyLetterbox(view)
    }

    fun onVideoLayout(width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        layoutWidth = width
        layoutHeight = height
        boundView?.let(::applyLetterbox)
    }

    fun touch(action: Int, viewX: Float, viewY: Float) {
        mirror.touch(action, viewX, viewY, layoutWidth, layoutHeight)
    }

    fun sendKey(key: TvKey, press: KeyPress) {
        remote.sendKey(key, press)
    }

    private fun applyLetterbox(view: TextureView) {
        val viewWidth = view.width
        val viewHeight = view.height
        if (viewWidth <= 0 || viewHeight <= 0) return
        layoutWidth = viewWidth
        layoutHeight = viewHeight
        val matrix = Matrix()
        val videoWidth = frameVideoWidth
        val videoHeight = frameVideoHeight
        if (videoWidth > 0 && videoHeight > 0) {
            val videoAspect = videoWidth.toFloat() / videoHeight.toFloat()
            val viewAspect = viewWidth.toFloat() / viewHeight.toFloat()
            val scaleX: Float
            val scaleY: Float
            if (viewAspect > videoAspect) {
                scaleX = videoAspect / viewAspect
                scaleY = 1f
            } else {
                scaleX = 1f
                scaleY = viewAspect / videoAspect
            }
            matrix.setScale(scaleX, scaleY, viewWidth / 2f, viewHeight / 2f)
        }
        view.setTransform(matrix)
    }

    private fun releaseVideo() {
        val texture = videoTexture ?: return
        videoTexture = null
        videoSurface = null
        boundView = null
        mirror.setSurface(null)
        texture.release()
    }

    private fun scheduleStop() {
        (app.appScope + Dispatchers.IO + NonCancellable).launch {
            mirror.stop()
        }
    }

    private fun begin() {
        opened = false
        beginJob?.cancel()
        beginJob = viewModelScope.launch(Dispatchers.IO) {
            host = settings.read().pairedHost
            savedPreset = settings.mirrorPreset().first()
            if (!visible || !isActive) return@launch
            val playing = awaitPlayback()
            if (!visible || !isActive) return@launch
            val preset = mirrorPresetWhilePlaying(savedPreset, playing, userOverride = false)
            autoLight = playing && preset == MirrorPreset.Light
            _videoLightHint.value = autoLight
            logPreset(preset, if (playing && preset == MirrorPreset.Light && preset != savedPreset) "auto, video playing" else "open")
            startMirror(preset)
            opened = true
        }
    }

    private fun playingNow(): Boolean {
        return app.media.nowPlaying.value?.state == NowPlayingDump.STATE_PLAYING
    }

    private suspend fun awaitPlayback(): Boolean {
        if (app.media.nowPlaying.value != null) return playingNow()
        val deadline = System.currentTimeMillis() + PLAYBACK_WAIT_MS
        while (System.currentTimeMillis() < deadline && currentCoroutineContext().isActive) {
            delay(200)
            if (app.media.nowPlaying.value != null) return playingNow()
        }
        return playingNow()
    }

    private fun startMirror(preset: MirrorPreset) {
        val previous = runningPreset
        val live = mirror.sessionRunning()
        runningPreset = preset
        _preset.value = preset
        if (previous == preset && live) {
            mirror.resume()
            return
        }
        mirror.start(host, preset, presetChange = previous != null && previous != preset && live)
    }

    private fun pictureChipFor(stalled: Boolean, blocked: Boolean, now: NowPlaying?): PictureChip {
        return when {
            blocked -> PictureChip.Blocked
            stalled && now?.state == NowPlayingDump.STATE_PAUSED -> PictureChip.Paused
            stalled -> PictureChip.Static
            else -> PictureChip.None
        }
    }

    private fun logPreset(preset: MirrorPreset, reason: String) {
        Log.i(
            TAG,
            "Preset ${preset.wire} max_size=${preset.maxSize} max_fps=${preset.maxFps} " +
                "video_bit_rate=${preset.bitRate} ($reason)",
        )
    }

    override fun onCleared() {
        ProcessLifecycleOwner.get().lifecycle.removeObserver(processObserver)
        releaseVideo()
        scheduleStop()
    }

    private companion object {
        const val TAG = "TvRemoteMirror"
        const val PLAYBACK_WAIT_MS = 1_200L
    }
}

internal enum class PictureChip {
    None,
    Paused,
    Static,
    Blocked,
}
