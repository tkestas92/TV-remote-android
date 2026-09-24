package lt.kantrybes.tvremote.data.remote

import java.math.BigInteger
import java.security.MessageDigest
import java.security.cert.X509Certificate
import java.security.interfaces.RSAPublicKey

/**
 * Pairing secret from the Android TV Remote protocol v2.
 *
 * The digest is SHA-256(client modulus, client exponent, server modulus, server exponent,
 * bytes of code[2:]). Each integer is unsigned big-endian with the sign byte removed, which
 * matches androidtvremote2 for an RSA-2048 key and exponent 65537. The first code byte must
 * equal hash[0]; that check rejects a mistyped code before anything is sent.
 */
internal object PairingSecret {
    fun compute(
        clientCertificate: X509Certificate,
        serverCertificate: X509Certificate,
        pairingCode: String,
    ): ByteArray {
        val clientKey = clientCertificate.publicKey as? RSAPublicKey
            ?: throw IllegalArgumentException("Client certificate is not RSA")
        val serverKey = serverCertificate.publicKey as? RSAPublicKey
            ?: throw IllegalArgumentException("TV certificate is not RSA")
        return compute(
            clientModulus = clientKey.modulus,
            clientExponent = clientKey.publicExponent,
            serverModulus = serverKey.modulus,
            serverExponent = serverKey.publicExponent,
            pairingCode = pairingCode,
        )
    }

    fun compute(
        clientModulus: BigInteger,
        clientExponent: BigInteger,
        serverModulus: BigInteger,
        serverExponent: BigInteger,
        pairingCode: String,
    ): ByteArray {
        val code = normalize(pairingCode)
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(unsignedBigEndian(clientModulus))
        digest.update(unsignedBigEndian(clientExponent))
        digest.update(unsignedBigEndian(serverModulus))
        digest.update(unsignedBigEndian(serverExponent))
        digest.update(decodeHex(code.substring(2)))
        val hash = digest.digest()
        val prefix = code.substring(0, 2).toInt(16)
        if ((hash[0].toInt() and 0xFF) != prefix) {
            throw InvalidPairingCodeException(InvalidPairingCodeException.Reason.MISMATCH)
        }
        return hash
    }

    private fun normalize(pairingCode: String): String {
        val code = pairingCode.trim().uppercase()
        val hex = code.all { it in '0'..'9' || it in 'A'..'F' }
        if (code.length != 6 || !hex) {
            throw InvalidPairingCodeException(InvalidPairingCodeException.Reason.FORMAT)
        }
        return code
    }

    private fun unsignedBigEndian(value: BigInteger): ByteArray {
        require(value.signum() >= 0) { "Expected a non-negative integer" }
        val twosComplement = value.toByteArray()
        return if (twosComplement.size > 1 && twosComplement[0] == 0.toByte()) {
            twosComplement.copyOfRange(1, twosComplement.size)
        } else {
            twosComplement
        }
    }

    private fun decodeHex(hex: String): ByteArray {
        val out = ByteArray(hex.length / 2)
        for (index in out.indices) {
            val byte = hex.substring(index * 2, index * 2 + 2).toInt(16)
            out[index] = byte.toByte()
        }
        return out
    }
}
