package lt.kantrybes.tvremote.ui.remote

import lt.kantrybes.tvremote.data.remote.TvKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DirectionPadGeometryTest {
    private val radius = 100f

    @Test
    fun centerDiskIsOk() {
        assertEquals(TvKey.OK, DirectionPadGeometry.keyAt(0f, 0f, radius))
        assertEquals(TvKey.OK, DirectionPadGeometry.keyAt(0f, -34f, radius))
    }

    @Test
    fun ringQuadrantsFollowTheTouchAngle() {
        assertEquals(TvKey.UP, DirectionPadGeometry.keyAt(0f, -70f, radius))
        assertEquals(TvKey.RIGHT, DirectionPadGeometry.keyAt(70f, 0f, radius))
        assertEquals(TvKey.DOWN, DirectionPadGeometry.keyAt(0f, 70f, radius))
        assertEquals(TvKey.LEFT, DirectionPadGeometry.keyAt(-70f, 0f, radius))
    }

    @Test
    fun outsideThePadIsIgnored() {
        assertNull(DirectionPadGeometry.keyAt(120f, 0f, radius))
    }
}
