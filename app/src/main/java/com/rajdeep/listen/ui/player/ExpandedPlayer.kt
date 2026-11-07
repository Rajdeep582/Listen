package com.rajdeep.listen.ui.player

import androidx.lifecycle.repeatOnLifecycle

import androidx.lifecycle.compose.collectAsStateWithLifecycle

import android.content.Context
import android.media.AudioManager
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.automirrored.rounded.VolumeDown
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Lyrics
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.RepeatOne
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import com.rajdeep.listen.playback.PlayerUiState
import com.rajdeep.listen.ui.AppViewModel
import com.rajdeep.listen.ui.components.Artwork
import com.rajdeep.listen.ui.components.artLookup
import com.rajdeep.listen.ui.components.Badge
import com.rajdeep.listen.ui.components.formatDuration
import com.rajdeep.listen.ui.theme.LocalAccent
import com.rajdeep.listen.ui.theme.Palette
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

private enum class Pane { ART, LYRICS, QUEUE }

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ExpandedPlayer(
    vm: AppViewModel,
    state: PlayerUiState,
    onCollapse: () -> Unit,
    onOpenAlbum: (Long) -> Unit,
    onOpenArtist: (String) -> Unit,
) {
    val np = state.current ?: return
    val player = vm.player
    val art = com.rajdeep.listen.ui.components.rememberArtModel(np.artwork, np.artLookup())
    val colors = rememberArtColors(art)
    val accent = LocalAccent.current
    var pane by remember { mutableStateOf(Pane.ART) }
    var sleepDialog by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    // stable flow per track (no conditional remember → no slot-table mismatch when switching local ↔ online)
    val songFlow = remember(np.localId) { np.localId?.let { vm.library.dao.songFlow(it) } ?: kotlinx.coroutines.flow.flowOf(null) }
    val song by songFlow.collectAsStateWithLifecycle(null)
    var trackMenu by remember { mutableStateOf(false) }

    // Ambient motion: a slow drifting glow behind everything.
    val inf = rememberInfiniteTransition(label = "amb")
    val reduce = com.rajdeep.listen.ui.theme.LocalReduceMotion.current
    val drift = inf.animateFloat(0f, if (reduce) 0f else 1f, infiniteRepeatable(tween(14_000, easing = LinearEasing), RepeatMode.Reverse), label = "drift")
    val dragY = remember { Animatable(0f) }

    BoxWithConstraints(
        Modifier.fillMaxSize()
            .graphicsLayer { translationY = dragY.value.coerceAtLeast(0f) }
            .background(Palette.Ink)
            .background(Brush.verticalGradient(listOf(colors.dark, colors.dark.copy(alpha = 0.6f), Palette.Ink)))
    ) {
        val glow = colors.vibrant
        // Drift is read in the draw phase only: zero recompositions per animation frame.
        Box(
            Modifier.fillMaxSize().drawBehind {
                val w = size.width; val d = drift.value
                drawRect(Brush.radialGradient(listOf(glow.copy(alpha = 0.32f), Color.Transparent), center = Offset(w * (0.2f + 0.6f * d), w * (0.35f + 0.25f * d)), radius = w * 0.9f))
            }
        )
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 22.dp)) {
            // top bar (drag down to dismiss)
            Row(
                Modifier.fillMaxWidth().padding(top = 4.dp).pointerInput(Unit) {
                    detectVerticalDragGestures(
                        onDragEnd = { if (dragY.value > 220) onCollapse(); scope.launch { dragY.animateTo(0f, spring()) } },
                        onVerticalDrag = { ch, d -> ch.consume(); scope.launch { dragY.snapTo(dragY.value + d) } },
                    )
                },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onCollapse) { Icon(Icons.Rounded.KeyboardArrowDown, "Collapse", tint = Palette.Text, modifier = Modifier.size(30.dp)) }
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("PLAYING FROM", style = MaterialTheme.typography.labelSmall, color = Palette.TextDim)
                    Text(if (np.source == "Local") np.album.ifEmpty { "Your library" } else np.source, style = MaterialTheme.typography.titleSmall, color = Palette.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                // ⋮ works for every track: library songs get the full song menu, online/radio get a track menu
                IconButton(onClick = { val sg = song; if (sg != null) vm.menuSong.value = sg else trackMenu = true }) {
                    Icon(Icons.Rounded.MoreVert, "More", tint = Palette.Text)
                }
            }

            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                AnimatedContent(pane, transitionSpec = { fadeIn(tween(220)) + scaleIn(initialScale = 0.97f) togetherWith fadeOut(tween(150)) }, label = "pane") { p ->
                    when (p) {
                        Pane.ART -> ArtPane(art, np.album, np.mediaId, state.isPlaying, onNext = player::next, onPrev = player::previous)
                        Pane.LYRICS -> LyricsPane(vm, np)
                        Pane.QUEUE -> QueuePane(vm, state)
                    }
                }
            }

            // title + favourite
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(np.title, style = MaterialTheme.typography.headlineMedium, color = Palette.Text, maxLines = 1, modifier = Modifier.weight(1f, false).basicMarquee())
                        if (np.isPreview) { Spacer(Modifier.width(8.dp)); Badge("30s preview") }
                    }
                    Text(
                        np.artist, style = MaterialTheme.typography.titleMedium, color = Palette.TextDim, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.clickable(enabled = np.localId != null) { onOpenArtist(np.artist) },
                    )
                }
                if (np.localId != null) {
                    val fav = song?.isFavorite == true
                    val s by animateFloatAsState(if (fav) 1.15f else 1f, spring(dampingRatio = 0.4f), label = "fav")
                    IconButton(onClick = player::toggleFavoriteCurrent) {
                        Icon(if (fav) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder, "Favourite", tint = if (fav) accent.primary else Palette.Text, modifier = Modifier.size(28.dp).graphicsLayer { scaleX = s; scaleY = s })
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            if (np.isLive) LiveBar() else SeekBar(vm, state.duration)
            Spacer(Modifier.height(6.dp))
            // transport
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                PressIcon(
                    icon = Icons.Rounded.Shuffle,
                    label = "Shuffle",
                    size = 26.dp,
                    tint = if (state.shuffle) accent.primary else Palette.TextDim,
                    active = state.shuffle,
                    onClick = player::toggleShuffle,
                )
                PressIcon(
                    icon = Icons.Rounded.SkipPrevious,
                    label = "Previous",
                    size = 38.dp,
                    tint = Palette.Text,
                    active = false,
                    onClick = player::previous,
                )
                val playSrc = remember { MutableInteractionSource() }
                val playPressed by playSrc.collectIsPressedAsState()
                val playScale by animateFloatAsState(
                    if (playPressed) 0.88f else 1f,
                    spring(dampingRatio = 0.55f, stiffness = 900f),
                    label = "play_scale"
                )
                val haptic = LocalHapticFeedback.current
                Box(
                    Modifier
                        .size(78.dp)
                        .graphicsLayer { scaleX = playScale; scaleY = playScale }
                        .shadow(18.dp, CircleShape, ambientColor = colors.vibrant, spotColor = colors.vibrant)
                        .clip(CircleShape)
                        .background(Palette.Text)
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
                            (fadeIn(tween(150)) + scaleIn(tween(150), initialScale = 0.75f)) togetherWith
                            (fadeOut(tween(150)) + scaleOut(tween(150), targetScale = 0.75f))
                        },
                        label = "play_icon"
                    ) { playing ->
                        Icon(
                            if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                            "Play/Pause",
                            tint = Palette.Ink,
                            modifier = Modifier.size(42.dp)
                        )
                    }
                }
                PressIcon(
                    icon = Icons.Rounded.SkipNext,
                    label = "Next",
                    size = 38.dp,
                    tint = Palette.Text,
                    active = false,
                    onClick = player::next,
                )
                val repeatOne = state.repeat == Player.REPEAT_MODE_ONE
                val repeatOn = state.repeat != Player.REPEAT_MODE_OFF
                PressIcon(
                    icon = if (repeatOne) Icons.Rounded.RepeatOne else Icons.Rounded.Repeat,
                    label = "Repeat",
                    size = 26.dp,
                    tint = if (repeatOn) accent.primary else Palette.TextDim,
                    active = repeatOn,
                    onClick = player::cycleRepeat,
                )
            }
            Spacer(Modifier.height(4.dp))
            VolumeRow(colors.vibrant)
            // pane switches
            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                PaneButton(Icons.Rounded.Lyrics, "Lyrics", pane == Pane.LYRICS) { pane = if (pane == Pane.LYRICS) Pane.ART else Pane.LYRICS }
                PaneButton(Icons.AutoMirrored.Rounded.QueueMusic, "Queue", pane == Pane.QUEUE) { pane = if (pane == Pane.QUEUE) Pane.ART else Pane.QUEUE }
                PaneButton(Icons.Rounded.Bedtime, sleepLabel(state.sleepAt), state.sleepAt > 0) { sleepDialog = true }
                PaneButton(Icons.AutoMirrored.Rounded.PlaylistAdd, "Add", false, enabled = song != null) { song?.let { vm.addToPlaylistSongs.value = listOf(it) } }
            }
        }
    }

    if (trackMenu) OnlineTrackMenu(np, onLyrics = { pane = Pane.LYRICS }, onQueue = { pane = Pane.QUEUE }, onSleep = { sleepDialog = true }) { trackMenu = false }

    if (sleepDialog) {
        AlertDialog(
            onDismissRequest = { sleepDialog = false },
            containerColor = Palette.SurfaceHigh,
            title = { Text("Sleep timer", color = Palette.Text) },
            text = {
                Column {
                    listOf(10, 15, 30, 45, 60, 90).forEach { m ->
                        Text("$m minutes", style = MaterialTheme.typography.titleMedium, color = Palette.Text,
                            modifier = Modifier.fillMaxWidth().clickable { player.sleepIn(m); sleepDialog = false }.padding(vertical = 12.dp))
                    }
                }
            },
            confirmButton = { if (state.sleepAt > 0) TextButton(onClick = { player.sleepIn(0); sleepDialog = false }) { Text("Turn off") } },
            dismissButton = { TextButton(onClick = { sleepDialog = false }) { Text("Close") } },
        )
    }
}

