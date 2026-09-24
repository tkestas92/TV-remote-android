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

    private fun failure(host: String): InvalidTvAddressException.Kind {
        try {
            TvAddress.requireIpv4(host)
        } catch (error: InvalidTvAddressException) {
            return error.kind
        }
        throw AssertionError("Expected an invalid address")
    }
}
