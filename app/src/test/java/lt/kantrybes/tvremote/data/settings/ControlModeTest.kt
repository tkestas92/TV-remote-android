package lt.kantrybes.tvremote.data.settings

import org.junit.Assert.assertEquals
import org.junit.Test

class ControlModeTest {
    @Test
    fun swipeMigratesToDpadAndButtonsStayButtons() {
        assertEquals(ControlMode.DPAD, ControlMode.fromStored("Swipe"))
        assertEquals(ControlMode.DPAD, ControlMode.fromStored("swipe"))
        assertEquals(ControlMode.DPAD, ControlMode.fromStored(null))
        assertEquals(ControlMode.DPAD, ControlMode.fromStored("dpad"))
        assertEquals(ControlMode.BUTTONS, ControlMode.fromStored("buttons"))
    }
}
