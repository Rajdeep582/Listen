package com.rajdeep.listen.ui.player

import androidx.lifecycle.compose.collectAsStateWithLifecycle

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.rajdeep.listen.playback.PlayerConnection
import com.rajdeep.listen.playback.PlayerUiState
import com.rajdeep.listen.ui.components.Artwork
import com.rajdeep.listen.ui.components.artLookup
import com.rajdeep.listen.ui.components.Badge
import com.rajdeep.listen.ui.theme.LocalAccent
import com.rajdeep.listen.ui.theme.Palette
import kotlinx.coroutines.launch
import kotlin.math.abs

@Composable
fun MiniPlayer(player: PlayerConnection, state: PlayerUiState, onExpand: () -> Unit, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    val np = state.current ?: return
    val accent = LocalAccent.current
    val colors = rememberArtColors(com.rajdeep.listen.ui.components.rememberArtModel(np.artwork, np.artLookup()))
    val scope = rememberCoroutineScope()
    var dragX by remember { mutableFloatStateOf(0f) }
    var dragY by remember { mutableFloatStateOf(0f) }
    val haptic = LocalHapticFeedback.current
    val dismissThreshold = with(LocalDensity.current) { 72.dp.toPx() }
    val swipeThreshold = with(LocalDensity.current) { 64.dp.toPx() }
    val latestOnDismiss by androidx.compose.runtime.rememberUpdatedState(onDismiss)

    Box(
        modifier = modifier.fillMaxWidth().height(64.dp).clip(RoundedCornerShape(18.dp))
            .background(Brush.horizontalGradient(listOf(colors.dark, androidx.compose.ui.graphics.lerp(Palette.SurfaceHigh, accent.primary, 0.14f))))
            .border(1.dp, Brush.horizontalGradient(listOf(accent.primary.copy(alpha = 0.45f), accent.secondary.copy(alpha = 0.15f))), RoundedCornerShape(18.dp))
            .clickable(onClick = onExpand)
            .graphicsLayer { translationY = dragY }
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    var axis = 0 // 1 = horizontal track control, 2 = downward dismissal
                    var totalX = 0f
                    var totalY = 0f
                    dragX = 0f
                    dragY = 0f
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) break
                        val delta = change.positionChange()
                        totalX += delta.x
                        totalY += delta.y
                        if (axis == 0 && (abs(totalX) > viewConfiguration.touchSlop || abs(totalY) > viewConfiguration.touchSlop)) {
                            axis = if (abs(totalX) > abs(totalY)) 1 else 2
                        }
                        if (axis != 0) {
                            change.consume()
                            if (axis == 1) dragX = totalX.coerceIn(-260f, 260f)
                            else dragY = totalY.coerceIn(-40f, 360f)
                        }
                    }

                    when {
                        axis == 2 && dragY > dismissThreshold -> {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            latestOnDismiss()
                        }
                        axis == 1 && dragX < -swipeThreshold -> {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            player.next()
                            scope.launch {
                                androidx.compose.animation.core.animate(dragX, 0f, animationSpec = spring(dampingRatio = 0.65f, stiffness = 800f)) { cur, _ -> dragX = cur }
                            }
                        }
                        axis == 1 && dragX > swipeThreshold -> {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            player.previous()
                            scope.launch {
                                androidx.compose.animation.core.animate(dragX, 0f, animationSpec = spring(dampingRatio = 0.65f, stiffness = 800f)) { cur, _ -> dragX = cur }
                            }
                        }
                        else -> scope.launch {
                            androidx.compose.animation.core.animate(dragX, 0f, animationSpec = spring(dampingRatio = 0.65f, stiffness = 800f)) { cur, _ -> dragX = cur }
                            androidx.compose.animation.core.animate(dragY, 0f, animationSpec = spring(dampingRatio = 0.65f, stiffness = 800f)) { cur, _ -> dragY = cur }
                        }
                    }
                }
            },
    ) {
        Row(
            Modifier.fillMaxWidth().fillMaxHeight().padding(start = 8.dp, end = 8.dp).graphicsLayer { translationX = dragX * 0.5f; alpha = 1f - abs(dragX) / 600f },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Artwork(np.artwork, np.album, Modifier.size(48.dp), RoundedCornerShape(12.dp), lookup = np.artLookup())
            Spacer(Modifier.width(12.dp))
            AnimatedContent(np, Modifier.weight(1f), transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "mini") { track ->
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            track.title,
                            style = MaterialTheme.typography.titleSmall,
                            color = Palette.Text,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false).basicMarquee()
                        )
                        if (track.isPreview) { Spacer(Modifier.width(6.dp)); Badge("Preview") }
                    }
                    Text(track.artist, style = MaterialTheme.typography.bodySmall, color = Palette.TextDim, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Spacer(Modifier.width(8.dp))
            val playSrc = remember { MutableInteractionSource() }
            val playPressed by playSrc.collectIsPressedAsState()
            val playScale by animateFloatAsState(
                if (playPressed) 0.88f else 1f,
                spring(dampingRatio = 0.55f, stiffness = 900f),
                label = "mini_play_scale"
            )
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .graphicsLayer { scaleX = playScale; scaleY = playScale }
                    .clip(CircleShape)
                    .background(accent.primary)
                    .clickable(
                        interactionSource = playSrc,
                        indication = null,
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            player.togglePlay()
                        }
                    ),
                contentAlignment = Alignment.Center,
            ) {
                AnimatedContent(
                    targetState = state.isPlaying,
                    transitionSpec = {
                        (fadeIn(tween(150)) + scaleIn(tween(150), initialScale = 0.72f)) togetherWith
                        (fadeOut(tween(150)) + scaleOut(tween(150), targetScale = 0.72f))
                    },
                    label = "mini_play_icon"
                ) { isPlaying ->
                    Icon(
                        if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                        contentDescription = if (isPlaying) "Pause" else "Play",
                        tint = Palette.Ink,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }
            Spacer(Modifier.width(6.dp))
            val nextSrc = remember { MutableInteractionSource() }
            val nextPressed by nextSrc.collectIsPressedAsState()
            val nextScale by animateFloatAsState(
                if (nextPressed) 0.88f else 1f,
                spring(dampingRatio = 0.55f, stiffness = 900f),
                label = "mini_next_scale"
            )
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .graphicsLayer { scaleX = nextScale; scaleY = nextScale }
                    .clip(CircleShape)
                    .clickable(
                        interactionSource = nextSrc,
                        indication = null,
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            player.next()
                        }
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Rounded.SkipNext, "Next", tint = Palette.Text, modifier = Modifier.size(24.dp))
            }
        }
        MiniProgress(player, state.duration, accent.primary, Modifier.align(Alignment.BottomStart))
    }
}

@Composable
private fun MiniProgress(player: PlayerConnection, duration: Long, color: androidx.compose.ui.graphics.Color, modifier: Modifier = Modifier) {
    val pos by remember { player.positionFlow(500) }.collectAsStateWithLifecycle(0L)
    val frac = if (duration > 0) (pos.toFloat() / duration).coerceIn(0f, 1f) else 0f
    val animatedFrac by animateFloatAsState(
        targetValue = frac,
        animationSpec = tween(500, easing = LinearEasing),
        label = "mini_progress"
    )
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(2.5.dp)
            .padding(horizontal = 14.dp)
            .clip(RoundedCornerShape(1.25.dp))
            .background(Palette.Text.copy(alpha = 0.12f))
    ) {
        Box(
            Modifier
                .fillMaxWidth(animatedFrac)
                .fillMaxHeight()
                .clip(RoundedCornerShape(1.25.dp))
                .background(color)
        )
    }
}
