package lt.kantrybes.tvremote.data.remote

import org.bouncycastle.asn1.x500.X500NameBuilder
import org.bouncycastle.asn1.x500.style.BCStyle
import org.bouncycastle.asn1.x509.BasicConstraints
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.asn1.x509.GeneralName
import org.bouncycastle.asn1.x509.GeneralNames
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.File
import java.math.BigInteger
import java.security.GeneralSecurityException
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Date

internal data class ClientIdentity(
    val certificate: X509Certificate,
    val privateKey: PrivateKey,
)

internal fun generateClientIdentity(commonName: String): ClientIdentity {
    val generator = KeyPairGenerator.getInstance("RSA")
    generator.initialize(2048, SecureRandom())
    val keyPair = generator.generateKeyPair()

    val subject = X500NameBuilder(BCStyle.INSTANCE)
        .addRDN(BCStyle.CN, commonName)
        .build()
    val notBefore = Date()
    val notAfter = Date(notBefore.time + TEN_YEARS_MS)
    val builder = JcaX509v3CertificateBuilder(
        subject,
        BigInteger.valueOf(1000),
        notBefore,
        notAfter,
        subject,
        keyPair.public,
    )
    builder.addExtension(Extension.basicConstraints, false, BasicConstraints(0))
    builder.addExtension(
        Extension.subjectAlternativeName,
        false,
        GeneralNames(GeneralName(GeneralName.dNSName, commonName)),
    )
    val signer = JcaContentSignerBuilder("SHA256withRSA").build(keyPair.private)
    val holder = builder.build(signer)
    val certificate = JcaX509CertificateConverter().getCertificate(holder)
    return ClientIdentity(certificate, keyPair.private)
}

internal class ClientCertificateStore(private val directory: File) {
    fun exists(): Boolean {
        return certificateFile().isFile && keyFile().isFile
    }

    fun loadExisting(): ClientIdentity? {
        if (!exists()) return null
        return try {
            load()
        } catch (_: GeneralSecurityException) {
            null
        } catch (_: java.io.IOException) {
            null
        }
    }

    fun getOrCreate(): ClientIdentity {
        directory.mkdirs()
        if (exists()) {
            return load()
        }
        val identity = generateClientIdentity(CERT_COMMON_NAME)
        val keyBytes = identity.privateKey.encoded
            ?: error("Generated private key is not exportable")
        writeAtomic(certificateFile(), identity.certificate.encoded)
        writeAtomic(keyFile(), keyBytes)
        return identity
    }

    private fun load(): ClientIdentity {
        val certificate = certificateFile().inputStream().use { input ->
            CertificateFactory.getInstance("X.509").generateCertificate(input) as X509Certificate
        }
        val privateKey = KeyFactory.getInstance("RSA").generatePrivate(
            PKCS8EncodedKeySpec(keyFile().readBytes()),
        )
        return ClientIdentity(certificate, privateKey)
    }

    private fun certificateFile(): File = File(directory, "client.crt")

    private fun keyFile(): File = File(directory, "client.key")

    private fun writeAtomic(destination: File, bytes: ByteArray) {
        val temporary = File(directory, "${destination.name}.tmp")
        temporary.writeBytes(bytes)
        if (!temporary.renameTo(destination)) {
            destination.writeBytes(bytes)
            temporary.delete()
        }
    }
}

private const val TEN_YEARS_MS = 10L * 365 * 24 * 60 * 60 * 1000
