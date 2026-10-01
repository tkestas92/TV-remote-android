package lt.kantrybes.tvremote.data.remote

import android.util.Log
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

/**
 * Magic packet for the TV's Wi-Fi MAC. Sent before each remote connect and
 * again on the retry interval while the command session is still down.
 */
internal object WakeOnLan {
    const val DEFAULT_MAC = "44:d8:78:15:c8:f2"

    fun magicPacket(mac: String): ByteArray? {
        val address = parseMac(mac) ?: return null
        val packet = ByteArray(6 + 16 * 6)
        for (index in 0 until 6) packet[index] = 0xFF.toByte()
        for (repeat in 0 until 16) {
            address.copyInto(packet, destinationOffset = 6 + repeat * 6)
        }
        return packet
    }

    fun parseMac(mac: String): ByteArray? {
        val parts = mac.trim().replace('-', ':').split(':')
        if (parts.size != 6 || parts.any { it.length != 2 }) return null
        val bytes = ByteArray(6)
        for (index in parts.indices) {
            val value = parts[index].toIntOrNull(16) ?: return null
            bytes[index] = value.toByte()
        }
        return bytes
    }

    fun resolveMac(mac: String): String {
        return if (parseMac(mac) != null) mac.trim() else DEFAULT_MAC
    }

    /** @return true when at least one broadcast left the socket. */
    fun send(mac: String, bind: (DatagramSocket) -> Unit): Boolean {
        val payload = magicPacket(resolveMac(mac)) ?: return false
        return try {
            DatagramSocket().use { socket ->
                socket.broadcast = true
                socket.soTimeout = SEND_TIMEOUT_MS
                try {
                    bind(socket)
                } catch (error: Exception) {
                    Log.w(TAG, "WoL bind failed: ${error.message}")
                }
                var sent = false
                for (target in TARGETS) {
                    try {
                        val packet = DatagramPacket(
                            payload,
                            payload.size,
                            InetAddress.getByName(target),
                            PORT,
                        )
                        socket.send(packet)
                        sent = true
                    } catch (error: Exception) {
                        Log.w(TAG, "WoL send to $target failed: ${error.message}")
                    }
                }
                sent
            }
        } catch (error: Exception) {
            Log.w(TAG, "WoL failed: ${error.message}")
            false
        }
    }

    private val TARGETS = listOf("255.255.255.255", "192.168.0.255")
    private const val PORT = 9
    private const val SEND_TIMEOUT_MS = 1_000
    private const val TAG = "TvRemoteSession"
}
