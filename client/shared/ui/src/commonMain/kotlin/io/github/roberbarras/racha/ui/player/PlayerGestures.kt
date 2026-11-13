package io.github.roberbarras.racha.ui.player

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs

/** What a gesture asks the player to do; the screen maps it to controller/platform calls and feedback. */
sealed interface GestureAction {
    data object ToggleControls : GestureAction
    data class SeekBy(val seconds: Int) : GestureAction

    /** Horizontal scrub in progress ([seconds] relative to where it started) or finished. */
    data class Scrub(val seconds: Int, val finished: Boolean) : GestureAction
    data class Brightness(val delta: Float) : GestureAction
    data class Volume(val delta: Float) : GestureAction
    data class HoldSpeed(val held: Boolean) : GestureAction
    data class Pinch(val zoomIn: Boolean) : GestureAction

    /** The mouse moved over the player (desktop): show the controls. */
    data object PointerMoved : GestureAction
}

private const val EDGE_FRACTION = 0.25f

/** Volume change per wheel notch. */
private const val WHEEL_VOLUME_STEP = 0.05f

/** Hover reports are throttled: the controls only need to know the pointer is alive. */
private const val HOVER_INTERVAL_MS = 250L

/** Seconds per full-width horizontal swipe. */
private const val SCRUB_SECONDS_PER_WIDTH = 90f

/**
 * Player gestures (spec §4.2), each gated by its setting: edge taps and double taps seek ±N s, a centre tap toggles
 * the controls, long press holds the fast speed, horizontal swipe scrubs, vertical swipes adjust brightness (left)
 * and volume (right), pinch switches fit/fill. One pointer handler tells drags from pinches by pointer count, so a
 * two-finger pinch never seeks. With a mouse, the wheel changes the volume and moving the pointer shows the controls.
 */
fun Modifier.playerGestures(prefs: PlayerPreferences, scope: CoroutineScope, onAction: (GestureAction) -> Unit): Modifier = this
    .mouseGestures(prefs, onAction)
    .pointerInput(prefs) {
        detectTapGestures(
            onTap = { offset ->
                val side = side(offset, size)
                if (prefs.tapEdges && side != 0) onAction(GestureAction.SeekBy(side * prefs.seekStep)) else onAction(GestureAction.ToggleControls)
            },
            onDoubleTap = { offset ->
                if (prefs.doubleTap) onAction(GestureAction.SeekBy((if (offset.x < size.width / 2) -1 else 1) * prefs.seekStep))
            },
            onPress = {
                if (!prefs.longPressSpeed) return@detectTapGestures
                var held = false
                val job = scope.launch {
                    delay(viewConfiguration.longPressTimeoutMillis)
                    held = true
                    onAction(GestureAction.HoldSpeed(true))
                }
                tryAwaitRelease()
                job.cancel()
                if (held) onAction(GestureAction.HoldSpeed(false))
            },
        )
    }
    .pointerInput(prefs) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            val slop = viewConfiguration.touchSlop
            var axis = 0 // 0 undecided, 1 horizontal, 2 vertical, 3 pinch
            var total = Offset.Zero
            var zoom = 1f
            var scrub = 0
            val leftHalf = down.position.x < size.width / 2
            while (true) {
                val event = awaitPointerEvent()
                val pressed = event.changes.filter { it.pressed }
                if (pressed.isEmpty()) break
                if (pressed.size >= 2 && prefs.pinchZoom) {
                    axis = 3
                    zoom *= event.calculateZoom()
                    event.changes.forEach { it.consume() }
                    continue
                }
                if (axis == 3) continue
                val change = event.changes.firstOrNull { it.id == down.id } ?: continue
                val delta = change.positionChange()
                total += delta
                if (axis == 0 && total.getDistance() > slop) axis = if (abs(total.x) > abs(total.y)) 1 else 2
                when (axis) {
                    1 -> if (prefs.swipeSeek) {
                        scrub = (total.x / size.width * SCRUB_SECONDS_PER_WIDTH).toInt()
                        onAction(GestureAction.Scrub(scrub, finished = false))
                        change.consume()
                    }
                    2 -> {
                        val d = -delta.y / size.height
                        if (leftHalf && prefs.swipeBrightness) onAction(GestureAction.Brightness(d)).also { change.consume() }
                        if (!leftHalf && prefs.swipeVolume) onAction(GestureAction.Volume(d)).also { change.consume() }
                    }
                }
            }
            if (axis == 1 && prefs.swipeSeek) onAction(GestureAction.Scrub(scrub, finished = true))
            if (axis == 3 && abs(zoom - 1f) > 0.12f) onAction(GestureAction.Pinch(zoomIn = zoom > 1f))
        }
    }

private fun Modifier.mouseGestures(prefs: PlayerPreferences, onAction: (GestureAction) -> Unit): Modifier = pointerInput(prefs) {
    var lastHover = 0L
    awaitPointerEventScope {
        while (true) {
            val event = awaitPointerEvent()
            when (event.type) {
                PointerEventType.Scroll -> if (prefs.swipeVolume) {
                    val notches = event.changes.sumOf { it.scrollDelta.y.toDouble() }.toFloat()
                    if (notches != 0f) onAction(GestureAction.Volume(-notches * WHEEL_VOLUME_STEP))
                    event.changes.forEach { it.consume() }
                }
                PointerEventType.Move -> if (event.changes.none { it.pressed } && event.changes.first().uptimeMillis - lastHover > HOVER_INTERVAL_MS) {
                    lastHover = event.changes.first().uptimeMillis
                    onAction(GestureAction.PointerMoved)
                }
            }
        }
    }
}

private fun side(offset: Offset, size: IntSize): Int = when {
    offset.x < size.width * EDGE_FRACTION -> -1
    offset.x > size.width * (1 - EDGE_FRACTION) -> 1
    else -> 0
}