@Composable
private fun sleepLabel(at: Long): String {
    if (at <= 0) return "Sleep"
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(at) { while (true) { now = System.currentTimeMillis(); delay(15_000) } }
    return "${((at - now) / 60_000 + 1).coerceAtLeast(0)} min"
}

@Composable
private fun ArtPane(art: Any?, seed: String, mediaId: String, playing: Boolean, onNext: () -> Unit, onPrev: () -> Unit) {
    val reduce = com.rajdeep.listen.ui.theme.LocalReduceMotion.current
    val scope = rememberCoroutineScope()
    val dx = remember { Animatable(0f) }
    val scale by animateFloatAsState(if (playing) 1f else 0.92f, spring(dampingRatio = 0.62f, stiffness = 200f), label = "disc")
    // Spin continuously while playing; ease to a stop on pause. Read only in graphicsLayer → no recomposition.
    val spin = remember { Animatable(0f) }
    LaunchedEffect(playing, reduce) {
        if (reduce) return@LaunchedEffect
        if (playing) while (true) spin.animateTo(spin.value + 360f, tween(6000, easing = LinearEasing))
        else spin.animateTo(spin.value + 45f, tween(900, easing = androidx.compose.animation.core.LinearOutSlowInEasing))
    }
    Box(
        Modifier.widthIn(max = 420.dp).fillMaxWidth().aspectRatio(1f).padding(12.dp)
            .pointerInput(mediaId) {
                detectHorizontalDragGestures(
                    onDragEnd = {
                        val v = dx.value
                        if (v < -180) onNext() else if (v > 180) onPrev()
                        scope.launch { dx.animateTo(0f, spring()) }
                    },
                    onHorizontalDrag = { c, d -> c.consume(); scope.launch { dx.snapTo(dx.value + d) } },
                )
            }
            .graphicsLayer { scaleX = scale; scaleY = scale; translationX = dx.value * 0.6f; alpha = 1f - abs(dx.value) / 900f },
        contentAlignment = Alignment.Center,
    ) {
        // one disc only: the poster is printed on it when available, otherwise a classic silver CD
        Box(Modifier.fillMaxSize().graphicsLayer { rotationZ = spin.value + dx.value / 5f }) {
            AnimatedContent(art, transitionSpec = { fadeIn(tween(350)) togetherWith fadeOut(tween(250)) }, label = "disc") { a -> CompactDisc(a) }
        }
        // light reflection stays fixed while the disc turns
        Box(
            Modifier.fillMaxSize().clip(CircleShape).drawBehind {
                drawCircle(Brush.sweepGradient(listOf(Color.White.copy(0f), Color.White.copy(0.20f), Color.White.copy(0f), Color.White.copy(0f), Color.White.copy(0.13f), Color.White.copy(0f)), center))
                drawCircle(Brush.linearGradient(listOf(Color.White.copy(0.10f), Color.Transparent), end = Offset(size.width * 0.5f, size.height * 0.5f)))
            },
        )
    }
    @Suppress("UNUSED_VARIABLE") val unused = seed
}

