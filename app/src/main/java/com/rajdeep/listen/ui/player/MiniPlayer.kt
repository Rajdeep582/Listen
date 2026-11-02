package com.rajdeep.listen.ui.player

import androidx.lifecycle.compose.collectAsStateWithLifecycle

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
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

@Composable
fun MiniPlayer(player: PlayerConnection, state: PlayerUiState, onExpand: () -> Unit, modifier: Modifier = Modifier) {
    val np = state.current ?: return
    val accent = LocalAccent.current
    val colors = rememberArtColors(com.rajdeep.listen.ui.components.rememberArtModel(np.artwork, np.artLookup()))
    val scope = rememberCoroutineScope()
    val dragX = remember { Animatable(0f) }

    Box(
        modifier.fillMaxWidth().height(64.dp).clip(RoundedCornerShape(18.dp))
            .background(Brush.horizontalGradient(listOf(colors.dark, androidx.compose.ui.graphics.lerp(Palette.SurfaceHigh, accent.primary, 0.14f))))
            .border(1.dp, Brush.horizontalGradient(listOf(accent.primary.copy(alpha = 0.45f), accent.secondary.copy(alpha = 0.15f))), RoundedCornerShape(18.dp))
            .clickable(onClick = onExpand)
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragEnd = {
                        val v = dragX.value
                        if (v < -140) player.next() else if (v > 140) player.previous()
                        scope.launch { dragX.animateTo(0f) }
                    },
                    onHorizontalDrag = { ch, d -> ch.consume(); scope.launch { dragX.snapTo((dragX.value + d).coerceIn(-260f, 260f)) } },
                )
            },
    ) {
        Row(
            Modifier.fillMaxWidth().fillMaxHeight().padding(start = 8.dp, end = 4.dp).graphicsLayer { translationX = dragX.value * 0.5f; alpha = 1f - kotlin.math.abs(dragX.value) / 600f },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Artwork(np.artwork, np.album, Modifier.size(48.dp), RoundedCornerShape(12.dp), lookup = np.artLookup())
            Spacer(Modifier.width(12.dp))
            AnimatedContent(np.mediaId, Modifier.weight(1f), transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "mini") { _ ->
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(np.title, style = MaterialTheme.typography.titleSmall, color = Palette.Text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                        if (np.isPreview) { Spacer(Modifier.width(6.dp)); Badge("Preview") }
                    }
                    Text(np.artist, style = MaterialTheme.typography.bodySmall, color = Palette.TextDim, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            IconButton(onClick = player::togglePlay) {
                Icon(if (state.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, "Play/Pause", tint = Palette.Text, modifier = Modifier.size(30.dp))
            }
            IconButton(onClick = player::next) { Icon(Icons.Rounded.SkipNext, "Next", tint = Palette.Text) }
        }
        MiniProgress(player, state.duration, accent.primary, Modifier.align(Alignment.BottomStart))
    }
}

@Composable
private fun MiniProgress(player: PlayerConnection, duration: Long, color: androidx.compose.ui.graphics.Color, modifier: Modifier) {
    val pos by remember { player.positionFlow(500) }.collectAsStateWithLifecycle(0L)
    val f = if (duration > 0) (pos.toFloat() / duration).coerceIn(0f, 1f) else 0f
    Box(modifier.fillMaxWidth().height(2.dp).padding(horizontal = 14.dp)) {
        Box(Modifier.fillMaxWidth(f).fillMaxHeight().background(color))
    }
}
