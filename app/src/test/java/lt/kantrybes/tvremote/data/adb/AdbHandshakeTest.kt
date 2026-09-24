package lt.kantrybes.tvremote.data.adb

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException

class AdbHandshakeTest {
    @Test
    fun handshakeReadTimeoutIsRetried() {
        val error = IOException("Connection handshake failed", SocketTimeoutException("Read timed out"))

        assertTrue(isRetryableHandshake(error))
    }

    @Test
    fun refusedConnectionIsNotAHandshakeTimeout() {
        assertFalse(isRetryableHandshake(IOException("Failed to connect to tv:5555")))
    }
}
