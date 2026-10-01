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
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException

internal sealed interface MirrorState {
    data object Idle : MirrorState
    data object Connecting : MirrorState
    data object StartingServer : MirrorState
    data class Streaming(val width: Int, val height: Int) : MirrorState
    data class Failed(val message: String) : MirrorState
}

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
    private var codecLabel = "h264"
    private var phase = "start"
    private var startedAt = 0L
    private val serverLog = StringBuilder()
    @Volatile private var serverExited = false
    @Volatile private var videoConnected = false

    private val _state = MutableStateFlow<MirrorState>(MirrorState.Idle)
    val state: StateFlow<MirrorState> = _state.asStateFlow()

    fun start(host: String) {
        val current = generation.incrementAndGet()
        job?.cancel()
        val previous = controlOut
        controlOut = Channel(Channel.UNLIMITED)
        previous.close()
        startedAt = System.currentTimeMillis()
        loggedStart = false
        loggedFrame = false
        phase = "start"
        _state.value = MirrorState.Connecting
        job = appScope.launch(Dispatchers.IO) {
            try {
                withContext(NonCancellable) { closeStreams() }
                if (current != generation.get()) return@launch
                run(host, current)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (current == generation.get() && _state.value !is MirrorState.Failed) {
                    Log.w(TAG, "Mirror failed at $phase", error)
                    _state.value = MirrorState.Failed(detail(phase, reason(error)))
                }
            } finally {
                if (current == generation.get()) {
                    withContext(NonCancellable) { closeStreams() }
                }
            }
        }
    }

    fun stop() {
        val token = generation.incrementAndGet()
        job?.cancel()
        job = null
        controlOut.close()
        _state.value = MirrorState.Idle
        appScope.launch(Dispatchers.IO + NonCancellable) {
            if (token == generation.get()) closeStreams()
        }
    }

    fun setSurface(next: Surface?) {
        try {
            synchronized(codecLock) {
                if (surface === next) return
                releaseCodec()
                surface?.release()
                surface = next
                restartCodec()
            }
        } catch (error: Exception) {
            Log.w(TAG, "Decoder surface failed", error)
        }
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

    private suspend fun run(host: String, current: Int) {
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
        if (!pushServer(connection, current)) return
        if (current != generation.get()) return
        launchMirror(connection, current)
    }

    private suspend fun pushServer(connection: Dadb, current: Int): Boolean {
        phase = "jar push"
        val jar = copyServer()
        val remoteSize = AdbShell.exec(connection, "stat -c %s ${ScrcpyProtocol.REMOTE_JAR}")
            .lineSequence()
            .map { it.trim() }
            .firstOrNull { it.isNotEmpty() && it.all(Char::isDigit) }
        if (remoteSize == jar.length().toString()) {
            logStep("push skipped")
            return current == generation.get()
        }
        when (val pushed = connection.push(jar, ScrcpyProtocol.REMOTE_JAR)) {
            is SyncResult.Failure -> {
                Log.w(TAG, "Server push failed: ${pushed.reason}")
                fail(current, detail("jar push", pushed.reason))
                return false
            }
            SyncResult.Success -> logStep("push done")
        }
        return current == generation.get()
    }

    private suspend fun launchMirror(connection: Dadb, current: Int): Boolean {
        if (current != generation.get()) return false
        try {
            phase = "server"
            val scid = ScrcpyProtocol.randomScid()
            val command = ScrcpyProtocol.serverCommand(scid)
            try {
                AdbShell.exec(connection, "pkill -f 'scrcpy-serve[r]' || true")
            } catch (error: Exception) {
                Log.i(TAG, "pkill finished: ${reason(error)}")
            }
            if (current != generation.get()) return false
            Log.i(TAG, command)
            resetServerLog()
            val server = connection.open("shell:$command")
            if (!keep(current) { shell = server }) {
                server.close()
                return false
            }
            watchServer(server)
            logStep("server started")
            if (current == generation.get()) _state.value = MirrorState.StartingServer
            val destination = ScrcpyProtocol.socket(scid)
            phase = "video socket"
            val videoStream = openWhenReady(connection, destination, current, "video socket") ?: return false
            if (!keep(current) { video = videoStream }) {
                videoStream.close()
                return false
            }
            videoConnected = true
            logStep("video socket connected")
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
                _state.value = MirrorState.Failed(detail(phase, reason(error)))
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
        var sawMeta = false
        var sawPacket = false
        while (current == generation.get() && coroutineContext.isActive) {
            phase = if (sawMeta) "video packet" else "codec meta"
            val header = ScrcpyProtocol.parseHeader(source.readByteArray(ScrcpyProtocol.HEADER_LENGTH.toLong()))
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
                        onConfig(payload)
                    } else {
                        feed(payload, header.pts)
                    }
                }
            }
        }
    }

    private fun onSession(width: Int, height: Int, current: Int) {
        if (width <= 0 || height <= 0) return
        synchronized(codecLock) {
            val changed = width != videoWidth || height != videoHeight
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
            }
            if (changed) {
                codecConfig = null
                releaseCodec()
            }
        }
    }

    private fun onConfig(payload: ByteArray) {
        synchronized(codecLock) {
            codecConfig = payload
            restartCodec()
        }
    }

    private fun feed(payload: ByteArray, pts: Long) {
        synchronized(codecLock) {
            val decoder = codec ?: return
            var index = decoder.dequeueInputBuffer(10_000)
            if (index < 0) {
                drain(decoder)
                index = decoder.dequeueInputBuffer(10_000)
            }
            if (index < 0) return
            val input = decoder.getInputBuffer(index) ?: return
            input.clear()
            input.put(payload)
            decoder.queueInputBuffer(index, 0, payload.size, pts, 0)
            drain(decoder)
        }
    }

    private fun restartCodec() {
        releaseCodec()
        val output = surface ?: return
        val config = codecConfig ?: return
        if (videoWidth <= 0 || videoHeight <= 0) return
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
        } catch (error: Exception) {
            decoder.release()
            throw error
        }
    }

    private fun drain(decoder: MediaCodec) {
        val info = MediaCodec.BufferInfo()
        val ready = ArrayList<Int>(4)
        while (true) {
            val index = decoder.dequeueOutputBuffer(info, 0)
            if (index < 0) break
            ready.add(index)
        }
        if (ready.isEmpty()) return
        for (index in 0 until ready.lastIndex) {
            decoder.releaseOutputBuffer(ready[index], false)
        }
        decoder.releaseOutputBuffer(ready.last(), true)
        if (!loggedFrame) {
            loggedFrame = true
            logStep("first frame")
        }
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
        if (current == generation.get()) _state.value = MirrorState.Failed(message)
    }

    private fun closeSessionStreams() {
        controlAlive = false
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
    }
}