/** Classic CD; if [art] loads it is printed across the face, otherwise the iridescent silver disc shows. */
@Composable
private fun CompactDisc(art: Any?) {
    Box(Modifier.fillMaxSize().shadow(22.dp, CircleShape).clip(CircleShape)) {
        // silver + rainbow data surface (visible when no poster)
        Box(Modifier.fillMaxSize().drawBehind {
            val c = center; val r = size.minDimension / 2
            drawCircle(Brush.radialGradient(listOf(Color(0xFFF1F1F5), Color(0xFFC3C5CE), Color(0xFF9799A6)), c, r))
            drawCircle(
                Brush.sweepGradient(listOf(Color(0x88FF6AD5), Color(0x884CC9F0), Color(0x88A6FF8F), Color(0x88FFE066), Color(0x88FF6A3D), Color(0x88B388FF), Color(0x88FF6AD5)), c),
                r * 0.66f, c, style = androidx.compose.ui.graphics.drawscope.Stroke(r * 0.62f),
            )
        })
        if (art != null) coil3.compose.AsyncImage(
            model = art, contentDescription = null, contentScale = androidx.compose.ui.layout.ContentScale.Crop,
            modifier = Modifier.fillMaxSize().clip(CircleShape),
        )
        Box(Modifier.fillMaxSize().drawBehind {
            val c = center; val r = size.minDimension / 2
            var rr = r * 0.36f
            while (rr < r * 0.97f) { drawCircle(Color.Black.copy(alpha = 0.07f), rr, c, style = androidx.compose.ui.graphics.drawscope.Stroke(1f)); rr += r * 0.03f }
            drawCircle(Color.White.copy(alpha = 0.22f), r * 0.985f, c, style = androidx.compose.ui.graphics.drawscope.Stroke(r * 0.03f))
            // clear hub + spindle hole
            drawCircle(Color(0xCCE6E8EE), r * 0.25f, c)
            drawCircle(Color(0xFFB9BCC6), r * 0.17f, c)
            drawCircle(Color.Black.copy(alpha = 0.2f), r * 0.17f, c, style = androidx.compose.ui.graphics.drawscope.Stroke(2f))
            drawCircle(Palette.Ink, r * 0.075f, c)
            drawCircle(Color.Black.copy(alpha = 0.35f), r, c, style = androidx.compose.ui.graphics.drawscope.Stroke(2f))
        })
    }
}

