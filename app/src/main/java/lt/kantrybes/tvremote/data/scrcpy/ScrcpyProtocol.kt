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
    const val ACTION_DOWN = 0
    const val ACTION_UP = 1
    const val ACTION_MOVE = 2
    const val POINTER_ID_FINGER = -2L
    const val MAX_SIZE = 768
    const val BIT_RATE = 2_500_000
    const val MAX_FPS = 24

    private const val FLAG_SESSION = 1L shl 63
    private const val FLAG_CONFIG = 1L shl 62
    private const val FLAG_KEY_FRAME = 1L shl 61
    private const val PTS_MASK = (1L shl 61) - 1

    fun randomScid(random: Random = Random): String {
        val value = random.nextInt(0x7FFFFFFF)
        return "%08x".format(value)
    }

    fun socket(scid: String): String = "localabstract:scrcpy_$scid"

    fun serverCommand(scid: String): String {
        return "export CLASSPATH=$REMOTE_JAR; app_process / com.genymobile.scrcpy.Server $VERSION " +
            "scid=$scid tunnel_forward=true audio=false control=true " +
            "max_size=$MAX_SIZE video_bit_rate=$BIT_RATE max_fps=$MAX_FPS video_codec=h264"
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
