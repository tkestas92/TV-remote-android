package lt.kantrybes.tvremote.data.adb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NowPlayingTest {
    @Test
    fun takesTheFirstPlayingPausedOrBufferingSession() {
        val dump = """
            package=com.android.systemui
            state=PlaybackState {state=0, position=0, buffered position=0, speed=0.0, updated=1000, actions=0, error=null}
            metadata: size=0, description=null, null, null
            package=com.google.android.youtube.tv
            state=PlaybackState {state=3, position=5000, buffered position=8000, speed=1.0, updated=10000, actions=559, error=null}
            metadata: size=3, description=Boiler Room, Boiler Room, null
            package=com.netflix.ninja
            state=PlaybackState {state=2, position=9, buffered position=9, speed=1.0, updated=10000, actions=1, error=null}
            metadata: size=1, description=Other, Someone, null
        """.trimIndent()

        val playing = NowPlayingDump.parse(dump, uptimeMs = 15_500)

        assertEquals("com.google.android.youtube.tv", playing?.packageName)
        assertEquals("YouTube", playing?.appName)
        assertEquals(NowPlayingDump.STATE_PLAYING, playing?.state)
        assertEquals(10_500L, playing?.positionMs)
        assertEquals(1f, playing?.speed)
        assertEquals("Boiler Room", playing?.title)
        assertEquals("Boiler Room", playing?.artist)
    }

    @Test
    fun pausedAndBufferingDoNotExtrapolate() {
        val paused = """
            package=lt.go3.android.tv
            state=PlaybackState {state=2, position=4000, buffered position=9000, speed=1.0, updated=1000, actions=1, error=null}
            description=News, Channel, null
        """.trimIndent()
        val buffering = """
            package=com.stremio.one
            state=PlaybackState {state=6, position=2500, buffered position=3000, speed=1.0, updated=0, actions=1, error=null}
            description=Movie, null, null
        """.trimIndent()

        val pausedItem = NowPlayingDump.parse(paused, uptimeMs = 20_000)
        val bufferingItem = NowPlayingDump.parse(buffering, uptimeMs = 20_000)

        assertEquals("Go3", pausedItem?.appName)
        assertEquals(4_000L, pausedItem?.positionMs)
        assertEquals(0f, pausedItem?.speed)
        assertEquals("Stremio", bufferingItem?.appName)
        assertEquals(2_500L, bufferingItem?.positionMs)
        assertEquals(0f, bufferingItem?.speed)
        assertNull(bufferingItem?.artist)
    }

    @Test
    fun titleMayContainCommasAndSpeedScalesTheAdvance() {
        val dump = """
            package=com.netflix.ninja
            state=PlaybackState {state=3, position=1000, buffered position=0, speed=2.0, updated=0, actions=1, error=null}
            description=Boiler Room, London, set, Artist, null
        """.trimIndent()

        val playing = NowPlayingDump.parse(dump, uptimeMs = 5_000)

        assertEquals("Netflix", playing?.appName)
        assertEquals("Boiler Room, London, set", playing?.title)
        assertEquals("Artist", playing?.artist)
        assertEquals(11_000L, playing?.positionMs)
    }

    @Test
    fun nothingActiveBecomesNullAndPositionDoesNotGoNegative() {
        val idle = """
            package=com.example.other
            state=PlaybackState {state=1, position=10, buffered position=10, speed=1.0, updated=10, actions=0, error=null}
            description=Idle, null, null
        """.trimIndent()
        assertNull(NowPlayingDump.parse(idle, uptimeMs = 10_000))
        assertEquals("com.example.other", NowPlayingDump.appName("com.example.other"))
        assertEquals(
            0L,
            NowPlayingDump.extrapolatedPosition(
                state = NowPlayingDump.STATE_PLAYING,
                positionMs = 100,
                updatedMs = 5_000,
                speed = 1f,
                uptimeMs = 1_000,
            ),
        )
    }

    @Test
    fun uptimeIsTheLastProcLine() {
        val output = """
            package=com.google.android.youtube.tv
            12.00 34.00
            state=PlaybackState {state=3, position=0, buffered position=0, speed=1.0, updated=0, actions=1, error=null}
            description=Clip, Channel, null
            15.50 40.25
        """.trimIndent()

        assertEquals(15_500L, NowPlayingDump.uptimeMs(output))
        assertEquals(15_500L, NowPlayingDump.parse(output, NowPlayingDump.uptimeMs(output))?.positionMs)
    }
}
