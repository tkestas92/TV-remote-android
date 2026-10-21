package lt.kantrybes.tvremote.data.launcher

import org.junit.Assert.assertEquals
import org.junit.Test

class LauncherStatusTest {
    @Test
    fun pairing429IsLockedAndReadsRetryAfterSeconds() {
        val error = mapLauncherHttpStatus(
            code = 429,
            pairing = true,
            wallpaper = false,
            bodyBlank = true,
            retryAfterHeader = "45",
        )
        assertEquals(LauncherCallException.Kind.LOCKED, error.kind)
        assertEquals(45, error.retryAfterSeconds)
    }

    @Test
    fun pairing429WithoutANumericRetryAfterIsZero() {
        listOf(null, "", "  ", "soon", "-3").forEach { header ->
            val error = mapLauncherHttpStatus(
                code = 429,
                pairing = true,
                wallpaper = false,
                bodyBlank = true,
                retryAfterHeader = header,
            )
            assertEquals(header, LauncherCallException.Kind.LOCKED, error.kind)
            assertEquals(header, 0, error.retryAfterSeconds)
        }
        val padded = mapLauncherHttpStatus(429, pairing = true, wallpaper = false, bodyBlank = true, retryAfterHeader = " 12 ")
        assertEquals(12, padded.retryAfterSeconds)
    }

    @Test
    fun pairing403IsClosedAnd401And400StayWrongPin() {
        assertEquals(
            LauncherCallException.Kind.PAIRING_CLOSED,
            status(403, pairing = true).kind,
        )
        assertEquals(
            LauncherCallException.Kind.WRONG_PIN,
            status(401, pairing = true).kind,
        )
        assertEquals(
            LauncherCallException.Kind.WRONG_PIN,
            status(400, pairing = true).kind,
        )
    }

    @Test
    fun nonPairingStatusesStayUnchanged() {
        assertEquals(LauncherCallException.Kind.UNAUTHORIZED, status(401, pairing = false).kind)
        assertEquals(LauncherCallException.Kind.NOT_HOME, status(403, pairing = false).kind)
        assertEquals(LauncherCallException.Kind.BAD_WALLPAPER, status(400, pairing = false, wallpaper = true).kind)
        assertEquals(LauncherCallException.Kind.FAILED, status(400, pairing = false).kind)
        assertEquals(LauncherCallException.Kind.FAILED, status(429, pairing = false, bodyBlank = true).kind)
        assertEquals(LauncherCallException.Kind.NOT_HOME, status(429, pairing = false, bodyBlank = false).kind)
    }

    private fun status(
        code: Int,
        pairing: Boolean,
        wallpaper: Boolean = false,
        bodyBlank: Boolean = true,
    ): LauncherCallException {
        return mapLauncherHttpStatus(
            code = code,
            pairing = pairing,
            wallpaper = wallpaper,
            bodyBlank = bodyBlank,
            retryAfterHeader = null,
        )
    }
}
