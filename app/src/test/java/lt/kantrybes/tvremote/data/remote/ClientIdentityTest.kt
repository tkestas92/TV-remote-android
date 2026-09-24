package lt.kantrybes.tvremote.data.remote

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigInteger
import java.nio.file.Files
import java.security.KeyFactory
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.interfaces.RSAPrivateKey
import java.security.interfaces.RSAPublicKey
import java.security.spec.PKCS8EncodedKeySpec

class ClientIdentityTest {
    @Test
    fun generatedCertificateIsSelfSignedRsa2048() {
        val identity = generateClientIdentity("tvremote")
        val publicKey = identity.certificate.publicKey as RSAPublicKey

        assertTrue(publicKey.modulus.bitLength() >= 2040)
        assertEquals(BigInteger.valueOf(65537), publicKey.publicExponent)
        identity.certificate.checkValidity()
        identity.certificate.verify(identity.certificate.publicKey)
    }

    @Test
    fun storeReloadsTheSameCertificate() {
        val directory = Files.createTempDirectory("remote-identity").toFile()
        val store = ClientCertificateStore(directory)
        val created = store.getOrCreate()
        val loaded = store.getOrCreate()

        assertArrayEquals(created.certificate.encoded, loaded.certificate.encoded)
        assertEquals(
            (created.privateKey as RSAPrivateKey).modulus,
            (loaded.privateKey as RSAPrivateKey).modulus,
        )

        val certificate = directory.resolve("client.crt").inputStream().use { input ->
            CertificateFactory.getInstance("X.509").generateCertificate(input) as X509Certificate
        }
        val privateKey = KeyFactory.getInstance("RSA").generatePrivate(
            PKCS8EncodedKeySpec(directory.resolve("client.key").readBytes()),
        )
        assertArrayEquals(created.certificate.encoded, certificate.encoded)
        assertEquals((created.privateKey as RSAPrivateKey).modulus, (privateKey as RSAPrivateKey).modulus)
    }
}
