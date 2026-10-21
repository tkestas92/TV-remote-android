package lt.kantrybes.tvremote.data.remote

import java.io.IOException
import java.net.ConnectException
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException

internal object ConnectionTester {
    private const val TIMEOUT_MS = 3_000

    fun test(host: String, port: Int = PAIRING_PORT) {
        val ip = TvAddress.requireIpv4(host)
        try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(ip, port), TIMEOUT_MS)
            }
        } catch (error: SocketTimeoutException) {
            throw CannotConnectException(CannotConnectException.Kind.TIMEOUT, error)
        } catch (error: ConnectException) {
            throw CannotConnectException(CannotConnectException.Kind.REFUSED, error)
        } catch (error: IOException) {
            throw CannotConnectException(CannotConnectException.Kind.OTHER, error)
        }
    }
}
