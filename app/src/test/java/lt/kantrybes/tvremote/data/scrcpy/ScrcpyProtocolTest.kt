package lt.kantrybes.tvremote.data.scrcpy

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScrcpyProtocolTest {
    @Test
    fun serverCommandMatchesScrcpy41() {
        val command = ScrcpyProtocol.serverCommand("0abc1234")

        assertTrue(command.startsWith("export CLASSPATH=/data/local/tmp/scrcpy-server.jar; app_process / "))
        assertFalse(command.contains("\""))
        assertTrue(command.contains("com.genymobile.scrcpy.Server 4.1 "))
        assertTrue(command.contains("scid=0abc1234"))
        assertTrue(command.contains("tunnel_forward=true"))
        assertTrue(command.contains("audio=false"))
        assertTrue(command.contains("control=true"))
        assertTrue(command.contains("max_size=768"))
        assertTrue(command.contains("video_bit_rate=2500000"))
        assertTrue(command.contains("max_fps=24"))
        assertTrue(command.contains("video_codec=h264"))
        assertTrue(command.contains("audio=false"))
        assertFalse(command.contains("video_encoder="))
        assertFalse(command.contains("list_encoders"))
        assertFalse(command.contains("854"))
        assertEquals("localabstract:scrcpy_0abc1234", ScrcpyProtocol.socket("0abc1234"))
    }

    @Test
    fun lightPresetUsesTheSmallerEncoderSize() {
        val command = ScrcpyProtocol.serverCommand("0abc1234", MirrorPreset.Light)

        assertTrue(command.contains("max_size=512"))
        assertTrue(command.contains("video_bit_rate=1000000"))
        assertTrue(command.contains("max_fps=12"))
        assertTrue(command.contains("video_codec=h264"))
        assertTrue(MirrorPreset.Smooth.fitsEncoder())
        assertTrue(MirrorPreset.Light.fitsEncoder())
        assertEquals(MirrorPreset.Smooth, MirrorPreset.fromStored(null))
        assertEquals(MirrorPreset.Light, MirrorPreset.fromStored("light"))
        assertEquals(MirrorPreset.Light, mirrorPresetWhilePlaying(MirrorPreset.Smooth, videoPlaying = true, userOverride = false))
        assertEquals(MirrorPreset.Smooth, mirrorPresetWhilePlaying(MirrorPreset.Smooth, videoPlaying = true, userOverride = true))
        assertEquals(MirrorPreset.Light, mirrorPresetWhilePlaying(MirrorPreset.Light, videoPlaying = false, userOverride = false))
        assertEquals(MirrorPreset.Smooth, mirrorPresetAfterPlayback(MirrorPreset.Smooth, autoSwitchedToLight = true))
        assertEquals(MirrorPreset.Light, mirrorPresetAfterPlayback(MirrorPreset.Light, autoSwitchedToLight = false))
    }

    @Test
    fun resetVideoIsTheTypeByteOnly() {
        assertArrayEquals(byteArrayOf(17), ScrcpyProtocol.resetVideo())
    }

    @Test
    fun idrNalIsTypeFive() {
        val idr = byteArrayOf(0, 0, 0, 1, 0x65, 0, 1)
        val slice = byteArrayOf(0, 0, 0, 1, 0x41, 0, 1)

        assertTrue(ScrcpyProtocol.isIdrFrame(idr))
        assertFalse(ScrcpyProtocol.isIdrFrame(slice))
    }

    @Test
    fun jarCheckPrefersMd5AndFallsBackToSize() {
        assertEquals("abc123abc123abc123abc123abc123ab", ScrcpyProtocol.remoteJarMd5("abc123abc123abc123abc123abc123ab  /data/local/tmp/scrcpy-server.jar"))
        assertNull(ScrcpyProtocol.remoteJarMd5("not found"))
        assertTrue(ScrcpyProtocol.jarIsCurrent("aa", null, "aa", 10))
        assertFalse(ScrcpyProtocol.jarIsCurrent("bb", 10, "aa", 10))
        assertTrue(ScrcpyProtocol.jarIsCurrent(null, 10, "aa", 10))
        assertFalse(ScrcpyProtocol.jarIsCurrent(null, null, "aa", 10))
    }

    @Test
    fun moveEventsKeepOnlyTheLatest() {
        val down = ControlMsg(ScrcpyProtocol.ACTION_DOWN, byteArrayOf(1))
        val move1 = ControlMsg(ScrcpyProtocol.ACTION_MOVE, byteArrayOf(2))
        val move2 = ControlMsg(ScrcpyProtocol.ACTION_MOVE, byteArrayOf(3))
        val up = ControlMsg(ScrcpyProtocol.ACTION_UP, byteArrayOf(4))

        val merged = ScrcpyProtocol.coalesceMoves(listOf(down, move1, move2, up))

        assertEquals(listOf(ScrcpyProtocol.ACTION_DOWN, ScrcpyProtocol.ACTION_MOVE, ScrcpyProtocol.ACTION_UP), merged.map { it.action })
        assertEquals(3, merged[1].payload[0].toInt())
    }

    @Test
    fun sessionPacketCarriesWidthAndHeight() {
        val header = byteArrayOf(
            0x80.toByte(), 0, 0, 0,
            0, 0, 0x05, 0x00,
            0, 0, 0x02, 0xD0.toByte(),
        )

        val packet = ScrcpyProtocol.parseHeader(header) as PacketHeader.Session

        assertEquals(1280, packet.width)
        assertEquals(720, packet.height)
    }

    @Test
    fun mediaHeaderKeepsConfigKeyframeAndSize() {
        val header = ByteArray(12)
        header[0] = 0x60
        header[11] = 16

        val packet = ScrcpyProtocol.parseHeader(header) as PacketHeader.Media

        assertTrue(packet.config)
        assertTrue(packet.keyFrame)
        assertEquals(0L, packet.pts)
        assertEquals(16, packet.size)
    }

    @Test
    fun touchEventIs32BytesForAFinger() {
        val message = ScrcpyProtocol.touchEvent(
            action = ScrcpyProtocol.ACTION_DOWN,
            x = 10,
            y = 20,
            width = 1920,
            height = 1080,
            pressure = 1f,
        )

        assertEquals(32, message.size)
        assertEquals(2, message[0].toInt())
        assertEquals(0, message[1].toInt())
        assertArrayEquals(byteArrayOf(-1, -1, -1, -1, -1, -1, -1, -2), message.copyOfRange(2, 10))
        assertEquals(10, message[13].toInt())
        assertEquals(20, message[17].toInt())
        assertEquals(0x07, message[18].toInt() and 0xFF)
        assertEquals(0x80, message[19].toInt() and 0xFF)
        assertEquals(0x04, message[20].toInt() and 0xFF)
        assertEquals(0x38, message[21].toInt() and 0xFF)
        assertEquals(0xFF.toByte(), message[22])
        assertEquals(0xFF.toByte(), message[23])
    }

    @Test
    fun mappingFollowsTheLaidOutVideoRect() {
        assertNull(ScrcpyProtocol.mapTouch(10f, 200f, 800f, 400f, 768, 432))
        val landscape = ScrcpyProtocol.mapTouch(400f, 200f, 800f, 400f, 768, 432)
        assertTrue(kotlin.math.abs((landscape?.first ?: 0) - 384) <= 1)
        assertEquals(216, landscape?.second)

        assertNull(ScrcpyProtocol.mapTouch(200f, 10f, 400f, 800f, 768, 432))
        assertEquals(384 to 216, ScrcpyProtocol.mapTouch(200f, 400f, 400f, 800f, 768, 432))
    }

    @Test
    fun touchOutsideTheFittedFrameIsIgnored() {
        assertNull(
            ScrcpyProtocol.mapTouch(
                viewX = 10f,
                viewY = 10f,
                viewWidth = 200f,
                viewHeight = 200f,
                videoWidth = 200,
                videoHeight = 100,
            ),
        )
        assertEquals(
            100 to 50,
            ScrcpyProtocol.mapTouch(100f, 100f, 200f, 200f, 200, 100),
        )
    }

    @Test
    fun scidFitsIn31Bits() {
        repeat(32) {
            val scid = ScrcpyProtocol.randomScid()
            assertEquals(8, scid.length)
            val value = scid.toLong(16)
            assertTrue(value >= 0L)
            assertTrue(value < 0x7FFFFFFF)
        }
    }
}
