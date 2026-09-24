package lt.kantrybes.tvremote.data.adb

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PictureMenuTest {
    @Test
    fun startingIntentCountsAsOpened() {
        val output = "Starting: Intent { cmp=com.tcl.settings/.MainActivity }"

        assertTrue(PictureMenu.started(output))
    }

    @Test
    fun missingActivityDoesNotCountAsOpened() {
        val output = "Error: Activity class {com.tcl.settings/com.tcl.settings.MainActivity} does not exist."

        assertFalse(PictureMenu.started(output))
    }
}
