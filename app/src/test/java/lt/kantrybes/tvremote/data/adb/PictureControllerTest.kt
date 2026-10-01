package lt.kantrybes.tvremote.data.adb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PictureControllerTest {
    @Test
    fun commandStopsSettingsThenSetsTheValueInOneShellCall() {
        assertEquals(
            "am force-stop com.tcl.settings; " +
                "am startservice -n com.tcl.settings/.ShowWindowService " +
                "-a com.tcl.settings.SHOW_WINDOW " +
                "--es Type Settings --es ITEM contrast --es MOTION SET --es MOTION_VALUE 70",
            PictureController.command("contrast", 70),
        )
    }

    @Test
    fun clampsTheValueAndRejectsUnknownItems() {
        assertEquals(100, PictureController.command("brightness", 140)?.substringAfterLast(" ")?.toInt())
        assertNull(PictureController.command("backlight", 10))
    }
}
