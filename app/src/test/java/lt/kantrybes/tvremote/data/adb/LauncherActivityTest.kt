package lt.kantrybes.tvremote.data.adb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LauncherActivityTest {
    @Test
    fun takesTheLastResolveLine() {
        val output = """
            priority=0 preferredOrder=0 match=0x108000 specificIndex=-1 isDefault=true
            com.google.android.youtube.tv/com.google.android.youtube.tv.MainActivity
        """.trimIndent()
        assertEquals(
            "com.google.android.youtube.tv/com.google.android.youtube.tv.MainActivity",
            LauncherActivity.componentFromResolve(output, "com.google.android.youtube.tv"),
        )
    }

    @Test
    fun emptyResolveIsNotAComponent() {
        assertNull(LauncherActivity.componentFromResolve("", "lt.go3.android.tv"))
        assertNull(LauncherActivity.componentFromResolve("No activity found\n", "lt.go3.android.tv"))
    }

    @Test
    fun startCommandDoesNotWait() {
        val command = LauncherActivity.startCommand("lt.go3.android.tv/.MainActivity")
        assertEquals("am start -n lt.go3.android.tv/.MainActivity", command)
        assertFalse(command.contains("-W"))
    }

    @Test
    fun warningsAreNotFailures() {
        assertFalse(
            LauncherActivity.startFailed(
                "Starting: Intent { cmp=lt.go3.android.tv/.MainActivity }\n" +
                    "Warning: Activity not started, its current task has been brought to the front",
            ),
        )
        assertTrue(LauncherActivity.startFailed("Error: Activity class {lt.go3.android.tv/.Gone} does not exist."))
    }

    @Test
    fun rejectsShellMetacharactersInTheComponent() {
        assertFalse(LauncherActivity.isComponent("lt.go3.android.tv/.Main;reboot"))
        assertFalse(LauncherActivity.isComponent("lt.go3.android.tv/Main Activity"))
    }
}