@Composable
private fun LiveBar() {
    val inf = rememberInfiniteTransition(label = "live")
    val a = inf.animateFloat(0.35f, 1f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "dot")
    Row(Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(9.dp).graphicsLayer { alpha = a.value }.clip(CircleShape).background(Palette.Danger))
        Spacer(Modifier.width(8.dp))
        Text("LIVE RADIO", style = MaterialTheme.typography.labelLarge, color = Palette.Text)
        Spacer(Modifier.weight(1f))
        Text("Streaming via Radio Browser", style = MaterialTheme.typography.labelMedium, color = Palette.TextDim)
    }
}

@Composable
private fun PressIcon(
    icon: ImageVector,
    label: String,
    size: Dp,
    tint: Color,
    active: Boolean = false,
    onClick: () -> Unit,
) {
    val accent = LocalAccent.current.primary
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    val scale by animateFloatAsState(
        if (pressed) 0.86f else 1f,
        spring(dampingRatio = 0.55f, stiffness = 900f),
        label = "press_icon"
    )
    val haptic = LocalHapticFeedback.current
    Column(
        modifier = Modifier
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(CircleShape)
            .clickable(
                interactionSource = src,
                indication = null,
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    onClick()
                }
            )
            .padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(icon, contentDescription = label, tint = tint, modifier = Modifier.size(size))
        Spacer(Modifier.height(3.dp))
        Box(
            Modifier
                .size(4.dp)
                .clip(CircleShape)
                .background(if (active) accent else Color.Transparent)
        )
    }
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun SeekBar(vm: AppViewModel, duration: Long) {
    val pos by remember { vm.player.positionFlow(200) }.collectAsStateWithLifecycle(0L)
    var drag by remember { mutableStateOf<Float?>(null) }
    val len = duration.coerceAtLeast(1)
    val shown = drag?.toLong() ?: pos
    val interactionSource = remember { MutableInteractionSource() }
    val isDragged by interactionSource.collectIsDraggedAsState()
    val thumbSize by animateDpAsState(
        targetValue = if (isDragged) 20.dp else 14.dp,
        animationSpec = spring(dampingRatio = 0.6f, stiffness = 900f),
        label = "seek_thumb"
    )
    val sliderColors = SliderDefaults.colors(
        thumbColor = Palette.Text,
        activeTrackColor = Palette.Text,
        inactiveTrackColor = Palette.Text.copy(alpha = 0.18f)
    )
    Column {
        Slider(
            value = shown.toFloat().coerceIn(0f, len.toFloat()),
            valueRange = 0f..len.toFloat(),
            onValueChange = { drag = it },
            onValueChangeFinished = { drag?.let { vm.player.seekTo(it.toLong()) }; drag = null },
            interactionSource = interactionSource,
            colors = sliderColors,
            modifier = Modifier.height(28.dp),
            thumb = {
                Box(
                    Modifier
                        .size(thumbSize)
                        .clip(CircleShape)
                        .background(Palette.Text)
                )
            },
            track = { sliderState ->
                SliderDefaults.Track(
                    sliderState = sliderState,
                    modifier = Modifier.height(4.dp),
                    colors = sliderColors,
                    drawStopIndicator = null,
                    thumbTrackGapSize = 0.dp,
                )
            }
        )
        Row {
            Text(formatDuration(shown), style = MaterialTheme.typography.labelMedium, color = Palette.TextDim)
            Spacer(Modifier.weight(1f))
            Text("−" + formatDuration((duration - shown).coerceAtLeast(0)), style = MaterialTheme.typography.labelMedium, color = Palette.TextDim)
        }
    }
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun VolumeRow(color: Color) {
    val ctx = LocalContext.current
    val am = remember { ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    val max = remember { am.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1) }
    var vol by remember { mutableFloatStateOf(am.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat()) }
    var dragging by remember { mutableStateOf(false) }
    val lo = androidx.lifecycle.compose.LocalLifecycleOwner.current
    LaunchedEffect(lo) { lo.lifecycle.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) { while (true) { if (!dragging) vol = am.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat(); delay(800) } } }
    val interactionSource = remember { MutableInteractionSource() }
    val isDragged by interactionSource.collectIsDraggedAsState()
    val thumbSize by animateDpAsState(
        targetValue = if (isDragged) 20.dp else 14.dp,
        animationSpec = spring(dampingRatio = 0.6f, stiffness = 900f),
        label = "vol_thumb"
    )
    val sliderColors = SliderDefaults.colors(
        thumbColor = color,
        activeTrackColor = color,
        inactiveTrackColor = Palette.Text.copy(alpha = 0.14f)
    )
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.AutoMirrored.Rounded.VolumeDown, null, tint = Palette.TextDim, modifier = Modifier.size(20.dp))
        Slider(
            value = vol,
            valueRange = 0f..max.toFloat(),
            onValueChange = { dragging = true; vol = it; am.setStreamVolume(AudioManager.STREAM_MUSIC, it.roundToInt(), 0) },
            onValueChangeFinished = { dragging = false },
            interactionSource = interactionSource,
            colors = sliderColors,
            modifier = Modifier.weight(1f).height(24.dp).padding(horizontal = 6.dp),
            thumb = {
                Box(
                    Modifier
                        .size(thumbSize)
                        .clip(CircleShape)
                        .background(color)
                )
            },
            track = { sliderState ->
                SliderDefaults.Track(
                    sliderState = sliderState,
                    modifier = Modifier.height(4.dp),
                    colors = sliderColors,
                    drawStopIndicator = null,
                    thumbTrackGapSize = 0.dp,
                )
            }
        )
        Icon(Icons.AutoMirrored.Rounded.VolumeUp, null, tint = Palette.TextDim, modifier = Modifier.size(20.dp))
    }
}

