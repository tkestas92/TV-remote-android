package lt.kantrybes.tvremote.data.remote

import org.junit.Assert.assertEquals
import org.junit.Test

class TvAddressTest {
    @Test
    fun acceptsADottedIpv4Address() {
        assertEquals("10.0.0.1", TvAddress.requireIpv4(" 10.0.0.1 "))
    }

    @Test
    fun rejectsEmptyAndOutOfRangeAddresses() {
        assertEquals(InvalidTvAddressException.Kind.EMPTY, failure(""))
        assertEquals(InvalidTvAddressException.Kind.INVALID, failure("256.0.0.1"))
        assertEquals(InvalidTvAddressException.Kind.INVALID, failure("not-an-ip"))
    }

    @Test
    fun acceptsPrivateLanRanges() {
        listOf(
            "10.0.0.0",
            "10.0.0.1",
            "10.255.255.255",
            "172.16.0.0",
            "172.16.5.1",
            "172.31.255.255",
            "192.168.0.0",
            "192.168.0.61",
            "192.168.255.255",
            "169.254.0.0",
            "169.254.1.1",
            "169.254.255.255",
            " 192.168.0.61 ",
        ).forEach { host ->
            assertEquals(host.trim(), TvAddress.requireIpv4(host))
        }
    }

    @Test
    fun rejectsPublicAndEdgeAddresses() {
        listOf(
            "8.8.8.8",
            "1.1.1.1",
            "9.255.255.255",
            "11.0.0.0",
            "172.15.255.255",
            "172.32.0.0",
            "192.167.255.255",
            "192.169.0.1",
            "169.253.255.255",
            "169.255.0.1",
            "127.0.0.1",
            "0.0.0.0",
            "100.64.0.1",
            "255.255.255.255",
        ).forEach { host ->
            assertEquals(host, InvalidTvAddressException.Kind.NOT_PRIVATE, failure(host))
        }
    }

    private fun failure(host: String): InvalidTvAddressException.Kind {
        try {
            TvAddress.requireIpv4(host)
        } catch (error: InvalidTvAddressException) {
            return error.kind
        }
        throw AssertionError("Expected an invalid address")
    }
}
