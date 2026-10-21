package lt.kantrybes.tvremote.data.scrcpy

import android.content.Context
import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Build
import android.util.Log
import android.view.Surface
import dadb.AdbStream
import dadb.Dadb
import dadb.SyncResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import lt.kantrybes.tvremote.data.adb.AdbClient
import lt.kantrybes.tvremote.data.adb.AdbShell
import okio.BufferedSource
import java.io.File
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException

internal class ScrcpyMirror(
    private val context: Context,
    private val adb: AdbClient,
    private val appScope: CoroutineScope,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val videoExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "TvRemoteMirror-video")
    }
    private val videoDispatcher = videoExecutor.asCoroutineDispatcher()
    private val generation = AtomicInteger(0)
    private val codecLock = Any()
    private val streamLock = Any()
    private var job: Job? = null
    private var writerJob: Job? = null
    private var link: Dadb? = null
    private var shell: AdbStream? = null
    private var video: AdbStream? = null
    private var control: AdbStream? = null
    @Volatile private var controlOut: Channel<ControlMsg> = Channel(Channel.UNLIMITED)
    @Volatile private var controlAlive = false
    @Volatile private var fingerDown = false
    private var lastX = 0
    private var lastY = 0
    private var codec: MediaCodec? = null
    private var surface: Surface? = null
    private var codecConfig: ByteArray? = null
    @Volatile private var videoWidth = 0
    @Volatile private var videoHeight = 0
    private var loggedStart = false
    private var loggedFrame = false
    private var frameWatch: Job? = null
    private var graceJob: Job? = null
    private var activePreset = MirrorPreset.Smooth
    private val videoRoute = AtomicReference(false)
    @Volatile private var presenting = true
    @Volatile private var awaitKeyframe = false
    @Volatile private var reattachPending = false
    @Volatile private var controlHandshakeDone = false
    @Volatile private var allowTimeoutRestart = true
    @Volatile private var everDecoded = false
    @Volatile private var resetSent = false
    @Volatile private var lastFrameAt = 0L
    @Volatile private var streamingSince = 0L
    @Volatile private var reuseStartedAt = 0L
    @Volatile private var loggedOutput = false
    private var lastHost: String? = null
    private var cachedJarMd5: String? = null
    private var cachedJarSize = -1L
    private var codecLabel = "h264"
    private var phase = "start"
    private var startedAt = 0L
    private val serverLog = StringBuilder()
    @Volatile private var serverExited = false
    @Volatile private var videoConnected = false

    private val _state = MutableStateFlow<MirrorState>(MirrorState.Idle)
    val state: StateFlow<MirrorState> = _state.asStateFlow()
    private val _stage = MutableStateFlow<MirrorStage>(MirrorStage.Idle)
    val stage: StateFlow<MirrorStage> = _stage.asStateFlow()
    private val _stalled = MutableStateFlow(false)
    val stalled: StateFlow<Boolean> = _stalled.asStateFlow()
    private val _captureBlocked = MutableStateFlow(false)
    val captureBlocked: StateFlow<Boolean> = _captureBlocked.asStateFlow()

    fun sessionRunning(): Boolean {
        return job?.isActive == true &&
            videoConnected &&
            controlAlive &&
            _state.value !is MirrorState.Failed &&
            _state.value !is MirrorState.Idle
    }

    fun start(
        host: String,
        preset: MirrorPreset = MirrorPreset.Smooth,
        presetChange: Boolean = false,
        allowTimeoutRestart: Boolean = true,
    ) {
        val current = generation.incrementAndGet()
        graceJob?.cancel()
        graceJob = null
        job?.cancel()
        frameWatch?.cancel()
        frameWatch = null
        val previous = controlOut
        controlOut = Channel(Channel.UNLIMITED)
        previous.close()
        activePreset = preset
        lastHost = host
        this.allowTimeoutRestart = allowTimeoutRestart
        presenting = true
        awaitKeyframe = false
        reattachPending = false
        controlHandshakeDone = false
        everDecoded = false
        resetSent = false
        reuseStartedAt = 0L
        startedAt = System.currentTimeMillis()
        loggedStart = false
        loggedFrame = false
        loggedOutput = false
        lastFrameAt = 0L
        streamingSince = 0L
        _stalled.value = false
        _captureBlocked.value = false
        phase = "start"
        _state.value = MirrorState.Connecting
        _stage.value = MirrorStage.PushingJar
        synchronized(codecLock) {
            updateRoute(surface != null)
        }
        job = appScope.launch(Dispatchers.IO) {
            try {
                withContext(NonCancellable) { closeStreams() }
                if (current != generation.get()) return@launch
                run(host, current, preset, presetChange)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (current == generation.get() && _state.value !is MirrorState.Failed) {
                    Log.w(TAG, "Mirror failed at $phase", error)
                    fail(current, detail(phase, reason(error)))
                }
            } finally {
                if (current == generation.get()) {
                    withContext(NonCancellable) { closeStreams() }
                }
            }
        }
    }

    fun resume() {
        graceJob?.cancel()
        graceJob = null
        if (!sessionRunning()) return
        val sendReset: Boolean
        synchronized(codecLock) {
            if (presenting && codec != null && videoRoute.get()) return
            presenting = true
            reattachPending = true
            if (codec == null) restartCodec()
            updateRoute(surface != null)
            sendReset = armReattachLocked()
        }
        if (sendReset) requestKeyframe()
    }

    fun leave() {
        if (!sessionRunning()) {
            stop()
            return
        }
        presenting = false
        reattachPending = false
        awaitKeyframe = false
        synchronized(codecLock) {
            releaseCodec()
            updateRoute(false)
        }
        graceJob?.cancel()
        graceJob = scope.launch {
            delay(GRACE_MS)
            Log.d(TAG, "grace expired, server stopped")
            stop()
        }
    }

    fun stop() {
        val token = generation.incrementAndGet()
        graceJob?.cancel()
        graceJob = null
        job?.cancel()
        job = null
        frameWatch?.cancel()
        frameWatch = null
        presenting = false
        reattachPending = false
        controlHandshakeDone = false
        lastFrameAt = 0L
        streamingSince = 0L
        _stalled.value = false
        _captureBlocked.value = false
        updateRoute(false)
        controlOut.close()
        _state.value = MirrorState.Idle
        _stage.value = MirrorStage.Idle
        appScope.launch(Dispatchers.IO + NonCancellable) {
            if (token == generation.get()) {
                pkillQuietly(presetChange = false)
                closeStreams()
            }
        }
    }

    fun setSurface(next: Surface?) {
        val sendReset: Boolean
        try {
            synchronized(codecLock) {
                if (next == null) {
                    releaseCodec()
                    surface?.release()
                    surface = null
                    updateRoute(false)
                    return
                }
                if (surface === next && codec != null && presenting && videoRoute.get()) return
                val reattach = (!presenting || graceJob != null) && controlAlive
                if (surface !== next) {
                    releaseCodec()
                    surface?.release()
                    surface = next
                }
                if (reattach) {
                    graceJob?.cancel()
                    graceJob = null
                    presenting = true
                    reattachPending = true
                }
                if (presenting && codec == null) restartCodec()
                updateRoute(presenting && surface != null)
                sendReset = armReattachLocked()
            }
        } catch (error: Exception) {
            Log.w(TAG, "Decoder surface failed", error)
            return
        }
        if (sendReset) requestKeyframe()
    }

    fun touch(action: Int, viewX: Float, viewY: Float, viewWidth: Int, viewHeight: Int) {
        val width = videoWidth
        val height = videoHeight
        if (width <= 0 || height <= 0) return
        val mapped = ScrcpyProtocol.mapTouch(
            viewX,
            viewY,
            viewWidth.toFloat(),
            viewHeight.toFloat(),
            width,
            height,
        )
        val point = when {
            mapped != null -> mapped
            action != ScrcpyProtocol.ACTION_DOWN && fingerDown -> lastX to lastY
            else -> return
        }
        val resolved = if (mapped == null) ScrcpyProtocol.ACTION_UP else action
        if (resolved == ScrcpyProtocol.ACTION_UP) {
            fingerDown = false
        } else {
            fingerDown = true
            lastX = point.first
            lastY = point.second
        }
        sendTouch(resolved, point.first, point.second, width, height)
    }

    private suspend fun run(host: String, current: Int, preset: MirrorPreset, presetChange: Boolean) {
        if (host.isBlank()) {
            fail(current, context.getString(lt.kantrybes.tvremote.R.string.connection_needs_pairing))
            return
        }
        phase = "adb"
        val connection = adb.openDedicated(host, MIRROR_READ_TIMEOUT_MS)
        if (!keep(current) { link = connection }) {
            connection.close()
            return
        }
        if (!pushServer(connection, host, current)) return
        if (current != generation.get()) return
        launchMirror(connection, current, preset, presetChange)
    }

    private suspend fun pushServer(connection: Dadb, host: String, current: Int): Boolean {
        phase = "jar push"
        if (current == generation.get()) _stage.value = MirrorStage.PushingJar
        val (assetMd5, assetSize) = assetJarFingerprint()
        val checkedAt = System.currentTimeMillis()
        val warm = try {
            adb.connection(host)
        } catch (error: Exception) {
            Log.d(TAG, "warm adb unavailable: ${reason(error)}")
            connection
        }
        val remoteMd5 = try {
            ScrcpyProtocol.remoteJarMd5(
                AdbShell.exec(warm, "md5sum ${ScrcpyProtocol.REMOTE_JAR} 2>/dev/null"),
            )
        } catch (error: Exception) {
            Log.d(TAG, "md5sum failed: ${reason(error)}")
            null
        }
        val remoteSize = if (remoteMd5 == null) remoteJarSize(warm) else null
        val checkMs = System.currentTimeMillis() - checkedAt
        if (ScrcpyProtocol.jarIsCurrent(remoteMd5, remoteSize, assetMd5, assetSize)) {
            Log.d(TAG, "jar check $checkMs ms (skipped)")
            Log.i(TAG, "jar up to date, push skipped")
            return current == generation.get()
        }
        val jar = copyServer()
        val pushStarted = System.currentTimeMillis()
        when (val pushed = connection.push(jar, ScrcpyProtocol.REMOTE_JAR)) {
            is SyncResult.Failure -> {
                Log.w(TAG, "Server push failed: ${pushed.reason}")
                fail(current, detail("jar push", pushed.reason))
                return false
            }
            SyncResult.Success -> {
                val pushMs = System.currentTimeMillis() - pushStarted
                Log.d(TAG, "jar check $checkMs ms (pushed)")
                Log.i(TAG, "jar pushed in $pushMs ms")
            }
        }
        return current == generation.get()
    }

    private fun remoteJarSize(connection: Dadb): Long? {
        return try {
            AdbShell.exec(connection, "stat -c %s ${ScrcpyProtocol.REMOTE_JAR}")
                .lineSequence()
                .map { it.trim() }
                .firstOrNull { it.isNotEmpty() && it.all(Char::isDigit) }
                ?.toLongOrNull()
        } catch (error: Exception) {
            Log.d(TAG, "stat failed: ${reason(error)}")
            null
        }
    }

    private fun assetJarFingerprint(): Pair<String, Long> {
        cachedJarMd5?.let { return it to cachedJarSize }
        val digest = MessageDigest.getInstance("MD5")
        var size = 0L
        context.assets.open("scrcpy-server.jar").use { input ->
            val buffer = ByteArray(8192)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
                size += read
            }
        }
        val md5 = digest.digest().joinToString("") { "%02x".format(it) }
        cachedJarMd5 = md5
        cachedJarSize = size
        return md5 to size
    }

    private suspend fun launchMirror(
        connection: Dadb,
        current: Int,
        preset: MirrorPreset,
        presetChange: Boolean,
    ): Boolean {
        if (current != generation.get()) return false
        try {
            phase = "server"
            val scid = ScrcpyProtocol.randomScid()
            val command = ScrcpyProtocol.serverCommand(scid, preset)
            if (current == generation.get()) _stage.value = MirrorStage.StartingServer
            val serverStarted = System.currentTimeMillis()
            pkillOn(connection, presetChange)
            if (current != generation.get()) return false
            Log.i(TAG, command)
            resetServerLog()
            val server = connection.open("shell:$command")
            if (!keep(current) { shell = server }) {
                server.close()
                return false
            }
            watchServer(server)
            Log.d(TAG, "server start ${System.currentTimeMillis() - serverStarted} ms")
            logStep("server started")
            if (current == generation.get()) _state.value = MirrorState.StartingServer
            val destination = ScrcpyProtocol.socket(scid)
            phase = "video socket"
            if (current == generation.get()) _stage.value = MirrorStage.ConnectingVideo
            val videoStarted = System.currentTimeMillis()
            val videoStream = openWhenReady(connection, destination, current, "video socket") ?: return false
            if (!keep(current) { video = videoStream }) {
                videoStream.close()
                return false
            }
            videoConnected = true
            Log.d(TAG, "video socket ${System.currentTimeMillis() - videoStarted} ms")
            logStep("video socket connected")
            if (current == generation.get()) {
                streamingSince = System.currentTimeMillis()
                _stage.value = MirrorStage.WaitingFirstFrame
                ensureFrameWatch(current)
            }
            phase = "control socket"
            val controlStream = openWhenReady(connection, destination, current, "control socket") ?: return false
            if (!keep(current) { control = controlStream }) {
                controlStream.close()
                return false
            }
            logStep("control socket connected")
            controlAlive = true
            val outgoing = controlOut
            writerJob = launchWriter(controlStream, outgoing, current)
            withContext(videoDispatcher) {
                readVideo(videoStream.source, current)
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            if (current == generation.get() && _state.value !is MirrorState.Failed) {
                Log.w(TAG, "Mirror failed at $phase", error)
                fail(current, detail(phase, reason(error)))
            }
        }
        if (current != generation.get()) return true
        return _state.value !is MirrorState.Failed
    }

    private fun launchWriter(stream: AdbStream, outgoing: Channel<ControlMsg>, current: Int): Job {
        return scope.launch(Dispatchers.IO) {
            writeControl(stream, outgoing, current)
        }
    }

    private suspend fun writeControl(stream: AdbStream, outgoing: Channel<ControlMsg>, current: Int) {
        var nextMoveAt = 0L
        try {
            while (current == generation.get() && coroutineContext.isActive) {
                val first = try {
                    outgoing.receive()
                } catch (_: ClosedReceiveChannelException) {
                    return
                }
                val burst = ArrayList<ControlMsg>()
                burst.add(first)
                while (true) {
                    val next = outgoing.tryReceive().getOrNull() ?: break
                    burst.add(next)
                }
                for (message in ScrcpyProtocol.coalesceMoves(burst)) {
                    if (message.action == ScrcpyProtocol.ACTION_MOVE) {
                        var move = message
                        val waitMs = (nextMoveAt - System.nanoTime()) / 1_000_000
                        if (waitMs > 0) delay(waitMs)
                        val extra = ArrayList<ControlMsg>()
                        while (true) {
                            val next = outgoing.tryReceive().getOrNull() ?: break
                            extra.add(next)
                        }
                        if (extra.isEmpty()) {
                            writePayload(stream, move.payload)
                            nextMoveAt = System.nanoTime() + MOVE_INTERVAL_NS
                        } else {
                            for (piece in ScrcpyProtocol.coalesceMoves(listOf(move) + extra)) {
                                writePayload(stream, piece.payload)
                                if (piece.action == ScrcpyProtocol.ACTION_MOVE) {
                                    nextMoveAt = System.nanoTime() + MOVE_INTERVAL_NS
                                }
                            }
                        }
                    } else {
                        writePayload(stream, message.payload)
                    }
                }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (_: ClosedReceiveChannelException) {
        } catch (error: Exception) {
            if (!controlAlive || current != generation.get()) return
            controlAlive = false
            Log.w(TAG, "Control write failed: ${reason(error)}")
            fail(current, detail("control", reason(error)))
        }
    }

    private fun writePayload(stream: AdbStream, payload: ByteArray) {
        stream.sink.write(payload)
        stream.sink.flush()
    }

    private fun resetServerLog() {
        synchronized(serverLog) { serverLog.setLength(0) }
        serverExited = false
        videoConnected = false
    }

    private fun appendServerLine(line: String) {
        synchronized(serverLog) { serverLog.append(line).append('\n') }
        if (!videoConnected) Log.i(TAG, "server: $line")
    }

    private fun serverLogText(): String = synchronized(serverLog) { serverLog.toString() }

    private fun watchServer(server: AdbStream) {
        scope.launch(Dispatchers.IO) {
            try {
                while (isActive) {
                    val line = server.source.readUtf8Line() ?: break
                    appendServerLine(line)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
            } finally {
                serverExited = true
                if (!videoConnected) {
                    Log.i(TAG, "server exited early:\n${serverLogText()}")
                }
            }
        }
    }

    private suspend fun openWhenReady(
        connection: Dadb,
        destination: String,
        current: Int,
        step: String,
    ): AdbStream? {
        val deadline = System.currentTimeMillis() + CONNECT_TIMEOUT_MS
        var last: Exception? = null
        while (current == generation.get()) {
            if (serverExited) {
                fail(current, detail(step, "server exited"))
                return null
            }
            try {
                return connection.open(destination)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                last = error
                if (System.currentTimeMillis() >= deadline) break
                delay(RETRY_INTERVAL_MS)
            }
        }
        if (current != generation.get()) return null
        Log.w(TAG, "$step timed out", last)
        fail(current, detail(step, "timeout"))
        return null
    }

    private suspend fun readVideo(source: BufferedSource, current: Int) {
        phase = "dummy byte"
        source.readByte()
        logStep("dummy byte")
        phase = "device name"
        val name = ScrcpyProtocol.deviceName(source.readByteArray(ScrcpyProtocol.DEVICE_NAME_LENGTH.toLong()))
        logStep("device name", name)
        phase = "codec meta"
        val codecId = ScrcpyProtocol.codecId(source.readByteArray(4))
        codecLabel = if (codecId == ScrcpyProtocol.CODEC_H264) "h264" else "%08x".format(codecId)
        if (codecId != ScrcpyProtocol.CODEC_H264) {
            Log.w(TAG, "Unexpected codec id $codecLabel")
            fail(current, detail("codec meta", "unexpected $codecLabel"))
            return
        }
        controlHandshakeDone = controlAlive
        val sendReset = synchronized(codecLock) {
            updateRoute(presenting && surface != null)
            if (codec == null && codecConfig != null && presenting) restartCodec()
            armReattachLocked()
        }
        if (sendReset) requestKeyframe()
        var sawMeta = false
        var sawPacket = false
        var loggedPackets = 0
        while (current == generation.get() && coroutineContext.isActive) {
            phase = if (sawMeta) "video packet" else "codec meta"
            val header = ScrcpyProtocol.parseHeader(source.readByteArray(ScrcpyProtocol.HEADER_LENGTH.toLong()))
            if (loggedPackets < 10) {
                loggedPackets++
                val size = if (header is PacketHeader.Media) header.size else 0
                val config = header is PacketHeader.Media && header.config
                val key = header is PacketHeader.Media && header.keyFrame
                Log.d(TAG, "pkt #$loggedPackets size=$size config=$config key=$key")
            }
            when (header) {
                is PacketHeader.Session -> {
                    onSession(header.width, header.height, current)
                    sawMeta = true
                }
                is PacketHeader.Media -> {
                    if (header.size < 0 || header.size > MAX_PACKET) {
                        fail(current, detail("video packet", "packet too large"))
                        return
                    }
                    val payload = if (header.size == 0) ByteArray(0) else source.readByteArray(header.size.toLong())
                    if (!sawPacket) {
                        sawPacket = true
                        logStep("first packet")
                    }
                    if (header.config) {
                        if (onConfig(payload)) requestKeyframe()
                    } else if (!videoRoute.get()) {
                        // One reader for the whole session. Detached, so discard the payload.
                    } else if (awaitKeyframe && !header.keyFrame) {
                        // Skip only non-config, non-key packets. Key is header bit 61.
                    } else {
                        val accepted = feed(payload, header.pts)
                        if (accepted && header.keyFrame && awaitKeyframe) awaitKeyframe = false
                    }
                }
            }
        }
    }

    private fun onSession(width: Int, height: Int, current: Int) {
        if (width <= 0 || height <= 0) return
        val sendReset: Boolean
        synchronized(codecLock) {
            val hadSize = videoWidth > 0 && videoHeight > 0
            val changed = hadSize && (width != videoWidth || height != videoHeight)
            videoWidth = width
            videoHeight = height
            if (!loggedStart) {
                loggedStart = true
                logStep("codec meta", "$codecLabel/${width}x$height")
                val elapsed = System.currentTimeMillis() - startedAt
                Log.i(TAG, "Mirror started in $elapsed ms, ${width}x$height")
            }
            if (current == generation.get() && _state.value !is MirrorState.Failed) {
                _state.value = MirrorState.Streaming(width, height)
                if (!everDecoded && _stage.value !is MirrorStage.Failed) {
                    _stage.value = MirrorStage.WaitingFirstFrame
                }
                ensureFrameWatch(current)
            }
            if (changed) {
                codecConfig = null
                releaseCodec()
                reattachPending = true
                awaitKeyframe = true
            } else if (codec == null && codecConfig != null && presenting) {
                restartCodec()
            }
            sendReset = armReattachLocked()
        }
        if (sendReset) requestKeyframe()
    }

    private fun onConfig(payload: ByteArray): Boolean {
        synchronized(codecLock) {
            codecConfig = payload.copyOf()
            if (!videoRoute.get()) return false
            if (codec == null) {
                restartCodec()
            } else {
                try {
                    queueLocked(codecConfig!!, 0, MediaCodec.BUFFER_FLAG_CODEC_CONFIG)
                } catch (error: Exception) {
                    Log.w(TAG, "config buffer failed", error)
                }
            }
            return armReattachLocked()
        }
    }

    private fun feed(payload: ByteArray, pts: Long): Boolean {
        if (!videoRoute.get()) return false
        synchronized(codecLock) {
            if (!videoRoute.get() || !presenting) return false
            return queueLocked(payload, pts, 0)
        }
    }

    private fun queueLocked(payload: ByteArray, pts: Long, flags: Int): Boolean {
        val decoder = codec ?: return false
        val configBuffer = flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
        var index = decoder.dequeueInputBuffer(if (configBuffer) 100_000 else 10_000)
        if (index < 0) {
            drain(decoder)
            index = decoder.dequeueInputBuffer(10_000)
        }
        if (index < 0) return false
        val input = decoder.getInputBuffer(index) ?: return false
        input.clear()
        if (payload.size > input.remaining()) return false
        input.put(payload)
        decoder.queueInputBuffer(index, 0, payload.size, pts, flags)
        drain(decoder)
        return true
    }

    private fun restartCodec() {
        releaseCodec()
        loggedOutput = false
        val output = surface ?: return
        val config = codecConfig ?: return
        if (!presenting || videoWidth <= 0 || videoHeight <= 0) return
        val decoder = MediaCodec.createDecoderByType(MIME)
        try {
            val format = MediaFormat.createVideoFormat(MIME, videoWidth, videoHeight)
            format.setByteBuffer("csd-0", ByteBuffer.wrap(config))
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                format.setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
            }
            format.setInteger(MediaFormat.KEY_PRIORITY, 0)
            format.setInteger(MediaFormat.KEY_OPERATING_RATE, Short.MAX_VALUE.toInt())
            decoder.configure(format, output, null, 0)
            decoder.start()
            codec = decoder
            Log.d(TAG, "decoder configured")
            try {
                queueLocked(config, 0, MediaCodec.BUFFER_FLAG_CODEC_CONFIG)
            } catch (error: Exception) {
                Log.w(TAG, "config buffer failed", error)
            }
        } catch (error: Exception) {
            if (codec === decoder) codec = null
            try {
                decoder.release()
            } catch (_: Exception) {
            }
            throw error
        }
    }

    private fun drain(decoder: MediaCodec) {
        val info = MediaCodec.BufferInfo()
        val ready = ArrayList<Int>(4)
        while (true) {
            val index = decoder.dequeueOutputBuffer(info, 0)
            when {
                index == MediaCodec.INFO_TRY_AGAIN_LATER -> break
                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> continue
                index < 0 -> break
                info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0 -> {
                    decoder.releaseOutputBuffer(index, false)
                }
                else -> ready.add(index)
            }
        }
        if (ready.isEmpty()) return
        if (!loggedOutput) {
            loggedOutput = true
            Log.d(TAG, "first output buffer")
        }
        for (index in 0 until ready.lastIndex) {
            decoder.releaseOutputBuffer(ready[index], false)
        }
        decoder.releaseOutputBuffer(ready.last(), true)
        val now = System.currentTimeMillis()
        lastFrameAt = now
        everDecoded = true
        resetSent = false
        _stalled.value = false
        _captureBlocked.value = false
        val reuseAt = reuseStartedAt
        if (reuseAt > 0L) {
            reuseStartedAt = 0L
            Log.d(TAG, "reused session (grace) – first frame ${now - reuseAt} ms")
        } else if (!loggedFrame) {
            loggedFrame = true
            val waited = if (streamingSince > 0L) now - streamingSince else now - startedAt
            Log.d(TAG, "first frame $waited ms")
            logStep("first frame")
        }
        if (presenting && videoWidth > 0) {
            _stage.value = MirrorStage.Streaming(videoWidth, videoHeight)
        }
    }

    private fun ensureFrameWatch(current: Int) {
        if (streamingSince == 0L) streamingSince = System.currentTimeMillis()
        if (frameWatch?.isActive == true) return
        frameWatch = scope.launch(Dispatchers.IO) {
            while (current == generation.get() && isActive) {
                delay(FRAME_CHECK_MS)
                if (current != generation.get()) return@launch
                val now = System.currentTimeMillis()
                if (
                    allowTimeoutRestart &&
                    presenting &&
                    !everDecoded &&
                    videoConnected &&
                    streamingSince > 0L &&
                    now - streamingSince >= FIRST_FRAME_TIMEOUT_MS
                ) {
                    val host = lastHost
                    if (host != null) {
                        Log.i(TAG, "first-frame timeout → full restart")
                        start(host, activePreset, presetChange = false, allowTimeoutRestart = false)
                        return@launch
                    }
                }
                val alive = videoConnected && _state.value is MirrorState.Streaming && !serverExited
                if (!alive || !presenting) {
                    if (_stalled.value) _stalled.value = false
                    continue
                }
                val sinceFrame = if (lastFrameAt > 0L) now - lastFrameAt else now - streamingSince
                if (sinceFrame >= FRAME_GAP_MS && !resetSent && controlAlive && controlHandshakeDone) {
                    resetSent = true
                    requestKeyframe()
                }
                val stalled = everDecoded && sinceFrame >= FRAME_GAP_MS
                if (stalled != _stalled.value) _stalled.value = stalled
                val blocked = !everDecoded && streamingSince > 0L && now - streamingSince >= NO_FRAME_MS
                if (blocked != _captureBlocked.value) _captureBlocked.value = blocked
            }
        }
    }

    private fun armReattachLocked(): Boolean {
        if (!reattachPending || codec == null || !controlHandshakeDone || !controlAlive) return false
        reattachPending = false
        awaitKeyframe = true
        reuseStartedAt = System.currentTimeMillis()
        if (videoWidth > 0) _stage.value = MirrorStage.WaitingFirstFrame
        return true
    }

    private fun updateRoute(attached: Boolean) {
        if (videoRoute.getAndSet(attached) == attached) return
        Log.d(TAG, if (attached) "reader attach" else "reader detach")
    }

    private fun requestKeyframe() {
        if (!controlAlive || !controlHandshakeDone) return
        val sent = controlOut.trySend(ControlMsg(action = -1, payload = ScrcpyProtocol.resetVideo()))
        if (sent.isSuccess) Log.d(TAG, "RESET_VIDEO")
    }

    private fun pkillOn(connection: Dadb, presetChange: Boolean) {
        try {
            AdbShell.exec(connection, "pkill -f 'scrcpy-serve[r]' || true")
            if (presetChange) Log.d(TAG, "old server stopped (preset change)")
        } catch (error: Exception) {
            if (presetChange) Log.d(TAG, "old server stopped (preset change)")
            else Log.d(TAG, "pkill finished: ${reason(error)}")
        }
    }

    private fun pkillQuietly(presetChange: Boolean) {
        val connection = synchronized(streamLock) { link } ?: return
        pkillOn(connection, presetChange)
    }

    private fun logStep(name: String, extra: String = "") {
        val elapsed = System.currentTimeMillis() - startedAt
        if (extra.isEmpty()) {
            Log.i(TAG, "$name in $elapsed ms")
        } else {
            Log.i(TAG, "$name in $elapsed ms $extra")
        }
    }

    private fun detail(step: String, why: String): String {
        return context.getString(lt.kantrybes.tvremote.R.string.screen_failed_detail, step, why)
    }

    private fun reason(error: Throwable): String {
        val text = error.message?.lineSequence()?.firstOrNull()?.trim().orEmpty()
        val short = if (text.isEmpty()) error.javaClass.simpleName else text
        return short.take(80)
    }

    private fun fail(current: Int, message: String) {
        if (current != generation.get()) return
        _state.value = MirrorState.Failed(message)
        _stage.value = MirrorStage.Failed(message)
    }

    private fun closeSessionStreams() {
        controlAlive = false
        controlHandshakeDone = false
        writerJob?.cancel()
        writerJob = null
        val streams: List<AdbStream?>
        synchronized(streamLock) {
            streams = listOf(video, control, shell)
            video = null
            control = null
            shell = null
        }
        streams.forEach { stream ->
            try {
                stream?.close()
            } catch (error: Exception) {
                Log.w(TAG, "Closing a mirror stream failed", error)
            }
        }
        synchronized(codecLock) {
            releaseCodec()
            codecConfig = null
            videoWidth = 0
            videoHeight = 0
            fingerDown = false
            loggedFrame = false
        }
    }

    private fun closeStreams() {
        controlAlive = false
        controlHandshakeDone = false
        writerJob?.cancel()
        writerJob = null
        synchronized(codecLock) {
            releaseCodec()
            codecConfig = null
            videoWidth = 0
            videoHeight = 0
            fingerDown = false
            loggedFrame = false
        }
        val streams: List<AdbStream?>
        val connection: Dadb?
        synchronized(streamLock) {
            streams = listOf(video, control, shell)
            connection = link
            video = null
            control = null
            shell = null
            link = null
        }
        streams.forEach { stream ->
            try {
                stream?.close()
            } catch (error: Exception) {
                Log.w(TAG, "Closing a mirror stream failed", error)
            }
        }
        try {
            connection?.close()
        } catch (error: Exception) {
            Log.w(TAG, "Closing the mirror connection failed", error)
        }
    }

    private fun keep(current: Int, assign: () -> Unit): Boolean {
        synchronized(streamLock) {
            if (current != generation.get()) return false
            assign()
            return true
        }
    }

    private fun sendTouch(action: Int, x: Int, y: Int, width: Int, height: Int) {
        val payload = ScrcpyProtocol.touchEvent(action, x, y, width, height, pressure(action))
        controlOut.trySend(ControlMsg(action, payload))
    }

    private fun releaseCodec() {
        val decoder = codec ?: return
        codec = null
        try {
            decoder.stop()
        } catch (_: Exception) {
        }
        try {
            decoder.release()
        } catch (_: Exception) {
        }
    }

    private fun copyServer(): File {
        val file = File(context.cacheDir, "scrcpy-server.jar")
        context.assets.open("scrcpy-server.jar").use { input ->
            file.outputStream().use { output -> input.copyTo(output) }
        }
        return file
    }

    private fun pressure(action: Int): Float {
        return if (action == ScrcpyProtocol.ACTION_UP) 0f else 1f
    }

    private companion object {
        const val TAG = "TvRemoteMirror"
        const val MIME = "video/avc"
        const val CONNECT_TIMEOUT_MS = 12_000L
        const val RETRY_INTERVAL_MS = 300L
        const val MOVE_INTERVAL_NS = 1_000_000_000L / 60
        const val MIRROR_READ_TIMEOUT_MS = 0
        const val MAX_PACKET = 8 * 1024 * 1024
        const val FRAME_GAP_MS = 3_000L
        const val FIRST_FRAME_TIMEOUT_MS = 6_000L
        const val NO_FRAME_MS = 8_000L
        const val FRAME_CHECK_MS = 500L
        const val GRACE_MS = 60_000L
    }
}
