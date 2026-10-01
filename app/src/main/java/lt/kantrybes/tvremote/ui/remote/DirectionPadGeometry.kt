package lt.kantrybes.tvremote.ui.remote

import lt.kantrybes.tvremote.data.remote.TvKey
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.hypot

/** Touch target for the circular pad. The center disk is OK; the ring is four quadrants. */
internal object DirectionPadGeometry {
    const val CENTER_FRACTION = 0.35f

    fun keyAt(dx: Float, dy: Float, radius: Float): TvKey? {
        if (radius <= 0f) return null
        val distance = hypot(dx, dy)
        if (distance > radius) return null
        if (distance <= radius * CENTER_FRACTION) return TvKey.OK
        val angle = atan2(dy.toDouble(), dx.toDouble())
        val quarter = PI / 4
        return when {
            angle >= -quarter && angle < quarter -> TvKey.RIGHT
            angle >= quarter && angle < 3 * quarter -> TvKey.DOWN
            angle >= 3 * quarter || angle < -3 * quarter -> TvKey.LEFT
            else -> TvKey.UP
        }
    }
}
