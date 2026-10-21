package lt.kantrybes.tvremote.data.remote

import android.util.Log
import com.google.polo.wire.protobuf.PoloProto
import com.google.protobuf.ByteString
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.security.cert.X509Certificate
import javax.net.ssl.SSLSocket

/**
 * One Android TV Remote v2 pairing attempt on port 6467.
 *
 * [openAndRequestCode] runs through configuration acknowledgement, which is when the TV
 * shows the 6-character code. [submitCode] sends the pairing secret and keeps the socket
 * open if the code fails the local check, so the user can correct a typo.
 */
internal class PairingSession(
    private val host: String,
    private val identity: ClientIdentity,
    private val port: Int = PAIRING_PORT,
) {
    private var socket: SSLSocket? = null
    private var input: InputStream? = null
    private var output: OutputStream? = null
    private var serverCertificate: X509Certificate? = null

    fun openAndRequestCode() {
        connect()
        try {
            send(pairingRequest())
            val acknowledgement = readMessage()
            ensureOk(acknowledgement)
            if (!acknowledgement.hasPairingRequestAck()) {
                throw PairingProtocolException(PairingProtocolException.Kind.UNEXPECTED)
            }
            send(options())
            val options = readMessage()
            ensureOk(options)
            if (!options.hasOptions()) {
                throw PairingProtocolException(PairingProtocolException.Kind.UNEXPECTED)
            }
            send(configuration())
            val configured = readMessage()
            ensureOk(configured)
            if (!configured.hasConfigurationAck()) {
                throw PairingProtocolException(PairingProtocolException.Kind.UNEXPECTED)
            }
            Log.d(TAG, "TV is showing a pairing code")
        } catch (error: PairingProtocolException) {
            close()
            throw error
        } catch (error: IOException) {
            close()
            throw PairingProtocolException(PairingProtocolException.Kind.CLOSED, error)
        }
    }

    fun submitCode(pairingCode: String) {
        val server = serverCertificate
            ?: throw PairingProtocolException(PairingProtocolException.Kind.CLOSED)
        val secret = PairingSecret.compute(identity.certificate, server, pairingCode)
        try {
            send(secretMessage(secret))
            val acknowledgement = readMessage()
            ensureOk(acknowledgement)
            if (!acknowledgement.hasSecretAck()) {
                throw PairingProtocolException(PairingProtocolException.Kind.UNEXPECTED)
            }
            Log.d(TAG, "Pairing succeeded")
        } catch (error: PairingProtocolException) {
            close()
            throw error
        } catch (error: IOException) {
            close()
            throw PairingProtocolException(PairingProtocolException.Kind.CLOSED, error)
        }
        close()
    }

    fun serverFingerprint(): ByteArray? {
        val certificate = serverCertificate ?: return null
        return MessageDigest.getInstance("SHA-256").digest(certificate.encoded)
    }

    fun close() {
        val current = socket
        socket = null
        input = null
        output = null
        try {
            current?.close()
        } catch (_: IOException) {
        }
    }

    private fun connect() {
        val connection = connectTls(
            host = host,
            port = port,
            identity = identity,
            connectTimeoutMs = CONNECT_TIMEOUT_MS,
            readTimeoutMs = READ_TIMEOUT_MS,
        )
        socket = connection.socket
        input = connection.socket.inputStream
        output = connection.socket.outputStream
        val peer = connection.serverCertificate
        if (peer == null) {
            close()
            throw PairingProtocolException(PairingProtocolException.Kind.UNEXPECTED)
        }
        serverCertificate = peer
        Log.d(TAG, "TLS connected for pairing")
    }

    private fun send(message: PoloProto.OuterMessage) {
        val current = output ?: throw PairingProtocolException(PairingProtocolException.Kind.CLOSED)
        current.write(ProtobufFraming.frame(message.toByteArray()))
        current.flush()
    }

    private fun readMessage(): PoloProto.OuterMessage {
        val current = input ?: throw PairingProtocolException(PairingProtocolException.Kind.CLOSED)
        val payload = ProtobufFraming.readPayload(current)
        return PoloProto.OuterMessage.parseFrom(payload)
    }

    private fun ensureOk(message: PoloProto.OuterMessage) {
        if (message.status == PoloProto.OuterMessage.Status.STATUS_OK) return
        val kind = if (message.status == PoloProto.OuterMessage.Status.STATUS_BAD_SECRET) {
            PairingProtocolException.Kind.BAD_SECRET
        } else {
            PairingProtocolException.Kind.UNEXPECTED
        }
        throw PairingProtocolException(kind)
    }

    private fun pairingRequest(): PoloProto.OuterMessage {
        return base()
            .setPairingRequest(
                PoloProto.PairingRequest.newBuilder()
                    .setServiceName(SERVICE_NAME)
                    .setClientName(CLIENT_NAME)
                    .build(),
            )
            .build()
    }

    private fun options(): PoloProto.OuterMessage {
        return base()
            .setOptions(
                PoloProto.Options.newBuilder()
                    .setPreferredRole(PoloProto.Options.RoleType.ROLE_TYPE_INPUT)
                    .addInputEncodings(hexEncoding())
                    .build(),
            )
            .build()
    }

    private fun configuration(): PoloProto.OuterMessage {
        return base()
            .setConfiguration(
                PoloProto.Configuration.newBuilder()
                    .setClientRole(PoloProto.Options.RoleType.ROLE_TYPE_INPUT)
                    .setEncoding(hexEncoding())
                    .build(),
            )
            .build()
    }

    private fun secretMessage(secret: ByteArray): PoloProto.OuterMessage {
        return base()
            .setSecret(
                PoloProto.Secret.newBuilder()
                    .setSecret(ByteString.copyFrom(secret))
                    .build(),
            )
            .build()
    }

    private fun base(): PoloProto.OuterMessage.Builder {
        return PoloProto.OuterMessage.newBuilder()
            .setProtocolVersion(2)
            .setStatus(PoloProto.OuterMessage.Status.STATUS_OK)
    }

    private fun hexEncoding(): PoloProto.Options.Encoding {
        return PoloProto.Options.Encoding.newBuilder()
            .setType(PoloProto.Options.Encoding.EncodingType.ENCODING_TYPE_HEXADECIMAL)
            .setSymbolLength(6)
            .build()
    }

    private companion object {
        const val TAG = "TvRemotePairing"
        const val CONNECT_TIMEOUT_MS = 5_000
        const val READ_TIMEOUT_MS = 10_000
    }
}