@Composable
private fun PaneButton(icon: ImageVector, label: String, active: Boolean, enabled: Boolean = true, onClick: () -> Unit) {
    val accent = LocalAccent.current.primary
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    val scale by animateFloatAsState(
        if (pressed) 0.88f else 1f,
        spring(dampingRatio = 0.55f, stiffness = 900f),
        label = "pane_scale"
    )
    val haptic = LocalHapticFeedback.current
    val bgColor by animateColorAsState(
        targetValue = if (active) accent.copy(alpha = 0.16f) else Color.Transparent,
        animationSpec = tween(200),
        label = "pane_bg"
    )
    Column(
        modifier = Modifier
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(RoundedCornerShape(14.dp))
            .background(bgColor)
            .clickable(
                enabled = enabled,
                interactionSource = src,
                indication = null,
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    onClick()
                }
            )
            .padding(horizontal = 14.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, label, tint = when { !enabled -> Palette.TextFaint; active -> accent; else -> Palette.Text }, modifier = Modifier.size(22.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, color = if (active) accent else Palette.TextDim)
    }
}


/** ⋮ menu for online / radio tracks (no library entry): share, lyrics, queue, sleep timer. */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun OnlineTrackMenu(np: com.rajdeep.listen.playback.NowPlaying, onLyrics: () -> Unit, onQueue: () -> Unit, onSleep: () -> Unit, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    androidx.compose.material3.ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Palette.SurfaceHigh) {
        Column(Modifier.navigationBarsPadding().padding(bottom = 12.dp)) {
            Column(Modifier.padding(horizontal = 22.dp, vertical = 8.dp)) {
                Text(np.title, style = MaterialTheme.typography.titleMedium, color = Palette.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(listOf(np.artist, np.source).filter { it.isNotBlank() }.joinToString(" · "), style = MaterialTheme.typography.bodyMedium, color = Palette.TextDim, maxLines = 1)
            }
            @Composable fun item(icon: ImageVector, label: String, onClick: () -> Unit) {
                Row(Modifier.fillMaxWidth().clickable { onClick(); onDismiss() }.padding(horizontal = 22.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(icon, null, tint = Palette.TextDim); Spacer(Modifier.width(18.dp))
                    Text(label, style = MaterialTheme.typography.titleMedium, color = Palette.Text)
                }
            }
            if (!np.isLive) item(Icons.Rounded.Lyrics, "Show lyrics", onLyrics)
            item(Icons.AutoMirrored.Rounded.QueueMusic, "Show queue", onQueue)
            item(Icons.Rounded.Bedtime, "Sleep timer", onSleep)
            item(Icons.Rounded.Share, "Share") {
                val send = android.content.Intent(android.content.Intent.ACTION_SEND).setType("text/plain")
                    .putExtra(android.content.Intent.EXTRA_TEXT, "${np.title} — ${np.artist}")
                ctx.startActivity(android.content.Intent.createChooser(send, np.title))
            }
        }
    }
}
