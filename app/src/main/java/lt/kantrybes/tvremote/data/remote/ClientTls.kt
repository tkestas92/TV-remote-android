package lt.kantrybes.tvremote.data.remote

import java.io.IOException
import java.net.ConnectException
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.security.Principal
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLException
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509ExtendedKeyManager
import javax.net.ssl.X509ExtendedTrustManager

internal class TlsConnection(
    val socket: SSLSocket,
    val serverCertificate: X509Certificate?,
)

internal fun connectTls(
    host: String,
    port: Int,
    identity: ClientIdentity,
    connectTimeoutMs: Int,
    readTimeoutMs: Int,
): TlsConnection {
    val ip = TvAddress.requireIpv4(host)
    val trustManager = CaptureTrustManager()
    val sslContext = SSLContext.getInstance("TLS")
    sslContext.init(
        arrayOf(FixedClientKeyManager(identity.privateKey, identity.certificate)),
        arrayOf(trustManager),
        SecureRandom(),
    )
    val sslSocket = sslContext.socketFactory.createSocket() as SSLSocket
    try {
        sslSocket.useClientMode = true
        sslSocket.tcpNoDelay = true
        val protocols = sslSocket.supportedProtocols.filter { it == "TLSv1.2" || it == "TLSv1.3" }
        if (protocols.isNotEmpty()) {
            sslSocket.enabledProtocols = protocols.toTypedArray()
        }
        sslSocket.connect(InetSocketAddress(ip, port), connectTimeoutMs)
        // 0 means block until data arrives. Keep a finite timeout only for the handshake,
        // then drop it so a quiet TV is not treated as a dead socket.
        sslSocket.soTimeout = if (readTimeoutMs == 0) HANDSHAKE_READ_TIMEOUT_MS else readTimeoutMs
        sslSocket.startHandshake()
        sslSocket.soTimeout = readTimeoutMs
        val serverCertificate = trustManager.serverCertificate
            ?: sslSocket.session.peerCertificates.filterIsInstance<X509Certificate>().firstOrNull()
        return TlsConnection(sslSocket, serverCertificate)
    } catch (error: SocketTimeoutException) {
        sslSocket.close()
        throw CannotConnectException(CannotConnectException.Kind.TIMEOUT, error)
    } catch (error: ConnectException) {
        sslSocket.close()
        throw CannotConnectException(CannotConnectException.Kind.REFUSED, error)
    } catch (error: SSLException) {
        sslSocket.close()
        throw CannotConnectException(CannotConnectException.Kind.TLS, error)
    } catch (error: IOException) {
        sslSocket.close()
        throw CannotConnectException(CannotConnectException.Kind.OTHER, error)
    }
}

private const val HANDSHAKE_READ_TIMEOUT_MS = 120_000

private class FixedClientKeyManager(
    private val privateKey: PrivateKey,
    private val certificate: X509Certificate,
) : X509ExtendedKeyManager() {
    override fun getClientAliases(keyType: String?, issuers: Array<out Principal>?): Array<String> {
        return arrayOf(ALIAS)
    }

    override fun chooseClientAlias(
        keyType: Array<out String>?,
        issuers: Array<out Principal>?,
        socket: Socket?,
    ): String = ALIAS

    override fun chooseEngineClientAlias(
        keyType: Array<out String>?,
        issuers: Array<out Principal>?,
        engine: SSLEngine?,
    ): String = ALIAS

    override fun getServerAliases(keyType: String?, issuers: Array<out Principal>?): Array<String>? = null

    override fun chooseServerAlias(
        keyType: String?,
        issuers: Array<out Principal>?,
        socket: Socket?,
    ): String? = null

    override fun getCertificateChain(alias: String?): Array<X509Certificate>? {
        return if (alias == ALIAS) arrayOf(certificate) else null
    }

    override fun getPrivateKey(alias: String?): PrivateKey? {
        return if (alias == ALIAS) privateKey else null
    }

    private companion object {
        const val ALIAS = "androidtv-remote"
    }
}

private class CaptureTrustManager : X509ExtendedTrustManager() {
    var serverCertificate: X509Certificate? = null

    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit

    override fun checkClientTrusted(
        chain: Array<out X509Certificate>?,
        authType: String?,
        socket: Socket?,
    ) = Unit

    override fun checkClientTrusted(
        chain: Array<out X509Certificate>?,
        authType: String?,
        engine: SSLEngine?,
    ) = Unit

    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        capture(chain)
    }

    override fun checkServerTrusted(
        chain: Array<out X509Certificate>?,
        authType: String?,
        socket: Socket?,
    ) {
        capture(chain)
    }

    override fun checkServerTrusted(
        chain: Array<out X509Certificate>?,
        authType: String?,
        engine: SSLEngine?,
    ) {
        capture(chain)
    }

    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()

    private fun capture(chain: Array<out X509Certificate>?) {
        serverCertificate = chain?.firstOrNull()
    }
}
