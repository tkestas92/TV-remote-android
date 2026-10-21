package lt.kantrybes.tvremote.data.remote

import android.net.Network
import java.net.DatagramSocket
import javax.net.SocketFactory

/**
 * The active Wi-Fi [Network]. Command sockets are created from its
 * [SocketFactory] so a weak Wi-Fi is not routed onto another interface.
 */
internal class WifiNetworks {
    @Volatile var network: Network? = null

    fun socketFactory(): SocketFactory = network?.socketFactory ?: SocketFactory.getDefault()

    fun bind(socket: DatagramSocket) {
        network?.bindSocket(socket)
    }
}
