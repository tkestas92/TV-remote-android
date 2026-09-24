package lt.kantrybes.tvremote.data.remote

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.math.BigInteger

class PairingSecretTest {
    @Test
    fun secretMatchesAndroidTvRemote2ForEvenLengthModuli() {
        val secret = PairingSecret.compute(
            clientModulus = BigInteger("AABBCCDD", 16),
            clientExponent = BigInteger.valueOf(65537),
            serverModulus = BigInteger("112233445566", 16),
            serverExponent = BigInteger.valueOf(65537),
            pairingCode = "20BEEF",
        )

        assertArrayEquals(hex("207a615fcf639658e6b09047b00adbf3ce88c46756d4f78a14954453ed866313"), secret)
    }

    @Test
    fun secretStripsTheSignByteAndPadsAnOddModulus() {
        val secret = PairingSecret.compute(
            clientModulus = BigInteger("8000000000000001", 16),
            clientExponent = BigInteger.valueOf(65537),
            serverModulus = BigInteger("ABC", 16),
            serverExponent = BigInteger.valueOf(65537),
            pairingCode = "0C00FF",
        )

        assertArrayEquals(hex("0cfdb0d0d96555a1a21195a4aa31aff265f527ee8b7b6c68cfe6b2a5a6201384"), secret)
    }

    @Test
    fun lowercaseCodeIsAccepted() {
        val secret = PairingSecret.compute(
            clientModulus = BigInteger("AABBCCDD", 16),
            clientExponent = BigInteger.valueOf(65537),
            serverModulus = BigInteger("112233445566", 16),
            serverExponent = BigInteger.valueOf(65537),
            pairingCode = "20beef",
        )

        assertEquals(0x20, secret[0].toInt() and 0xFF)
    }

    @Test
    fun firstCodeByteMustEqualHashPrefix() {
        val error = expectCodeFailure("00BEEF")
        assertEquals(InvalidPairingCodeException.Reason.MISMATCH, error.reason)
    }

    @Test
    fun codeMustBeSixHexCharacters() {
        assertEquals(InvalidPairingCodeException.Reason.FORMAT, expectCodeFailure("20BEE").reason)
        assertEquals(InvalidPairingCodeException.Reason.FORMAT, expectCodeFailure("20BEEG").reason)
        assertEquals(InvalidPairingCodeException.Reason.FORMAT, expectCodeFailure("").reason)
    }

    private fun expectCodeFailure(code: String): InvalidPairingCodeException {
        try {
            PairingSecret.compute(
                clientModulus = BigInteger("AABBCCDD", 16),
                clientExponent = BigInteger.valueOf(65537),
                serverModulus = BigInteger("112233445566", 16),
                serverExponent = BigInteger.valueOf(65537),
                pairingCode = code,
            )
        } catch (error: InvalidPairingCodeException) {
            return error
        }
        throw AssertionError("Expected an invalid pairing code")
    }

    private fun hex(value: String): ByteArray {
        return ByteArray(value.length / 2) { index ->
            value.substring(index * 2, index * 2 + 2).toInt(16).toByte()
        }
    }
}
