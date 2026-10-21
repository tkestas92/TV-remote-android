package lt.kantrybes.tvremote.data.scrcpy

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.random.Random

/**
 * scrcpy 4.1 wire format.
 *
 * Video socket, forward mode (DesktopConnection + Streamer):
 * dummy byte, device name (64 bytes), codec id (u32), then 12-byte packets.
 * Bit 63 set means a session packet (width, height). Otherwise the header is
 * PTS/flags (config = bit 62, key frame = bit 61) and a u32 size, then the payload.
 *
 * Touch events follow ControlMessageReader.parseInjectTouchEvent (32 bytes).
 */
internal object ScrcpyProtocol {
    const val VERSION = "4.1"
    const val REMOTE_JAR = "/data/local/tmp/scrcpy-server.jar"
    const val DEVICE_NAME_LENGTH = 64
    const val HEADER_LENGTH = 12
    const val CODEC_H264 = 0x68323634
    const val TOUCH_LENGTH = 32
    const val TYPE_INJECT_TOUCH_EVENT = 2
    /** scrcpy 4.1 ControlMessage.TYPE_RESET_VIDEO. The wire message is this byte alone. */
    const val TYPE_RESET_VIDEO = 17
    const val ACTION_DOWN = 0
    const val ACTION_UP = 1
    const val ACTION_MOVE = 2
    const val POINTER_ID_FINGER = -2L

    private const val FLAG_SESSION = 1L shl 63
    private const val FLAG_CONFIG = 1L shl 62
    private const val FLAG_KEY_FRAME = 1L shl 61
    private const val PTS_MASK = (1L shl 61) - 1

    fun randomScid(random: Random = Random): String {
        val value = random.nextInt(0x7FFFFFFF)
        return "%08x".format(value)
    }

    fun socket(scid: String): String = "localabstract:scrcpy_$scid"

    fun resetVideo(): ByteArray = byteArrayOf(TYPE_RESET_VIDEO.toByte())

    /** Annex-B IDR, NAL type 5. A packet may carry several NALs. */
    fun isIdrFrame(payload: ByteArray): Boolean {
        var index = 0
        while (index < payload.size) {
            val marker = startCodeLength(payload, index)
            if (marker == 0) {
                index++
                continue
            }
            val header = index + marker
            if (header >= payload.size) return false
            if ((payload[header].toInt() and 0x1F) == 5) return true
            index = header + 1
        }
        return false
    }

    fun remoteJarMd5(output: String): String? {
        val line = output.lineSequence()
            .map { it.trim().lowercase() }
            .firstOrNull { candidate ->
                candidate.length >= 32 && candidate.take(32).all { it in '0'..'9' || it in 'a'..'f' }
            } ?: return null
        return line.take(32)
    }

    fun jarIsCurrent(remoteMd5: String?, remoteSize: Long?, assetMd5: String, assetSize: Long): Boolean {
        if (remoteMd5 != null) return remoteMd5 == assetMd5.lowercase()
        return remoteSize != null && remoteSize == assetSize
    }

    private fun startCodeLength(payload: ByteArray, index: Int): Int {
        if (
            index + 4 <= payload.size &&
            payload[index] == 0.toByte() &&
            payload[index + 1] == 0.toByte() &&
            payload[index + 2] == 0.toByte() &&
            payload[index + 3] == 1.toByte()
        ) {
            return 4
        }
        if (
            index + 3 <= payload.size &&
            payload[index] == 0.toByte() &&
            payload[index + 1] == 0.toByte() &&
            payload[index + 2] == 1.toByte()
        ) {
            return 3
        }
        return 0
    }

    fun serverCommand(scid: String, preset: MirrorPreset = MirrorPreset.Smooth): String {
        return "export CLASSPATH=$REMOTE_JAR; app_process / com.genymobile.scrcpy.Server $VERSION " +
            "scid=$scid tunnel_forward=true audio=false control=true " +
            "max_size=${preset.maxSize} video_bit_rate=${preset.bitRate} max_fps=${preset.maxFps} video_codec=h264"
    }

    fun coalesceMoves(messages: List<ControlMsg>): List<ControlMsg> {
        val kept = ArrayList<ControlMsg>(messages.size)
        var pendingMove: ControlMsg? = null
        for (message in messages) {
            if (message.action == ACTION_MOVE) {
                pendingMove = message
            } else {
                pendingMove?.let { kept.add(it) }
                pendingMove = null
                kept.add(message)
            }
        }
        pendingMove?.let { kept.add(it) }
        return kept
    }

    fun deviceName(bytes: ByteArray): String {
        val end = bytes.indexOf(0).let { if (it < 0) bytes.size else it }
        return bytes.decodeToString(0, end)
    }

    fun codecId(bytes: ByteArray): Int = u32(bytes, 0)

