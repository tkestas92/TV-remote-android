package lt.kantrybes.tvremote.data.adb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class YouTubeShareTest {
    @Test
    fun findsWatchShortsAndShortLink() {
        assertEquals(
            "https://www.youtube.com/watch?v=abc",
            YouTubeShare.find("Look https://www.youtube.com/watch?v=abc"),
        )
        assertEquals(
            "https://youtu.be/abc",
            YouTubeShare.find("https://youtu.be/abc"),
        )
        assertEquals(
            "https://www.youtube.com/shorts/abc",
            YouTubeShare.find("https://www.youtube.com/shorts/abc"),
        )
    }

    @Test
    fun keepsQueryAmpersandsAndDropsATrailingPeriod() {
        assertEquals(
            "https://m.youtube.com/watch?v=abc&t=12",
            YouTubeShare.find("https://m.youtube.com/watch?v=abc&t=12."),
        )
    }

    @Test
    fun ignoresOtherLinks() {
        assertNull(YouTubeShare.find("https://example.com/watch?v=abc"))
    }

    @Test
    fun commandQuotesTheUrlForTheShell() {
        val command = YouTubeShare.command("https://www.youtube.com/watch?v=abc&t=1")

        assertTrue(command.contains("-d 'https://www.youtube.com/watch?v=abc&t=1'"))
        assertTrue(command.endsWith("com.google.android.youtube.tv"))
    }
}
