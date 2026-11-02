package com.rajdeep.listen.video

import androidx.lifecycle.compose.collectAsStateWithLifecycle

import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.animation.togetherWith
import androidx.compose.material.icons.rounded.Forward10
import androidx.compose.material.icons.rounded.Replay10
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.AspectRatio
import androidx.compose.material.icons.rounded.Audiotrack
import androidx.compose.material.icons.rounded.BrightnessMedium
import androidx.compose.material.icons.rounded.ClosedCaption
import androidx.compose.material.icons.rounded.FastForward
import androidx.compose.material.icons.rounded.FastRewind
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PictureInPictureAlt
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Replay
import androidx.compose.material.icons.rounded.Rotate90DegreesCw
import androidx.compose.material.icons.rounded.ScreenRotation
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.rajdeep.listen.ui.components.formatDuration
import com.rajdeep.listen.ui.theme.LocalAccent
import com.rajdeep.listen.ui.theme.Palette
import org.videolan.libvlc.util.VLCVideoLayout
import kotlin.math.abs
import kotlin.math.roundToInt

enum class Panel { NONE, AUDIO, SUBS, SUB_STYLE, SPEED, SCALE, INFO }

@Composable
fun VideoPlayerScreen(host: VideoPlayerActivity) {
    val vlc = host.vlc
    val state by vlc.state.collectAsStateWithLifecycle()
    val settings by host.settingsFlow.collectAsStateWithLifecycle()
    val inPip by host.inPip
    val accent = LocalAccent.current

    var controlsVisible by remember { mutableStateOf(true) }
    var locked by remember { mutableStateOf(false) }
    var panel by remember { mutableStateOf(Panel.NONE) }
    var interactionTick by remember { mutableIntStateOf(0) }

    // transient indicators
    var indicator by remember { mutableStateOf<Indicator?>(null) }
    var indicatorTick by remember { mutableIntStateOf(0) }
    var seekPreview by remember { mutableStateOf<Long?>(null) }
    var seekBase by remember { mutableLongStateOf(0L) }
    var tapFeedback by remember { mutableStateOf<Pair<Int, Int>?>(null) } // side to accumulated seconds
    var tapTick by remember { mutableIntStateOf(0) }

    // volume model: 0..1 = system volume, 1..2 = libVLC boost (if enabled)
    var volLevel by remember { mutableFloatStateOf(host.systemVolume().toFloat() / host.maxVolume().coerceAtLeast(1)) }

    // transform
    var zoom by remember { mutableFloatStateOf(1f) }
    var zoomStart by remember { mutableFloatStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }
    var rotation by remember { mutableIntStateOf(0) }
    var boxSize by remember { mutableStateOf(IntSize.Zero) }
    var toast by remember { mutableStateOf<String?>(null) }

    val seekStep = settings.seekStepSec * 1000L
    var pipBlocked by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { host.onPipBlocked = { pipBlocked = true } }
    if (pipBlocked) androidx.compose.material3.AlertDialog(
        onDismissRequest = { pipBlocked = false }, containerColor = Palette.SurfaceHigh,
        title = { Text("Mini player is turned off", color = Palette.Text) },
        text = { Text("Picture-in-picture is disabled for Listen. Turn it on to keep watching in a small floating window while you use other apps.", color = Palette.TextDim) },
        confirmButton = { androidx.compose.material3.TextButton(onClick = { pipBlocked = false; host.openPipSettings() }) { Text("Open settings", color = accent.primary) } },
        dismissButton = { androidx.compose.material3.TextButton(onClick = { pipBlocked = false }) { Text("Cancel", color = Palette.TextDim) } },
    )

    fun poke() { interactionTick++ }

    LaunchedEffect(state.videoW, state.videoH) { if (state.videoW > 0) host.onVideoSizeKnown(state.videoW, state.videoH) }
    LaunchedEffect(state.isPlaying) { host.updatePip() }
    LaunchedEffect(controlsVisible, interactionTick, state.isPlaying, panel) {
        if (controlsVisible && state.isPlaying && panel == Panel.NONE) { kotlinx.coroutines.delay(3500); controlsVisible = false }
    }
    LaunchedEffect(indicatorTick) { if (indicator != null) { kotlinx.coroutines.delay(900); indicator = null } }
    LaunchedEffect(tapTick) { if (tapFeedback != null) { kotlinx.coroutines.delay(700); tapFeedback = null } }
    LaunchedEffect(toast) { if (toast != null) { kotlinx.coroutines.delay(2500); toast = null } }
    LaunchedEffect(state.ended) {
        if (state.ended) { host.saveState(); if (!settings.autoPlayNextVideo || !host.next()) controlsVisible = true }
    }

    BackHandler(enabled = panel != Panel.NONE) { panel = Panel.NONE }

    val callbacks = remember(settings, state.length) {
        object : GestureCallbacks {
            override fun onTap() {
                if (panel != Panel.NONE) { panel = Panel.NONE; return }
                controlsVisible = !controlsVisible; poke()
            }
            override fun onDoubleTap(side: Int) {
                when (side) {
                    0 -> { val wasPlaying = vlc.isPlaying(); if (!vlc.togglePlay()) host.replay(); indicator = Indicator.Play(!wasPlaying); indicatorTick++ }
                    else -> {
                        vlc.seekBy(side * seekStep)
                        val prev = tapFeedback
                        val acc = if (prev != null && prev.first == side) prev.second + settings.seekStepSec else settings.seekStepSec
                        tapFeedback = side to acc; tapTick++
                    }
                }
            }
            override fun onBrightnessDrag(delta: Float) {
                host.setBrightness(host.brightness.floatValue + delta)
                indicator = Indicator.Level(Icons.Rounded.BrightnessMedium, host.brightness.floatValue, "Brightness"); indicatorTick++
            }
            override fun onVolumeDrag(delta: Float) {
                val max = if (settings.volumeBoost) 2f else 1f
                volLevel = (volLevel + delta).coerceIn(0f, max)
                val sys = (volLevel.coerceAtMost(1f) * host.maxVolume()).roundToInt()
                host.setSystemVolume(sys)
                vlc.setVolume(if (volLevel > 1f) (100 + (volLevel - 1f) * 100).roundToInt() else 100)
                indicator = Indicator.Level(Icons.Rounded.VolumeUp, volLevel / max, if (volLevel > 1f) "Boost ${(volLevel * 100).roundToInt()}%" else "Volume ${(volLevel * 100).roundToInt()}%"); indicatorTick++
            }
            override fun onAdjustEnd() { indicatorTick++ }
            override fun onSeekDrag(fraction: Float) {
                if (seekPreview == null) { seekBase = vlc.currentTime(); volLevel = host.systemVolume().toFloat() / host.maxVolume() }
                val span = if (state.length in 1..90_000) state.length else 90_000L
                val target = (seekBase + (fraction * span).toLong()).coerceIn(0, state.length.coerceAtLeast(0))
                seekPreview = target
            }
            override fun onSeekEnd(commit: Boolean) {
                seekPreview?.let { if (commit) vlc.seekTo(it) }
                seekPreview = null
            }
            override fun onZoomStart() { zoomStart = zoom }
            override fun onZoom(relativeScale: Float, pan2: Offset) {
                zoom = (zoomStart * relativeScale).coerceIn(1f, 4f)
                pan = if (zoom <= 1.01f) Offset.Zero else {
                    val p = pan + pan2
                    val maxX = boxSize.width * (zoom - 1) / 2; val maxY = boxSize.height * (zoom - 1) / 2
                    Offset(p.x.coerceIn(-maxX, maxX), p.y.coerceIn(-maxY, maxY))
                }
                indicator = Indicator.Text("Zoom ${(zoom * 100).roundToInt()}%"); indicatorTick++
            }
            override fun onZoomEnd() { if (zoom < 1.05f) { zoom = 1f; pan = Offset.Zero } }
        }
    }

    // Keep the volume model in sync with hardware keys whenever controls reappear.
    LaunchedEffect(controlsVisible) { if (controlsVisible && volLevel <= 1f) volLevel = host.systemVolume().toFloat() / host.maxVolume().coerceAtLeast(1) }

    val rotScale = if (rotation % 180 != 0 && boxSize.width > 0 && boxSize.height > 0)
        minOf(boxSize.width, boxSize.height).toFloat() / maxOf(boxSize.width, boxSize.height) else 1f
    val animRot by animateFloatAsState(rotation.toFloat(), tween(260), label = "rot")

    Box(Modifier.fillMaxSize().background(Color.Black).onSizeChanged { boxSize = it }) {
        AndroidView(
            factory = { ctx -> VLCVideoLayout(ctx).also { vlc.attach(it) } },
            modifier = Modifier.fillMaxSize().graphicsLayer {
                scaleX = zoom * rotScale; scaleY = zoom * rotScale
                translationX = pan.x; translationY = pan.y
                rotationZ = animRot
            },
        )

        if (!inPip) {
            GestureLayer(
                config = GestureConfig(
                    brightness = settings.gestureBrightness, volume = settings.gestureVolume, seek = settings.gestureSeek && state.seekable,
                    doubleTap = settings.gestureDoubleTap, pinch = settings.gesturePinch, locked = locked,
                ),
                callbacks = callbacks,
            )

            // ---- indicators ----
            IndicatorOverlay(indicator, Modifier.align(Alignment.Center))
            seekPreview?.let { target ->
                val delta = target - seekBase
                Column(Modifier.align(Alignment.Center).clip(RoundedCornerShape(18.dp)).background(Color.Black.copy(0.6f)).padding(horizontal = 22.dp, vertical = 14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text((if (delta >= 0) "+" else "−") + formatDuration(abs(delta)), color = accent.secondary, style = MaterialTheme.typography.headlineMedium)
                    Text("${formatDuration(target)} / ${formatDuration(state.length)}", color = Palette.Text, style = MaterialTheme.typography.bodyMedium)
                }
            }
            tapFeedback?.let { (side, secs) ->
                Box(
                    Modifier.align(if (side < 0) Alignment.CenterStart else Alignment.CenterEnd).fillMaxHeight().fillMaxWidth(0.36f)
                        .clip(if (side < 0) RoundedCornerShape(topEndPercent = 50, bottomEndPercent = 50) else RoundedCornerShape(topStartPercent = 50, bottomStartPercent = 50))
                        .background(Color.White.copy(0.12f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(if (side < 0) Icons.Rounded.FastRewind else Icons.Rounded.FastForward, null, tint = Color.White, modifier = Modifier.size(36.dp))
                        Text("${secs}s", color = Color.White, style = MaterialTheme.typography.titleMedium)
                    }
                }
            }
            if (state.buffering && !state.isPlaying && state.error == null) {
                androidx.compose.material3.CircularProgressIndicator(Modifier.align(Alignment.Center).size(44.dp), color = accent.primary, strokeWidth = 3.dp)
            }
            state.error?.let { err ->
                Column(Modifier.align(Alignment.Center).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(err, color = Palette.Text, style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        PillButton("Retry") { host.openCurrent() }
                        if (host.index.intValue < host.playlist.value.lastIndex) PillButton("Next") { host.next() }
                    }
                }
            }

            // ---- controls ----
            AnimatedVisibility(controlsVisible && !locked, enter = fadeIn(tween(160)), exit = fadeOut(tween(220))) {
                ControlsOverlay(
                    host = host, state = state, accent = accent.primary,
                    onPanel = { panel = it; poke() }, onLock = { locked = true; controlsVisible = true; poke() },
                    onRotate = { rotation = (rotation + 90) % 360; poke() },
                    onInteract = ::poke,
                    onToast = { toast = it },
                )
            }
            AnimatedVisibility(locked && controlsVisible, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.CenterStart)) {
                Row(
                    Modifier.padding(24.dp).glass(CircleShape).clickable { locked = false; poke() }.padding(horizontal = 18.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Rounded.Lock, "Unlock", tint = Color.White, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Tap to unlock", color = Color.White, style = MaterialTheme.typography.labelMedium)
                }
            }

            // ---- side panels ----
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val landscape = maxWidth > maxHeight
                AnimatedVisibility(
                    visible = panel != Panel.NONE,
                    enter = if (landscape) slideInHorizontally { it } + fadeIn() else slideInVertically { it } + fadeIn(),
                    exit = if (landscape) slideOutHorizontally { it } + fadeOut() else slideOutVertically { it } + fadeOut(),
                    modifier = Modifier.align(if (landscape) Alignment.CenterEnd else Alignment.BottomCenter),
                ) {
                    val m = if (landscape) Modifier.fillMaxHeight().width(360.dp) else Modifier.fillMaxWidth().height(maxHeight * 0.62f)
                    Box(
                        m.clip(if (landscape) RoundedCornerShape(topStart = 24.dp, bottomStart = 24.dp) else RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
                            .background(Palette.Surface.copy(alpha = 0.94f))
                            .clickable(enabled = false) {}
                            .windowInsetsPadding(WindowInsets.safeDrawing),
                    ) {
                        when (panel) {
                            Panel.AUDIO -> AudioPanel(vlc, state)
                            Panel.SUBS -> SubtitlePanel(host, state, onStyle = { panel = Panel.SUB_STYLE }, onToast = { toast = it })
                            Panel.SUB_STYLE -> SubtitleStylePanel(host, settings, onBack = { panel = Panel.SUBS })
                            Panel.SPEED -> SpeedPanel(vlc, state.rate)
                            Panel.SCALE -> ScalePanel(vlc, state.scale, zoom, onResetZoom = { zoom = 1f; pan = Offset.Zero }, rotation, onRotation = { rotation = it })
                            Panel.INFO -> InfoPanel(host, state)
                            Panel.NONE -> {}
                        }
                    }
                }
            }

            toast?.let {
                Text(
                    it, color = Palette.Text, style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.align(Alignment.TopCenter).padding(top = 72.dp).clip(RoundedCornerShape(12.dp)).background(Color.Black.copy(0.7f)).padding(horizontal = 16.dp, vertical = 10.dp),
                )
            }
        }
    }
}

sealed interface Indicator {
    data class Level(val icon: ImageVector, val value: Float, val label: String) : Indicator
    data class Play(val playing: Boolean) : Indicator
    data class Text(val text: String) : Indicator
}

@Composable
private fun IndicatorOverlay(indicator: Indicator?, modifier: Modifier) {
    val accent = LocalAccent.current
    AnimatedVisibility(indicator != null, enter = fadeIn(tween(90)) + scaleIn(initialScale = 0.9f), exit = fadeOut(tween(250)) + scaleOut(targetScale = 0.95f), modifier = modifier) {
        val ind = indicator ?: return@AnimatedVisibility
        Box(Modifier.clip(RoundedCornerShape(20.dp)).background(Color.Black.copy(0.62f)).padding(horizontal = 22.dp, vertical = 16.dp), contentAlignment = Alignment.Center) {
            when (ind) {
                is Indicator.Level -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(ind.icon, null, tint = Color.White, modifier = Modifier.size(28.dp))
                    Spacer(Modifier.height(10.dp))
                    Box(Modifier.width(160.dp).height(5.dp).clip(CircleShape).background(Color.White.copy(0.18f))) {
                        Box(Modifier.fillMaxHeight().fillMaxWidth(ind.value.coerceIn(0f, 1f)).clip(CircleShape).background(Brush.horizontalGradient(listOf(accent.primary, accent.secondary))))
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(ind.label, color = Color.White, style = MaterialTheme.typography.labelMedium)
                }
                is Indicator.Play -> Icon(if (ind.playing) Icons.Rounded.PlayArrow else Icons.Rounded.Pause, null, tint = Color.White, modifier = Modifier.size(52.dp))
                is Indicator.Text -> Text(ind.text, color = Color.White, style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}

private val Glass = Color.Black.copy(alpha = 0.38f)
private val GlassLine = Color.White.copy(alpha = 0.12f)

private fun Modifier.glass(shape: androidx.compose.ui.graphics.Shape) =
    this.clip(shape).background(Glass).border(1.dp, GlassLine, shape)

@Composable
private fun ControlsOverlay(
    host: VideoPlayerActivity,
    state: VideoUiState,
    accent: Color,
    onPanel: (Panel) -> Unit,
    onLock: () -> Unit,
    onRotate: () -> Unit,
    onInteract: () -> Unit,
    onToast: (String) -> Unit,
) {
    val vlc = host.vlc
    val title = host.current?.title.orEmpty()
    val playlist by host.playlist
    val idx = host.index.intValue
    val res = if (state.videoH > 0) {
        val h = minOf(state.videoW, state.videoH)
        when { h >= 2000 -> "4K"; h >= 1400 -> "1440p"; h >= 1000 -> "1080p"; h >= 700 -> "720p"; else -> "${h}p" }
    } else null
    Box(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxWidth().height(160.dp).align(Alignment.TopCenter).background(Brush.verticalGradient(listOf(Color.Black.copy(0.78f), Color.Black.copy(0.35f), Color.Transparent))))
        Box(Modifier.fillMaxWidth().height(220.dp).align(Alignment.BottomCenter).background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(0.45f), Color.Black.copy(0.88f)))))

        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(horizontal = 12.dp, vertical = 6.dp)) {
            // ---------- top bar ----------
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                GlassIcon(Icons.AutoMirrored.Rounded.ArrowBack, "Back", 44.dp) { host.finish() }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(title, color = Color.White, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        listOfNotNull(res, if (playlist.size > 1) "${idx + 1} of ${playlist.size}" else null, if (state.rate != 1f) "${trimRate(state.rate)}×" else null).joinToString("  ·  "),
                        color = Color.White.copy(0.65f), style = MaterialTheme.typography.labelMedium, maxLines = 1,
                    )
                }
                Row(Modifier.glass(CircleShape).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    TopAction(Icons.Rounded.Audiotrack, "Audio", badge = state.audioTracks.count { it.id >= 0 }.takeIf { it > 1 }, lit = false) { onPanel(Panel.AUDIO) }
                    TopAction(Icons.Rounded.ClosedCaption, "Subtitles", badge = null, lit = state.spuTrack >= 0) { onPanel(Panel.SUBS) }
                    TopAction(Icons.Rounded.Info, "Info", badge = null, lit = false) { onPanel(Panel.INFO) }
                }
            }
            Spacer(Modifier.weight(1f))
            // ---------- centre transport ----------
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                GlassIcon(Icons.Rounded.SkipPrevious, "Previous", 50.dp) { host.previous(); onInteract() }
                Spacer(Modifier.width(20.dp))
                GlassIcon(Icons.Rounded.Replay10, "Back 10 s", 56.dp) { vlc.seekBy(-10_000); onInteract() }
                Spacer(Modifier.width(24.dp))
                PlayButton(state, accent) { if (!vlc.togglePlay()) host.replay(); onInteract() }
                Spacer(Modifier.width(24.dp))
                GlassIcon(Icons.Rounded.Forward10, "Forward 10 s", 56.dp) { vlc.seekBy(10_000); onInteract() }
                Spacer(Modifier.width(20.dp))
                GlassIcon(Icons.Rounded.SkipNext, "Next", 50.dp, enabled = idx < playlist.lastIndex) { host.next(); onInteract() }
            }
            Spacer(Modifier.weight(1f))
            // ---------- seek ----------
            SeekRow(vlc, state, accent, onInteract)
            Spacer(Modifier.height(6.dp))
            // ---------- action bar ----------
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.glass(RoundedCornerShape(18.dp)).padding(horizontal = 4.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    BottomAction(Icons.Rounded.LockOpen, "Lock") { onLock() }
                    BottomAction(Icons.Rounded.AspectRatio, state.scale.label) { onPanel(Panel.SCALE) }
                    BottomAction(Icons.Rounded.Speed, if (state.rate == 1f) "Speed" else "${trimRate(state.rate)}×", lit = state.rate != 1f) { onPanel(Panel.SPEED) }
                    BottomAction(Icons.Rounded.Rotate90DegreesCw, "Rotate") { onRotate() }
                    val om by host.orientationMode
                    BottomAction(Icons.Rounded.ScreenRotation, om.label) {
                        val next = OrientationMode.entries[(om.ordinal + 1) % OrientationMode.entries.size]
                        host.setOrientationMode(next); onToast(next.label); onInteract()
                    }
                }
                Spacer(Modifier.weight(1f))
                if (Build.VERSION.SDK_INT >= 26) Row(Modifier.glass(RoundedCornerShape(18.dp)).padding(horizontal = 4.dp, vertical = 2.dp)) {
                    BottomAction(Icons.Rounded.PictureInPictureAlt, "Mini") { host.enterPip() }
                }
            }
        }
    }
}