    fun parseHeader(header: ByteArray): PacketHeader {
        val bits = u64(header, 0)
        return if (bits and FLAG_SESSION != 0L) {
            PacketHeader.Session(width = u32(header, 4), height = u32(header, 8))
        } else {
            PacketHeader.Media(
                pts = bits and PTS_MASK,
                config = bits and FLAG_CONFIG != 0L,
                keyFrame = bits and FLAG_KEY_FRAME != 0L,
                size = u32(header, 8),
            )
        }
    }

    fun touchEvent(action: Int, x: Int, y: Int, width: Int, height: Int, pressure: Float): ByteArray {
        val buffer = ByteBuffer.allocate(TOUCH_LENGTH).order(ByteOrder.BIG_ENDIAN)
        buffer.put(TYPE_INJECT_TOUCH_EVENT.toByte())
        buffer.put(action.toByte())
        buffer.putLong(POINTER_ID_FINGER)
        buffer.putInt(x)
        buffer.putInt(y)
        buffer.putShort(width.toShort())
        buffer.putShort(height.toShort())
        buffer.putShort(pressureFixed(pressure).toShort())
        buffer.putInt(0)
        buffer.putInt(0)
        return buffer.array()
    }

    /**
     * Maps a point in the view onto the video frame. The frame is fitted inside
     * the view, so bars around it are not part of the video.
     */
    fun mapTouch(
        viewX: Float,
        viewY: Float,
        viewWidth: Float,
        viewHeight: Float,
        videoWidth: Int,
        videoHeight: Int,
    ): Pair<Int, Int>? {
        if (viewWidth <= 0f || viewHeight <= 0f || videoWidth <= 0 || videoHeight <= 0) return null
        val videoAspect = videoWidth.toFloat() / videoHeight.toFloat()
        val viewAspect = viewWidth / viewHeight
        val displayWidth: Float
        val displayHeight: Float
        val offsetX: Float
        val offsetY: Float
        if (viewAspect > videoAspect) {
            displayHeight = viewHeight
            displayWidth = viewHeight * videoAspect
            offsetX = (viewWidth - displayWidth) / 2f
            offsetY = 0f
        } else {
            displayWidth = viewWidth
            displayHeight = viewWidth / videoAspect
            offsetX = 0f
            offsetY = (viewHeight - displayHeight) / 2f
        }
        val localX = viewX - offsetX
        val localY = viewY - offsetY
        if (localX < 0f || localY < 0f || localX > displayWidth || localY > displayHeight) return null
        val x = (localX / displayWidth * videoWidth).toInt().coerceIn(0, videoWidth - 1)
        val y = (localY / displayHeight * videoHeight).toInt().coerceIn(0, videoHeight - 1)
        return x to y
    }

    private fun pressureFixed(pressure: Float): Int {
        val scaled = (pressure.coerceIn(0f, 1f) * 65536f).toInt()
        return scaled.coerceIn(0, 0xFFFF)
    }

    private fun u32(bytes: ByteArray, offset: Int): Int {
        return ((bytes[offset].toInt() and 0xFF) shl 24) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
            (bytes[offset + 3].toInt() and 0xFF)
    }

    private fun u64(bytes: ByteArray, offset: Int): Long {
        var value = 0L
        for (index in 0 until 8) {
            value = (value shl 8) or (bytes[offset + index].toLong() and 0xFF)
        }
        return value
    }
}

/**
 * Software-encoder sizes. [maxSize] is the long edge; a 16:9 TV becomes
 * [maxSize] by [maxSize] * 9 / 16, and both stay multiples of 16.
 */
internal enum class MirrorPreset(
    val maxSize: Int,
    val maxFps: Int,
    val bitRate: Int,
    val wire: String,
) {
    Smooth(maxSize = 768, maxFps = 24, bitRate = 2_500_000, wire = "smooth"),
    Light(maxSize = 512, maxFps = 12, bitRate = 1_000_000, wire = "light"),
    ;

    fun fitsEncoder(): Boolean {
        val height = maxSize * 9 / 16
        return maxSize % 16 == 0 && height % 16 == 0
    }

    companion object {
        fun fromStored(value: String?): MirrorPreset {
            return entries.firstOrNull { it.wire == value } ?: Smooth
        }
    }
}

/**
 * Light while the TV is playing, unless the user just picked a preset.
 * After playback has been stopped, an automatic Light switch returns to Smooth.
 */
internal fun mirrorPresetWhilePlaying(
    saved: MirrorPreset,
    videoPlaying: Boolean,
    userOverride: Boolean,
): MirrorPreset {
    if (videoPlaying && !userOverride) return MirrorPreset.Light
    return saved
}

internal fun mirrorPresetAfterPlayback(saved: MirrorPreset, autoSwitchedToLight: Boolean): MirrorPreset {
    return if (autoSwitchedToLight) MirrorPreset.Smooth else saved
}

internal data class ControlMsg(val action: Int, val payload: ByteArray)

internal sealed interface PacketHeader {
    data class Session(val width: Int, val height: Int) : PacketHeader
    data class Media(
        val pts: Long,
        val config: Boolean,
        val keyFrame: Boolean,
        val size: Int,
    ) : PacketHeader
}
