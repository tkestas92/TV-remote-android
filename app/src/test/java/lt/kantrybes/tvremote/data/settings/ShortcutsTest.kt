package lt.kantrybes.tvremote.data.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShortcutsTest {
    @Test
    fun defaultsAreTheFourTvApps() {
        assertEquals(
            listOf(
                "com.google.android.youtube.tv",
                "lt.go3.android.tv",
                "com.stremio.one",
                "com.netflix.ninja",
            ),
            Shortcuts.defaults.map { it.packageName },
        )
    }

    @Test
    fun roundTripsEditedShortcuts() {
        val edited = listOf(
            AppShortcut("Films", "com.netflix.ninja"),
            Shortcuts.defaults[1],
            Shortcuts.defaults[2],
            Shortcuts.defaults[3],
        )

        assertEquals(edited, Shortcuts.decode(Shortcuts.encode(edited)))
    }

    @Test
    fun blankOrCorruptStorageUsesDefaults() {
        assertEquals(Shortcuts.defaults, Shortcuts.decode(null))
        assertEquals(Shortcuts.defaults, Shortcuts.decode("not a shortcut"))
    }

    @Test
    fun packageNamesRejectShellMetacharacters() {
        assertTrue(Shortcuts.isPackageName("lt.go3.android.tv"))
        assertFalse(Shortcuts.isPackageName("com.app;reboot"))
        assertFalse(Shortcuts.isPackageName("com.app name"))
        assertFalse(Shortcuts.isPackageName(""))
    }

    @Test
    fun monkeyCommandMatchesTheLeanbackLaunch() {
        assertEquals(
            "monkey -p lt.go3.android.tv -c android.intent.category.LEANBACK_LAUNCHER 1",
            Shortcuts.monkeyCommand("lt.go3.android.tv"),
        )
    }
}