private fun trimRate(r: Float) = "%.2f".format(r).trimEnd('0').trimEnd('.')

@Composable
private fun PlayButton(state: VideoUiState, accent: Color, onClick: () -> Unit) {
    val src = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    val sc by animateFloatAsState(if (pressed) 0.9f else 1f, androidx.compose.animation.core.spring(dampingRatio = 0.5f), label = "pp")
    Box(
        Modifier.size(84.dp).graphicsLayer { scaleX = sc; scaleY = sc }
            .clip(CircleShape).background(Brush.radialGradient(listOf(accent, accent.copy(alpha = 0.82f))))
            .border(2.dp, Color.White.copy(0.25f), CircleShape)
            .clickable(interactionSource = src, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        androidx.compose.animation.AnimatedContent(
            when { state.ended -> 2; state.isPlaying -> 1; else -> 0 }, label = "pi",
            transitionSpec = { (fadeIn(tween(140)) + scaleIn(initialScale = 0.6f)) togetherWith (fadeOut(tween(100)) + scaleOut(targetScale = 0.6f)) },
        ) { k ->
            Icon(when (k) { 2 -> Icons.Rounded.Replay; 1 -> Icons.Rounded.Pause; else -> Icons.Rounded.PlayArrow }, "Play/Pause", tint = Palette.Ink, modifier = Modifier.size(44.dp))
        }
    }
}

@Composable
private fun SeekRow(vlc: VlcController, state: VideoUiState, accent: Color, onInteract: () -> Unit) {
    val time by vlc.time.collectAsStateWithLifecycle()
    var dragging by remember { mutableStateOf<Float?>(null) }
    var showRemaining by remember { mutableStateOf(false) }
    val len = state.length.coerceAtLeast(1)
    val shown = dragging?.toLong() ?: time
    val frac = (shown.toFloat() / len).coerceIn(0f, 1f)
    val active = dragging != null
    val trackH by animateFloatAsState(if (active) 7f else 4f, label = "th")
    val thumb by animateFloatAsState(if (active) 22f else 14f, label = "tb")
    val enabled = state.seekable && state.length > 0
    Column(Modifier.fillMaxWidth().padding(horizontal = 6.dp)) {
        if (active) Text(
            formatDuration(shown), color = Color.White, style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.align(Alignment.CenterHorizontally).glass(RoundedCornerShape(10.dp)).padding(horizontal = 12.dp, vertical = 4.dp),
        )
        Box(
            Modifier.fillMaxWidth().height(32.dp)
                .pointerInput(enabled, len) {
                    if (!enabled) return@pointerInput
                    detectTapGestures { o -> vlc.seekTo((o.x / size.width * len).toLong()); onInteract() }
                }
                .pointerInput(enabled, len) {
                    if (!enabled) return@pointerInput
                    detectHorizontalDragGestures(
                        onDragStart = { o -> dragging = (o.x / size.width).coerceIn(0f, 1f) * len },
                        onDragEnd = { dragging?.let { vlc.seekTo(it.toLong()) }; dragging = null },
                        onDragCancel = { dragging = null },
                    ) { ch, d -> ch.consume(); dragging = ((dragging ?: 0f) + d / size.width * len).coerceIn(0f, len.toFloat()); onInteract() }
                }
                .drawBehind {
                    val h = trackH.dp.toPx(); val y = size.height / 2
                    val r = androidx.compose.ui.geometry.CornerRadius(h / 2)
                    drawRoundRect(Color.White.copy(0.22f), Offset(0f, y - h / 2), androidx.compose.ui.geometry.Size(size.width, h), r)
                    drawRoundRect(Brush.horizontalGradient(listOf(accent.copy(alpha = 0.85f), accent)), Offset(0f, y - h / 2), androidx.compose.ui.geometry.Size(size.width * frac, h), r)
                    val tr = thumb.dp.toPx() / 2
                    drawCircle(accent.copy(alpha = 0.3f), tr * 1.6f, Offset(size.width * frac, y))
                    drawCircle(Color.White, tr, Offset(size.width * frac, y))
                },
        )
        Row(Modifier.fillMaxWidth()) {
            Text(formatDuration(shown), color = Color.White, style = MaterialTheme.typography.labelMedium)
            Spacer(Modifier.weight(1f))
            Text(
                if (showRemaining) "−" + formatDuration((state.length - shown).coerceAtLeast(0)) else formatDuration(state.length),
                color = Color.White.copy(0.75f), style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.clickable { showRemaining = !showRemaining },
            )
        }
    }
}

@Composable
private fun TopAction(icon: ImageVector, label: String, badge: Int?, lit: Boolean, onClick: () -> Unit) {
    val accent = LocalAccent.current
    Box {
        IconButton(onClick = onClick) { Icon(icon, label, tint = if (lit) accent.secondary else Color.White, modifier = Modifier.size(22.dp)) }
        if (badge != null) {
            Text(
                "$badge", color = Palette.Ink, fontSize = 9.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.align(Alignment.TopEnd).padding(top = 6.dp, end = 6.dp).clip(CircleShape).background(accent.secondary).padding(horizontal = 4.dp),
            )
        }
    }
}

@Composable
private fun GlassIcon(icon: ImageVector, label: String, size: androidx.compose.ui.unit.Dp, enabled: Boolean = true, onClick: () -> Unit) {
    val src = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    val sc by animateFloatAsState(if (pressed) 0.88f else 1f, androidx.compose.animation.core.spring(dampingRatio = 0.5f), label = "gi")
    Box(
        Modifier.size(size).graphicsLayer { scaleX = sc; scaleY = sc; alpha = if (enabled) 1f else 0.35f }
            .glass(CircleShape).clickable(interactionSource = src, indication = null, enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, label, tint = Color.White, modifier = Modifier.size(size * 0.5f)) }
}

@Composable
private fun BottomAction(icon: ImageVector, label: String, lit: Boolean = false, onClick: () -> Unit) {
    val c = if (lit) LocalAccent.current.secondary else Color.White
    Column(
        Modifier.clip(RoundedCornerShape(14.dp)).clickable(onClick = onClick).padding(horizontal = 10.dp, vertical = 6.dp).width(54.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, label, tint = c, modifier = Modifier.size(21.dp))
        Spacer(Modifier.height(2.dp))
        Text(label, color = c.copy(alpha = 0.8f), style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
fun PillButton(text: String, filled: Boolean = true, onClick: () -> Unit) {
    val accent = LocalAccent.current.primary
    Text(
        text, color = if (filled) Palette.Ink else Palette.Text, style = MaterialTheme.typography.titleSmall,
        modifier = Modifier.clip(CircleShape).background(if (filled) accent else Palette.SurfaceTop).clickable(onClick = onClick).padding(horizontal = 18.dp, vertical = 10.dp),
    )
}
