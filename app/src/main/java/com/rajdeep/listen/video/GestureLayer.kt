package com.rajdeep.listen.video

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.hypot

data class GestureConfig(
    val brightness: Boolean,
    val volume: Boolean,
    val seek: Boolean,
    val doubleTap: Boolean,
    val pinch: Boolean,
    val locked: Boolean,
)

interface GestureCallbacks {
    fun onTap()
    /** side: -1 left (rewind), 0 centre (play/pause), 1 right (forward). */
    fun onDoubleTap(side: Int)
    fun onBrightnessDrag(delta: Float)
    fun onVolumeDrag(delta: Float)
    fun onAdjustEnd()
    /** fraction of screen width dragged since gesture start (signed). */
    fun onSeekDrag(fraction: Float)
    fun onSeekEnd(commit: Boolean)
    fun onZoomStart()
    fun onZoom(relativeScale: Float, pan: Offset)
    fun onZoomEnd()
}

private enum class G { NONE, IGNORE, SEEK, BRIGHT, VOL, PINCH }

private fun dist(a: PointerInputChange, b: PointerInputChange) =
    hypot(a.position.x - b.position.x, a.position.y - b.position.y)

/**
 * One gesture arbiter for the whole video surface. Axis is locked once touch slop is exceeded,
 * so brightness/volume/seek never fight. Top/bottom 6% are dead zones (system shade / nav gestures).
 */
@Composable
fun GestureLayer(config: GestureConfig, callbacks: GestureCallbacks, modifier: Modifier = Modifier) {
    val cb by rememberUpdatedState(callbacks)
    val scope = rememberCoroutineScope()
    Box(
        modifier.fillMaxSize().pointerInput(config) {
            var lastTapAt = 0L
            var lastTapX = 0f
            var tapJob: Job? = null
            var streakUntil = 0L
            var streakSide = 0
            val doubleWindow = 280L

            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                val w = size.width.toFloat()
                val h = size.height.toFloat()
                val sx = down.position.x
                val sy = down.position.y
                val dead = sy < h * 0.06f || sy > h * 0.94f
                val slop = viewConfiguration.touchSlop
                var mode = G.NONE
                var dx = 0f
                var dy = 0f
                var pinchStart = 0f

                while (true) {
                    val ev = awaitPointerEvent()
                    val pressed = ev.changes.filter { it.pressed }
                    if (!config.locked && config.pinch && pressed.size >= 2) {
                        if (mode != G.PINCH) {
                            if (mode == G.SEEK) cb.onSeekEnd(false)
                            if (mode == G.BRIGHT || mode == G.VOL) cb.onAdjustEnd()
                            mode = G.PINCH; pinchStart = dist(pressed[0], pressed[1]).coerceAtLeast(1f); cb.onZoomStart()
                        }
                        cb.onZoom(dist(pressed[0], pressed[1]) / pinchStart, ev.calculatePan())
                        ev.changes.forEach { it.consume() }
                        continue
                    }
                    if (mode == G.PINCH) { if (pressed.isEmpty()) break else continue }
                    val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                    if (!ch.pressed) break
                    val d = ch.positionChange()
                    if (mode == G.NONE) {
                        dx += d.x; dy += d.y
                        if (hypot(dx, dy) > slop) {
                            mode = when {
                                config.locked || dead -> G.IGNORE
                                abs(dx) > abs(dy) -> if (config.seek) G.SEEK else G.IGNORE
                                sx < w / 2 -> if (config.brightness) G.BRIGHT else G.IGNORE
                                else -> if (config.volume) G.VOL else G.IGNORE
                            }
                        }
                    } else {
                        when (mode) {
                            G.SEEK -> { dx += d.x; cb.onSeekDrag(dx / w) }
                            G.BRIGHT -> cb.onBrightnessDrag(-d.y / (h * 0.75f))
                            G.VOL -> cb.onVolumeDrag(-d.y / (h * 0.75f))
                            else -> {}
                        }
                        if (mode != G.IGNORE) ch.consume()
                    }
                }

                when (mode) {
                    G.SEEK -> cb.onSeekEnd(true)
                    G.BRIGHT, G.VOL -> cb.onAdjustEnd()
                    G.PINCH -> cb.onZoomEnd()
                    G.IGNORE -> {}
                    G.NONE -> {
                        val now = down.uptimeMillis
                        val side = when { sx < w * 0.35f -> -1; sx > w * 0.65f -> 1; else -> 0 }
                        if (config.locked || !config.doubleTap) {
                            cb.onTap()
                        } else if (now < streakUntil && side != 0 && side == streakSide) {
                            // Rapid repeated taps after a double-tap keep seeking (YouTube-style).
                            streakUntil = now + 650; cb.onDoubleTap(side)
                        } else if (now - lastTapAt < doubleWindow && abs(sx - lastTapX) < w * 0.25f) {
                            tapJob?.cancel(); lastTapAt = 0
                            cb.onDoubleTap(side)
                            if (side != 0) { streakUntil = now + 650; streakSide = side }
                        } else {
                            lastTapAt = now; lastTapX = sx
                            tapJob?.cancel()
                            tapJob = scope.launch { delay(doubleWindow); cb.onTap() }
                        }
                    }
                }
            }
        }
    )
}
