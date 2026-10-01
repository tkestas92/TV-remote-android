package lt.kantrybes.tvremote.data.remote

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WakeOnLanTest {
    @Test
    fun magicPacketRepeatsTheMacSixteenTimes() {
        val packet = WakeOnLan.magicPacket("44:d8:78:15:c8:f2")!!
        assertEquals(102, packet.size)
        assertArrayEquals(ByteArray(6) { 0xFF.toByte() }, packet.copyOfRange(0, 6))
        val mac = byteArrayOf(0x44, 0xD8.toByte(), 0x78, 0x15, 0xC8.toByte(), 0xF2.toByte())
        for (repeat in 0 until 16) {
            assertArrayEquals(mac, packet.copyOfRange(6 + repeat * 6, 12 + repeat * 6))
        }
    }

    @Test
    fun acceptsDashesAndRejectsShortAddresses() {
        assertArrayEquals(
            byteArrayOf(0x44, 0xD8.toByte(), 0x78, 0x15, 0xC8.toByte(), 0xF2.toByte()),
            WakeOnLan.parseMac("44-d8-78-15-c8-f2"),
        )
        assertNull(WakeOnLan.parseMac("44:d8:78:15:c8"))
        assertEquals(WakeOnLan.DEFAULT_MAC, WakeOnLan.resolveMac("not-a-mac"))
    }
}
