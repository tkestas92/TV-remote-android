package lt.kantrybes.tvremote.data.adb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TextSyncTest {
    @Test
    fun spacesBecomePercentSInsideQuotes() {
        assertEquals("input text 'hello%sworld'", TextSync.inputCommand("hello world"))
    }

    @Test
    fun nonAsciiIsNotAnAdbCommand() {
        assertFalse(TextSync.isAscii("é"))
        assertNull(TextSync.inputCommand("café"))
    }

    @Test
    fun appendsOnlyTheNewTail() {
        val plan = TextSync.plan("hel", "hello")

        assertEquals(0, plan.deletes)
        assertEquals("lo", plan.append)
        assertFalse(plan.needsIme)
    }

    @Test
    fun backspacePlansDeleteKeys() {
        val plan = TextSync.plan("hello", "hell")

        assertEquals(1, plan.deletes)
        assertNull(plan.append)
    }

    @Test
    fun nonAsciiTailAsksForIme() {
        assertTrue(TextSync.plan("cafe", "café").needsIme)
    }
}
