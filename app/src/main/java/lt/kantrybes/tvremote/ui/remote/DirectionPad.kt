package lt.kantrybes.tvremote.ui.remote

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlin.math.cos
import kotlin.math.sin
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import lt.kantrybes.tvremote.R
import lt.kantrybes.tvremote.data.remote.KeyPress
import lt.kantrybes.tvremote.data.remote.TvKey

@Composable
internal fun DirectionPad(
    enabled: Boolean,
    onKey: (TvKey, KeyPress) -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val longPressMs = LocalViewConfiguration.current.longPressTimeoutMillis
    val description = stringResource(R.string.control_mode_dpad)
    val up = stringResource(R.string.key_up)
    val down = stringResource(R.string.key_down)
    val left = stringResource(R.string.key_left)
    val right = stringResource(R.string.key_right)
    val ok = stringResource(R.string.key_ok)
    var pressed by remember { mutableStateOf<TvKey?>(null) }
    val idle = MaterialTheme.colorScheme.secondaryContainer
    val active = MaterialTheme.colorScheme.primary
    val mark = MaterialTheme.colorScheme.onSecondaryContainer
    val markActive = MaterialTheme.colorScheme.onPrimary
    val padSize = LocalConfiguration.current.screenWidthDp.dp * 0.7f

    Box(
        modifier = Modifier
            .size(padSize)
            .alpha(if (enabled) 1f else 0.4f)
            .semantics {
                contentDescription = description
                customActions = listOf(up, down, left, right, ok).zip(
                    listOf(TvKey.UP, TvKey.DOWN, TvKey.LEFT, TvKey.RIGHT, TvKey.OK),
                ).map { (label, key) ->
                    CustomAccessibilityAction(label) {
                        if (!enabled) return@CustomAccessibilityAction false
                        onKey(key, KeyPress.SHORT)
                        true
                    }
                }
            }
            .pointerInput(enabled, longPressMs) {
                if (!enabled) return@pointerInput
                awaitEachGesture {
                    val downEvent = awaitFirstDown(requireUnconsumed = false)
                    val radius = minOf(size.width, size.height) / 2f
                    fun hit(position: Offset): TvKey? {
                        return DirectionPadGeometry.keyAt(
                            dx = position.x - radius,
                            dy = position.y - radius,
                            radius = radius,
                        )
                    }
                    var key = hit(downEvent.position) ?: return@awaitEachGesture
                    downEvent.consume()
                    pressed = key
                    haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                    var longSent = false
                    var finished = false
                    var hold: Job = scope.launch {
                        delay(longPressMs)
                        longSent = true
                        onKey(key, KeyPress.START_LONG)
                    }
                    try {
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == downEvent.id } ?: break
                            if (!change.pressed) {
                                hold.cancel()
                                finished = true
                                if (longSent) {
                                    onKey(key, KeyPress.END_LONG)
                                } else {
                                    onKey(key, KeyPress.SHORT)
                                }
                                break
                            }
                            change.consume()
                            val next = hit(change.position) ?: continue
                            if (next == key) continue
                            hold.cancel()
                            if (longSent) onKey(key, KeyPress.END_LONG)
                            key = next
                            pressed = key
                            longSent = false
                            haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                            hold = scope.launch {
                                delay(longPressMs)
                                longSent = true
                                onKey(key, KeyPress.START_LONG)
                            }
                        }
                    } finally {
                        hold.cancel()
                        pressed = null
                        if (longSent && !finished) onKey(key, KeyPress.END_LONG)
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.matchParentSize()) {
            val radius = size.minDimension / 2f
            val centerRadius = radius * DirectionPadGeometry.CENTER_FRACTION
            val ringInner = centerRadius + 3.dp.toPx()
            segments.forEach { segment ->
                val selected = pressed == segment.key
                drawAnnularSector(
                    center = this.center,
                    inner = ringInner,
                    outer = radius,
                    startDegrees = segment.centerDegrees - SEGMENT_SWEEP / 2f,
                    sweepDegrees = SEGMENT_SWEEP,
                    color = if (selected) active else idle,
                )
                drawChevron(
                    center = this.center,
                    angleDegrees = segment.centerDegrees,
                    distance = (ringInner + radius) / 2f,
                    color = if (selected) markActive else mark,
                )
            }
            drawCircle(
                color = if (pressed == TvKey.OK) active else idle,
                radius = centerRadius,
            )
        }
        Text(
            text = ok,
            style = MaterialTheme.typography.titleMedium,
            color = if (pressed == TvKey.OK) markActive else mark,
        )
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawAnnularSector(
    center: Offset,
    inner: Float,
    outer: Float,
    startDegrees: Float,
    sweepDegrees: Float,
    color: Color,
) {
    val path = Path().apply {
        arcTo(
            rect = Rect(center = center, radius = outer),
            startAngleDegrees = startDegrees,
            sweepAngleDegrees = sweepDegrees,
            forceMoveTo = true,
        )
        arcTo(
            rect = Rect(center = center, radius = inner),
            startAngleDegrees = startDegrees + sweepDegrees,
            sweepAngleDegrees = -sweepDegrees,
            forceMoveTo = false,
        )
        close()
    }
    drawPath(path, color)
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawChevron(
    center: Offset,
    angleDegrees: Float,
    distance: Float,
    color: Color,
) {
    val radians = Math.toRadians(angleDegrees.toDouble())
    val direction = Offset(cos(radians).toFloat(), sin(radians).toFloat())
    val tangent = Offset(-direction.y, direction.x)
    val tip = center + direction * (distance + 8.dp.toPx())
    val base = center + direction * (distance - 6.dp.toPx())
    val wing = 9.dp.toPx()
    val path = Path().apply {
        moveTo(tip.x, tip.y)
        lineTo(base.x + tangent.x * wing, base.y + tangent.y * wing)
        lineTo(base.x - tangent.x * wing, base.y - tangent.y * wing)
        close()
    }
    drawPath(path, color)
}

private data class PadSegment(val key: TvKey, val centerDegrees: Float)

private const val SEGMENT_SWEEP = 82f

private val segments = listOf(
    PadSegment(TvKey.UP, -90f),
    PadSegment(TvKey.RIGHT, 0f),
    PadSegment(TvKey.DOWN, 90f),
    PadSegment(TvKey.LEFT, 180f),
)
