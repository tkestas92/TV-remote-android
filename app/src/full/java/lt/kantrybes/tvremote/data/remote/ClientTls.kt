package lt.kantrybes.tvremote.data.remote

import java.io.IOException
import java.net.ConnectException
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.security.MessageDigest
import java.security.Principal
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.SocketFactory
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
    handshakeTimeoutMs: Int = if (readTimeoutMs == 0) DEFAULT_HANDSHAKE_TIMEOUT_MS else readTimeoutMs,
    socketFactory: SocketFactory = SocketFactory.getDefault(),
    onSocket: (Socket) -> Unit = {},
    pinnedSha256: ByteArray? = null,
): TlsConnection {
    val ip = TvAddress.requireIpv4(host)
    val trustManager = CaptureTrustManager(pinnedSha256)
    val sslContext = SSLContext.getInstance("TLS")
    sslContext.init(
        arrayOf(FixedClientKeyManager(identity.privateKey, identity.certificate)),
        arrayOf(trustManager),
        SecureRandom(),
    )
    val plain = socketFactory.createSocket()
    var open: Socket = plain
    onSocket(plain)
    try {
        plain.tcpNoDelay = true
        plain.soTimeout = handshakeTimeoutMs
        plain.connect(InetSocketAddress(ip, port), connectTimeoutMs)
        val sslSocket = sslContext.socketFactory.createSocket(plain, ip, port, true) as SSLSocket
        open = sslSocket
        onSocket(sslSocket)
        sslSocket.useClientMode = true
        sslSocket.tcpNoDelay = true
        val protocols = sslSocket.supportedProtocols.filter { it == "TLSv1.2" || it == "TLSv1.3" }
        if (protocols.isNotEmpty()) {
            sslSocket.enabledProtocols = protocols.toTypedArray()
        }
        sslSocket.soTimeout = handshakeTimeoutMs
        sslSocket.startHandshake()
        val steadyTimeout = if (readTimeoutMs > 0) readTimeoutMs else handshakeTimeoutMs
        sslSocket.soTimeout = steadyTimeout
        plain.soTimeout = steadyTimeout
        val serverCertificate = trustManager.serverCertificate
            ?: sslSocket.session.peerCertificates.filterIsInstance<X509Certificate>().firstOrNull()
        return TlsConnection(sslSocket, serverCertificate)
    } catch (error: SocketTimeoutException) {
        open.close()
        throw CannotConnectException(CannotConnectException.Kind.TIMEOUT, error)
    } catch (error: ConnectException) {
        open.close()
        throw CannotConnectException(CannotConnectException.Kind.REFUSED, error)
    } catch (error: SSLException) {
        open.close()
        throw CannotConnectException(CannotConnectException.Kind.TLS, error)
    } catch (error: IOException) {
        open.close()
        throw CannotConnectException(CannotConnectException.Kind.OTHER, error)
    }
}

private const val DEFAULT_HANDSHAKE_TIMEOUT_MS = 5_000

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

private class CaptureTrustManager(
    private val pinnedSha256: ByteArray?,
) : X509ExtendedTrustManager() {
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
        val certificate = chain?.firstOrNull()
        serverCertificate = certificate
        val expected = pinnedSha256 ?: return
        if (certificate == null) {
            throw CertificateException("TV certificate missing")
        }
        val actual = MessageDigest.getInstance("SHA-256").digest(certificate.encoded)
        if (!MessageDigest.isEqual(expected, actual)) {
            throw TvCertificateChangedException()
        }
    }
}
