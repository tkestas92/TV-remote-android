package lt.kantrybes.tvremote.data.adb

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ForegroundAppTest {
    @Test
    fun resumedActivityCountsAsForeground() {
        val dump = """
            mResumedActivity: ActivityRecord{abc u0 com.google.android.youtube.tv/.ShellActivity t12}
            * Hist #1: ActivityRecord{def u0 com.netflix.ninja/.MainActivity t3}
        """.trimIndent()

        assertTrue(ForegroundApp.isPackageResumed(dump, "com.google.android.youtube.tv"))
        assertFalse(ForegroundApp.isPackageResumed(dump, "com.netflix.ninja"))
    }

    @Test
    fun focusedAppLineCountsWhenThereIsNoResumedLine() {
        val dump = "mFocusedApp=ActivityRecord{abc u0 lt.go3.android.tv/.Main t4}"

        assertTrue(ForegroundApp.isPackageResumed(dump, "lt.go3.android.tv"))
    }
}
