package com.rajdeep.listen.ui.radio

import androidx.lifecycle.repeatOnLifecycle

import androidx.lifecycle.compose.collectAsStateWithLifecycle

import android.content.Context
import android.media.AudioManager
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.FastForward
import androidx.compose.material.icons.rounded.FastRewind
import androidx.compose.material.icons.rounded.Radio
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarBorder
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rajdeep.listen.ListenApp
import com.rajdeep.listen.data.online.OnlineTrack
import com.rajdeep.listen.ui.AppViewModel
import com.rajdeep.listen.ui.LocalBottomInset
import com.rajdeep.listen.ui.Remote
import com.rajdeep.listen.ui.components.Artwork
import com.rajdeep.listen.ui.theme.Grotesk
import com.rajdeep.listen.ui.theme.Palette
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.roundToInt
import kotlin.math.sin

// ---------- palette for the cabinet ----------
private val WoodDark = Color(0xFF24150D)
private val WoodMid = Color(0xFF4A2C1A)
private val WoodLight = Color(0xFF6B4129)
private val BrassHi = Color(0xFFE9C987)
private val Brass = Color(0xFFC09A55)
private val BrassLo = Color(0xFF6E5228)
private val Cream = Color(0xFFF3E7C9)
private val Amber = Color(0xFFFFB547)
private val AmberGlow = Color(0xFFFF8A2A)
private val Needle = Color(0xFFE5322D)
private val Vfd = Color(0xFF0B0E0C)

private data class Band(val id: String, val label: String)
private val bands = listOf(
    Band("fav", "My stations"), Band("air", "All India Radio"), Band("india", "India"), Band("bollywood", "Bollywood"),
    Band("hindi", "Hindi"), Band("bengali", "Bengali"), Band("retro", "Retro"), Band("devotional", "Devotional"),
    Band("classical", "Classical"), Band("news", "News"), Band("world", "World"), Band("lofi", "Lo-fi"), Band("jazz", "Jazz"),
)

private fun freqOf(i: Int, n: Int): Float = if (n <= 0) 88f else ((88f + (i + 0.5f) * 20f / n) * 10).roundToInt() / 10f

@Composable
fun RadioScreen(vm: AppViewModel) {
    val ctx = LocalContext.current
    val radioPrefs = remember { (ctx.applicationContext as ListenApp).container.radio }
    val presets by radioPrefs.presets.collectAsStateWithLifecycle()
    val favs by radioPrefs.favourites.collectAsStateWithLifecycle()
    val bandsState by vm.bands.collectAsStateWithLifecycle()
    val player by vm.player.state.collectAsStateWithLifecycle()
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()

    var bandId by rememberSaveable { mutableStateOf(radioPrefs.lastBand.let { if (it == "fav" && radioPrefs.favourites.value.isEmpty()) "air" else it }) }
    LaunchedEffect(bandId) { radioPrefs.lastBand = bandId; if (bandId != "fav") vm.loadBand(bandId) }

    var query by rememberSaveable { mutableStateOf("") }
    var searchResults by remember { mutableStateOf<List<OnlineTrack>?>(null) }
    LaunchedEffect(query) {
        if (query.trim().length < 2) { searchResults = null; return@LaunchedEffect }
        delay(450); searchResults = vm.searchStations(query.trim())
    }

    val bandList: List<OnlineTrack> = when {
        searchResults != null -> searchResults!!
        bandId == "fav" -> favs
        else -> (bandsState[bandId] as? Remote.Ready)?.data.orEmpty()
    }
    val loading = searchResults == null && bandId != "fav" && bandsState[bandId] !is Remote.Ready
    val failed = (bandsState[bandId] as? Remote.Failed)?.message

    val current = player.current
    val onAir = current?.isLive == true
    val playingKey = if (onAir) current?.mediaId else null
    val isPlaying = onAir && player.isPlaying
    val buffering = onAir && player.isBuffering

    // tuned index follows the playing station when it is in this band
    var tuned by rememberSaveable { mutableIntStateOf(0) }
    LaunchedEffect(bandList, playingKey) {
        val i = bandList.indexOfFirst { it.key == (playingKey ?: radioPrefs.lastStationKey) }
        tuned = if (i >= 0) i else tuned.coerceIn(0, (bandList.size - 1).coerceAtLeast(0))
    }
    val station = bandList.getOrNull(tuned)

    // tuning → debounce → play (lets the knob sweep across stations without hammering streams)
    var tuneTick by remember { mutableIntStateOf(0) }
    LaunchedEffect(tuneTick) {
        if (tuneTick == 0) return@LaunchedEffect
        delay(420)
        bandList.getOrNull(tuned)?.let { vm.player.playOnline(listOf(it), 0); radioPrefs.lastStationKey = it.key }
    }
    fun tuneTo(i: Int, immediate: Boolean = false) {
        if (bandList.isEmpty()) return
        tuned = ((i % bandList.size) + bandList.size) % bandList.size
        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        if (immediate) { bandList[tuned].let { vm.player.playOnline(listOf(it), 0); radioPrefs.lastStationKey = it.key } } else tuneTick++
    }
    fun power() {
        when {
            isPlaying -> vm.player.pause()
            onAir && current?.mediaId == station?.key -> vm.player.togglePlay()
            station != null -> tuneTo(tuned, immediate = true)
        }
    }

    // scan: hop to the next station every 8 s until stopped
    var scanning by remember { mutableStateOf(false) }
    LaunchedEffect(scanning, bandList.size) {
        while (scanning && bandList.isNotEmpty()) { tuneTo(tuned + 1, immediate = true); delay(8000) }
    }

    val listState = rememberLazyListState()
    LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = PaddingValues(bottom = LocalBottomInset.current)) {
        item(key = "title") {
            Row(Modifier.padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.Bottom) {
                Text("Radio", style = MaterialTheme.typography.displaySmall, color = Palette.Text, modifier = Modifier.weight(1f))
                Text("${bandList.size} stations", style = MaterialTheme.typography.bodyMedium, color = Palette.TextDim)
            }
        }
        item(key = "cabinet") {
            Cabinet(
                station = station, index = tuned, count = bandList.size, isPlaying = isPlaying, buffering = buffering,
                bandLabel = if (searchResults != null) "Search" else bands.first { it.id == bandId }.label,
                presets = presets, scanning = scanning, sleepAt = player.sleepAt,
                onStep = { d -> scanning = false; tuneTo(tuned + d) },
                onSelect = { i -> scanning = false; if (i != tuned) tuneTo(i) },
                onPower = ::power,
                onSeek = { d -> scanning = false; tuneTo(tuned + d, immediate = true) },
                onScan = { scanning = !scanning },
                onSleep = {
                    val next = when { player.sleepAt == 0L -> 15; (player.sleepAt - System.currentTimeMillis()) < 20 * 60_000 -> 30; (player.sleepAt - System.currentTimeMillis()) < 40 * 60_000 -> 60; else -> 0 }
                    vm.player.sleepIn(next)
                },
                onPreset = { slot -> presets[slot]?.let { p -> scanning = false; vm.player.playOnline(listOf(p), 0); radioPrefs.lastStationKey = p.key; bandList.indexOfFirst { it.key == p.key }.takeIf { it >= 0 }?.let { tuned = it } } },
                onPresetSave = { slot -> station?.let { radioPrefs.setPreset(slot, it); haptic.performHapticFeedback(HapticFeedbackType.LongPress) } },
                bandRow = {
                    LazyRow(contentPadding = PaddingValues(horizontal = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(bands.filter { it.id != "fav" || favs.isNotEmpty() }, key = { it.id }) { b ->
                            BandKey(b.label, b.id == bandId && searchResults == null) { query = ""; scanning = false; bandId = b.id; tuned = 0 }
                        }
                    }
                },
            )
        }
        item(key = "search") {
            TextField(
                value = query, onValueChange = { query = it }, singleLine = true,
                placeholder = { Text("Find any station worldwide", color = Palette.TextFaint) },
                leadingIcon = { Icon(Icons.Rounded.Search, null, tint = Palette.TextDim) },
                trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Rounded.Close, "Clear", tint = Palette.TextDim) } },
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Palette.SurfaceHigh, unfocusedContainerColor = Palette.SurfaceHigh,
                    focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent, cursorColor = Amber,
                    focusedTextColor = Palette.Text, unfocusedTextColor = Palette.Text,
                ),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp).clip(CircleShape),
            )
        }
        when {
            loading -> item(key = "loading") { Text(failed ?: "Tuning in…", color = Palette.TextDim, modifier = Modifier.padding(20.dp)) }
            bandList.isEmpty() -> item(key = "empty") {
                Text(
                    if (bandId == "fav") "Star a station to keep it here." else failed ?: "No stations.",
                    color = Palette.TextDim, modifier = Modifier.padding(20.dp).clickable { vm.loadBand(bandId, force = true) },
                )
            }
            else -> items(bandList.size, key = { "st" + bandList[it].key }) { i ->
                val st = bandList[i]
                StationRow(
                    st, freqOf(i, bandList.size), active = st.key == playingKey, tuned = i == tuned,
                    fav = favs.any { it.key == st.key },
                    onClick = { scanning = false; tuned = i; vm.player.playOnline(listOf(st), 0); radioPrefs.lastStationKey = st.key; scope.launch { listState.animateScrollToItem(1) } },
                    onFav = { radioPrefs.toggleFavourite(st) },
                )
            }
        }
    }
}

// ---------------------------------------------------------------- cabinet

@Composable
private fun Cabinet(
    station: OnlineTrack?, index: Int, count: Int, isPlaying: Boolean, buffering: Boolean, bandLabel: String,
    presets: List<OnlineTrack?>, scanning: Boolean, sleepAt: Long,
    onStep: (Int) -> Unit, onSelect: (Int) -> Unit, onPower: () -> Unit, onSeek: (Int) -> Unit, onScan: () -> Unit, onSleep: () -> Unit,
    onPreset: (Int) -> Unit, onPresetSave: (Int) -> Unit, bandRow: @Composable () -> Unit,
) {
    val grain = remember { List(26) { Triple((it * 37 % 100) / 100f, 0.6f + (it * 13 % 40) / 100f, (it * 53 % 7).toFloat()) } }
    Box(
        Modifier.padding(horizontal = 14.dp).fillMaxWidth()
            .shadow(24.dp, RoundedCornerShape(30.dp), ambientColor = Color.Black, spotColor = Color.Black)
            .clip(RoundedCornerShape(30.dp))
            .drawBehind {
                drawRect(Brush.verticalGradient(listOf(WoodLight, WoodMid, WoodDark)))
                // wood grain
                grain.forEach { (y, amp, ph) ->
                    val yy = size.height * y
                    val path = androidx.compose.ui.graphics.Path().apply {
                        moveTo(0f, yy)
                        var x = 0f
                        while (x <= size.width) { lineTo(x, yy + sin(x / 70f + ph) * 6f * amp); x += 12f }
                    }
                    drawPath(path, Color.Black.copy(alpha = 0.10f * amp), style = Stroke(1.2f))
                }
                drawRect(Brush.verticalGradient(listOf(Color.White.copy(0.10f), Color.Transparent, Color.Black.copy(0.25f))))
            }
            .border(2.dp, Brush.verticalGradient(listOf(BrassHi, Brass, BrassLo)), RoundedCornerShape(30.dp))
            .padding(16.dp),
    ) {
        Column {
            // --- grille + badge + lamp ---
            Box(Modifier.fillMaxWidth().height(96.dp)) {
                SpeakerGrille(isPlaying, Modifier.fillMaxSize().clickable(remember { androidx.compose.foundation.interaction.MutableInteractionSource() }, null) { onPower() })
                Column(Modifier.align(Alignment.Center).clip(RoundedCornerShape(10.dp)).background(Brush.verticalGradient(listOf(BrassHi, Brass, BrassLo))).padding(horizontal = 14.dp, vertical = 5.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("LISTEN", style = TextStyle(fontFamily = Grotesk, fontWeight = FontWeight.Bold, fontSize = 15.sp, letterSpacing = 4.sp, color = WoodDark))
                    Text("MODEL 64 · FM", style = TextStyle(fontFamily = Grotesk, fontSize = 8.sp, letterSpacing = 2.sp, color = WoodDark.copy(0.8f)))
                }
                Lamp(isPlaying, buffering, Modifier.align(Alignment.TopEnd).padding(8.dp))
            }
            Spacer(Modifier.height(12.dp))
            VfdDisplay(station, freqOf(index, count), bandLabel, isPlaying, buffering, scanning, sleepAt, onPower = onPower, onSwipe = onStep)
            Spacer(Modifier.height(10.dp))
            Dial(index, count, isPlaying, onSelect)
            Spacer(Modifier.height(14.dp))
            // --- controls ---
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    VolumeKnob(78.dp)
                    Text("VOLUME", style = engraved)
                }
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        BrassButton(Modifier.size(38.dp), onClick = { onSeek(-1) }) { Icon(Icons.Rounded.FastRewind, "Previous station", tint = WoodDark, modifier = Modifier.size(20.dp)) }
                        PowerSwitch(isPlaying, onPower)
                        BrassButton(Modifier.size(38.dp), onClick = { onSeek(1) }) { Icon(Icons.Rounded.FastForward, "Next station", tint = WoodDark, modifier = Modifier.size(20.dp)) }
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SmallToggle("SCAN", scanning, onScan)
                        SmallToggle(if (sleepAt > 0) "SLEEP ${((sleepAt - System.currentTimeMillis()) / 60_000 + 1).coerceAtLeast(0)}m" else "SLEEP", sleepAt > 0, onSleep, icon = Icons.Rounded.Bedtime)
                    }
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    TuningKnob(78.dp, enabled = count > 0, onStep = onStep)
                    Text("TUNING", style = engraved)
                }
            }
            Spacer(Modifier.height(14.dp))
            // --- presets ---
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                presets.forEachIndexed { i, p -> PresetButton(i + 1, p, onClick = { onPreset(i) }, onLong = { onPresetSave(i) }) }
            }
            Text("Drag the dial or turn the knob · tap the display to play · hold a preset to save", style = engraved.copy(fontSize = 8.sp, letterSpacing = 1.sp), modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 6.dp))
            Spacer(Modifier.height(12.dp))
            bandRow()
        }
    }
}

private val engraved = TextStyle(fontFamily = Grotesk, fontWeight = FontWeight.Bold, fontSize = 9.sp, letterSpacing = 2.sp, color = BrassHi.copy(alpha = 0.85f))

@Composable
private fun SpeakerGrille(active: Boolean, modifier: Modifier) {
    val pulse = remember { Animatable(0f) }
    LaunchedEffect(active) {
        if (active) while (true) { pulse.animateTo(1f, tween(520, easing = LinearEasing)); pulse.animateTo(0f, tween(520, easing = LinearEasing)) }
        else pulse.animateTo(0f, tween(300))
    }
    Canvas(modifier.clip(RoundedCornerShape(18.dp))) {
        drawRect(Color(0xFF1A110B))
        val step = 9.dp.toPx(); val r = 2.2.dp.toPx()
        val c = Offset(size.width / 2, size.height / 2)
        var y = step / 2
        var row = 0
        while (y < size.height) {
            var x = step / 2 + if (row % 2 == 0) 0f else step / 2
            while (x < size.width) {
                val d = (Offset(x, y) - c).getDistance() / size.width
                val beat = if (active) pulse.value * 0.12f * (1f - d) else 0f
                drawCircle(Color(0xFF3A2A1E).copy(alpha = 0.95f), r * (1f + beat), Offset(x, y))
                drawCircle(Color.Black.copy(0.5f), r * 0.55f, Offset(x + 0.6f, y + 0.6f))
                x += step
            }
            y += step * 0.87f; row++
        }
        drawRect(Brush.radialGradient(listOf(Color.Transparent, Color.Black.copy(0.55f)), c, size.width * 0.7f))
    }
}

@Composable
private fun Lamp(on: Boolean, buffering: Boolean, modifier: Modifier) {
    val flicker = remember { Animatable(1f) }
    LaunchedEffect(buffering) {
        if (buffering) while (true) { flicker.animateTo(0.35f, tween(160)); flicker.animateTo(1f, tween(160)) } else flicker.snapTo(1f)
    }
    Canvas(modifier.size(18.dp)) {
        val a = when { buffering -> flicker.value; on -> 1f; else -> 0f }
        drawCircle(Brush.radialGradient(listOf(AmberGlow.copy(alpha = 0.7f * a), Color.Transparent)), size.minDimension)
        drawCircle(lerpColor(Color(0xFF3B2412), Amber, a), size.minDimension / 3.2f)
        drawCircle(Color.White.copy(0.35f * a), size.minDimension / 9f, center + Offset(-2f, -2f))
    }
}

private fun lerpColor(a: Color, b: Color, t: Float) = androidx.compose.ui.graphics.lerp(a, b, t.coerceIn(0f, 1f))

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun VfdDisplay(st: OnlineTrack?, freq: Float, band: String, on: Boolean, buffering: Boolean, scanning: Boolean, sleepAt: Long, onPower: () -> Unit, onSwipe: (Int) -> Unit) {
    val power by androidx.compose.runtime.rememberUpdatedState(onPower)
    val swipe by androidx.compose.runtime.rememberUpdatedState(onSwipe)
    val glow = if (on || buffering) Amber else Amber.copy(alpha = 0.35f)
    val mono = FontFamily.Monospace
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Vfd)
            .pointerInput(Unit) { detectTapGestures { power() } }
            .pointerInput(Unit) {
                var acc = 0f
                detectHorizontalDragGestures(onDragEnd = { acc = 0f }) { ch, d ->
                    ch.consume(); acc += d
                    if (acc > 90f) { swipe(-1); acc = 0f } else if (acc < -90f) { swipe(1); acc = 0f }
                }
            }
            .border(1.dp, Brush.verticalGradient(listOf(Color.Black, Color(0xFF2A2A2A))), RoundedCornerShape(14.dp))
            .drawBehind { drawRect(Brush.verticalGradient(listOf(Color.White.copy(0.05f), Color.Transparent))) }
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text("%.1f".format(freq), style = TextStyle(fontFamily = mono, fontWeight = FontWeight.Bold, fontSize = 30.sp, color = glow, shadow = androidx.compose.ui.graphics.Shadow(AmberGlow.copy(alpha = if (on) 0.8f else 0f), blurRadius = 14f)))
            Text(" MHz", style = TextStyle(fontFamily = mono, fontSize = 12.sp, color = glow.copy(0.8f)), modifier = Modifier.padding(bottom = 5.dp))
            Spacer(Modifier.weight(1f))
            Column(horizontalAlignment = Alignment.End) {
                Indicator("STEREO", on)
                Indicator(when { scanning -> "SCAN"; buffering -> "TUNING"; on -> "ON AIR"; else -> "STANDBY" }, on || buffering || scanning)
                if (sleepAt > 0) Indicator("SLEEP", true)
            }
        }
        Text(
            (st?.title ?: "NO SIGNAL").uppercase(),
            style = TextStyle(fontFamily = mono, fontSize = 15.sp, color = glow, letterSpacing = 1.sp), maxLines = 1,
            modifier = Modifier.fillMaxWidth().basicMarquee(iterations = Int.MAX_VALUE),
        )
        Text(
            listOfNotNull(band.uppercase(), st?.artist?.uppercase()?.takeIf { it.isNotBlank() }, st?.extra?.uppercase()?.takeIf { it.isNotBlank() }).joinToString("  ·  "),
            style = TextStyle(fontFamily = mono, fontSize = 10.sp, color = glow.copy(alpha = 0.7f)), maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun Indicator(text: String, lit: Boolean) {
    Text(text, style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 9.sp, letterSpacing = 1.sp, color = if (lit) Amber else Amber.copy(alpha = 0.18f)))
}

@Composable
private fun Dial(index: Int, count: Int, on: Boolean, onSelect: (Int) -> Unit) {
    val select by androidx.compose.runtime.rememberUpdatedState(onSelect)
    val n by androidx.compose.runtime.rememberUpdatedState(count)
    var dragPos by remember { mutableStateOf<Float?>(null) }
    val haptic = LocalHapticFeedback.current
    val target = dragPos ?: if (count <= 0) 0.5f else (index + 0.5f) / count
    val pos by animateFloatAsState(target, if (dragPos != null) spring(stiffness = 2000f) else spring(dampingRatio = 0.62f, stiffness = 140f), label = "needle")
    fun idxAt(f: Float) = (f * n).toInt().coerceIn(0, (n - 1).coerceAtLeast(0))
    val lit by animateFloatAsState(if (on) 1f else 0.45f, tween(500), label = "lit")
    Canvas(
        Modifier.fillMaxWidth().height(78.dp).clip(RoundedCornerShape(12.dp))
            .border(1.5.dp, Brush.verticalGradient(listOf(BrassHi, BrassLo)), RoundedCornerShape(12.dp))
            .pointerInput(Unit) {
                val pad = 14.dp.toPx()
                fun frac(x: Float) = ((x - pad) / (size.width - 2 * pad)).coerceIn(0f, 1f)
                detectTapGestures { o -> if (n > 0) select(idxAt(frac(o.x))) }
            }
            .pointerInput(Unit) {
                val pad = 14.dp.toPx()
                fun frac(x: Float) = ((x - pad) / (size.width - 2 * pad)).coerceIn(0f, 1f)
                var last = -1
                detectHorizontalDragGestures(
                    onDragStart = { o -> dragPos = frac(o.x); last = idxAt(frac(o.x)) },
                    onDragEnd = { dragPos?.let { if (n > 0) select(idxAt(it)) }; dragPos = null },
                    onDragCancel = { dragPos = null },
                ) { ch, _ ->
                    ch.consume()
                    val f = frac(ch.position.x); dragPos = f
                    val i = idxAt(f); if (i != last) { last = i; haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove) }
                }
            },
    ) {
        // backlit glass
        drawRect(Brush.verticalGradient(listOf(lerpColor(Color(0xFF6D5A3A), Cream, lit), lerpColor(Color(0xFF4A3C25), Color(0xFFE2CFA0), lit))))
        drawRect(Brush.radialGradient(listOf(Amber.copy(alpha = 0.35f * lit), Color.Transparent), Offset(size.width * pos, size.height / 2), size.width * 0.35f))
        val pad = 14.dp.toPx(); val w = size.width - pad * 2
        val ink = Color(0xFF2B1D10)
        // ticks 88..108
        for (i in 0..100) {
            val x = pad + w * i / 100f
            val major = i % 10 == 0; val mid = i % 5 == 0
            val h = when { major -> 18.dp.toPx(); mid -> 12.dp.toPx(); else -> 7.dp.toPx() }
            drawLine(ink.copy(alpha = if (major) 0.9f else 0.55f), Offset(x, size.height - 6.dp.toPx() - h), Offset(x, size.height - 6.dp.toPx()), strokeWidth = if (major) 2f else 1.2f)
        }
        // labels
        val paint = android.graphics.Paint().apply {
            isAntiAlias = true; color = android.graphics.Color.argb(220, 43, 29, 16); textSize = 10.dp.toPx(); textAlign = android.graphics.Paint.Align.CENTER
            typeface = android.graphics.Typeface.create(android.graphics.Typeface.MONOSPACE, android.graphics.Typeface.BOLD)
        }
        for (mhz in 88..108 step 4) {
            val x = pad + w * (mhz - 88) / 20f
            drawContext.canvas.nativeCanvas.drawText("$mhz", x, 16.dp.toPx(), paint)
        }
        // station marks
        if (count in 1..400) for (i in 0 until count) {
            val x = pad + w * (i + 0.5f) / count
            drawCircle(Needle.copy(alpha = 0.35f), 1.6.dp.toPx(), Offset(x, 26.dp.toPx()))
        }
        // needle
        val nx = pad + w * pos
        drawLine(Color.Black.copy(0.25f), Offset(nx + 2f, 4f), Offset(nx + 2f, size.height - 2f), strokeWidth = 3.dp.toPx(), cap = StrokeCap.Round)
        drawLine(Needle, Offset(nx, 4f), Offset(nx, size.height - 4f), strokeWidth = 2.5.dp.toPx(), cap = StrokeCap.Round)
        // glass reflection
        drawRect(Brush.verticalGradient(listOf(Color.White.copy(0.28f), Color.Transparent), endY = size.height * 0.45f))
    }
}

// ---------- knobs ----------

@Composable
private fun KnobFace(size: Dp, angle: Float, modifier: Modifier = Modifier) {
    Canvas(modifier.size(size)) {
        val r = this.size.minDimension / 2
        drawCircle(Color.Black.copy(0.5f), r, center + Offset(3f, 5f))
        drawCircle(Brush.linearGradient(listOf(BrassHi, BrassLo), Offset(0f, 0f), Offset(this.size.width, this.size.height)), r)
        // knurled edge
        rotate(angle) {
            for (i in 0 until 48) rotate(i * 7.5f) { drawLine(BrassLo.copy(0.9f), Offset(center.x, center.y - r), Offset(center.x, center.y - r * 0.86f), 2f) }
        }
        drawCircle(Brush.radialGradient(listOf(Color(0xFF3A2516), Color(0xFF1A0F08)), center, r * 0.78f), r * 0.78f)
        drawCircle(Brush.linearGradient(listOf(Color.White.copy(0.18f), Color.Transparent), Offset(0f, 0f), center), r * 0.78f)
        rotate(angle) {
            drawLine(BrassHi, Offset(center.x, center.y - r * 0.68f), Offset(center.x, center.y - r * 0.3f), 4f, cap = StrokeCap.Round)
        }
        drawCircle(BrassLo, r * 0.12f)
    }
}

private fun angleOf(p: Offset, c: Offset) = (atan2(p.y - c.y, p.x - c.x) * 180f / PI.toFloat())

@Composable
private fun TuningKnob(size: Dp, enabled: Boolean, onStep: (Int) -> Unit) {
    val step by androidx.compose.runtime.rememberUpdatedState(onStep)
    var angle by remember { mutableFloatStateOf(0f) }
    val spin = remember { Animatable(0f) }
    KnobFace(
        size, angle + spin.value,
        Modifier.pointerInput(enabled) { if (enabled) detectTapGestures { step(1) } }.pointerInput(enabled) {
            if (!enabled) return@pointerInput
            var acc = 0f
            detectDragGestures(onDragEnd = { acc = 0f }) { ch, drag ->
                ch.consume()
                val c = Offset(this.size.width / 2f, this.size.height / 2f)
                var d = angleOf(ch.position, c) - angleOf(ch.position - drag, c)
                if (d > 180) d -= 360; if (d < -180) d += 360
                angle += d; acc += d
                while (acc > 24f) { step(1); acc -= 24f }
                while (acc < -24f) { step(-1); acc += 24f }
            }
        },
    )
}

@Composable
private fun VolumeKnob(size: Dp) {
    val ctx = LocalContext.current
    val am = remember { ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    val max = remember { am.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1) }
    var level by remember { mutableFloatStateOf(am.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / max) }
    val lo = androidx.lifecycle.compose.LocalLifecycleOwner.current
    LaunchedEffect(lo) { lo.lifecycle.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) { while (true) { delay(1200); level = am.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / max } } }
    val haptic = LocalHapticFeedback.current
    Box(contentAlignment = Alignment.Center) {
        // level arc
        Canvas(Modifier.size(size + 12.dp)) {
            drawArc(Color.Black.copy(0.35f), 135f, 270f, false, style = Stroke(3.dp.toPx(), cap = StrokeCap.Round))
            drawArc(Amber, 135f, 270f * level, false, style = Stroke(3.dp.toPx(), cap = StrokeCap.Round))
        }
        KnobFace(
            size, -135f + 270f * level,
            Modifier.pointerInput(Unit) {
                detectDragGestures { ch, drag ->
                    ch.consume()
                    val c = Offset(this.size.width / 2f, this.size.height / 2f)
                    var d = angleOf(ch.position, c) - angleOf(ch.position - drag, c)
                    if (d > 180) d -= 360; if (d < -180) d += 360
                    val before = (level * max).roundToInt()
                    level = (level + d / 270f).coerceIn(0f, 1f)
                    val after = (level * max).roundToInt()
                    if (after != before) { am.setStreamVolume(AudioManager.STREAM_MUSIC, after, 0); haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove) }
                }
            },
        )
    }
}

// ---------- buttons ----------

@Composable
private fun BrassButton(modifier: Modifier, onClick: () -> Unit, content: @Composable () -> Unit) {
    val src = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    val sc by animateFloatAsState(if (pressed) 0.88f else 1f, spring(dampingRatio = 0.5f), label = "bb")
    val haptic = LocalHapticFeedback.current
    Box(
        modifier.graphicsLayer { scaleX = sc; scaleY = sc }.shadow(if (pressed) 1.dp else 4.dp, CircleShape).clip(CircleShape)
            .background(Brush.linearGradient(if (pressed) listOf(BrassLo, Brass, BrassHi) else listOf(BrassHi, Brass, BrassLo)))
            .clickable(interactionSource = src, indication = null) { haptic.performHapticFeedback(HapticFeedbackType.LongPress); onClick() },
        contentAlignment = Alignment.Center,
    ) { content() }
}

@Composable
private fun PowerSwitch(on: Boolean, onClick0: () -> Unit) {
    val haptic = LocalHapticFeedback.current
    val onClick = { haptic.performHapticFeedback(HapticFeedbackType.LongPress); onClick0() }
    val t by animateFloatAsState(if (on) 1f else 0f, spring(dampingRatio = 0.6f), label = "sw")
    Box(
        Modifier.width(62.dp).height(34.dp).clip(RoundedCornerShape(17.dp))
            .background(Color(0xFF140C07)).border(1.5.dp, Brush.verticalGradient(listOf(BrassHi, BrassLo)), RoundedCornerShape(17.dp))
            .clickable(onClick = onClick)
            .drawBehind {
                drawRoundRect(Amber.copy(alpha = 0.25f * t), cornerRadius = CornerRadius(size.height / 2))
                val r = size.height / 2 - 4.dp.toPx()
                val x = 4.dp.toPx() + r + (size.width - 8.dp.toPx() - 2 * r) * t
                drawCircle(Brush.radialGradient(listOf(BrassHi, BrassLo), Offset(x - 3f, size.height / 2 - 3f), r * 1.4f), r, Offset(x, size.height / 2))
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(if (on) "ON  " else "  OFF", style = engraved.copy(fontSize = 8.sp, color = if (on) Amber else BrassHi.copy(0.5f)))
    }
}

@Composable
private fun SmallToggle(label: String, on: Boolean, onClick: () -> Unit, icon: androidx.compose.ui.graphics.vector.ImageVector? = null) {
    Row(
        Modifier.clip(RoundedCornerShape(8.dp)).background(if (on) Amber.copy(0.22f) else Color.Black.copy(0.35f))
            .border(1.dp, if (on) Amber else BrassLo, RoundedCornerShape(8.dp)).clickable(onClick = onClick).padding(horizontal = 9.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) { Icon(icon, null, tint = if (on) Amber else BrassHi.copy(0.7f), modifier = Modifier.size(11.dp)); Spacer(Modifier.width(4.dp)) }
        Text(label, style = engraved.copy(fontSize = 8.sp, color = if (on) Amber else BrassHi.copy(0.7f)))
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PresetButton(n: Int, st: OnlineTrack?, onClick: () -> Unit, onLong: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(48.dp)) {
        Box(
            Modifier.size(40.dp).shadow(5.dp, CircleShape).clip(CircleShape)
                .background(Brush.linearGradient(listOf(if (st != null) BrassHi else Color(0xFF8C7A55), BrassLo)))
                .combinedClickable(onClick = onClick, onLongClick = onLong),
            contentAlignment = Alignment.Center,
        ) { Text("$n", style = TextStyle(fontFamily = Grotesk, fontWeight = FontWeight.Bold, fontSize = 15.sp, color = WoodDark)) }
        Text(
            st?.title ?: "—", style = engraved.copy(fontSize = 7.sp, letterSpacing = 0.5.sp), maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

@Composable
private fun BandKey(label: String, selected: Boolean, onClick: () -> Unit) {
    val press by animateFloatAsState(if (selected) 1f else 0f, tween(160), label = "key")
    Box(
        Modifier.height(38.dp).clip(RoundedCornerShape(bottomStart = 8.dp, bottomEnd = 8.dp, topStart = 3.dp, topEnd = 3.dp))
            .background(Brush.verticalGradient(listOf(lerpColor(Cream, Color(0xFFD8C79F), press), lerpColor(Color(0xFFD9C9A2), Color(0xFFBFA978), press))))
            .drawBehind {
                if (press > 0f) drawRect(Amber.copy(alpha = 0.25f * press))
                drawLine(Color.Black.copy(0.25f), Offset(0f, size.height - 1f), Offset(size.width, size.height - 1f), 2f)
            }
            .clickable(onClick = onClick).padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label.uppercase(), style = TextStyle(fontFamily = Grotesk, fontWeight = FontWeight.Bold, fontSize = 10.sp, letterSpacing = 1.sp, color = if (selected) Color(0xFF8A2A10) else WoodDark))
    }
}

// ---------- list ----------

@Composable
private fun StationRow(st: OnlineTrack, freq: Float, active: Boolean, tuned: Boolean, fav: Boolean, onClick: () -> Unit, onFav: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(if (active) Amber.copy(alpha = 0.10f) else Color.Transparent)
            .clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("%.1f".format(freq), style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = if (tuned || active) Amber else Palette.TextFaint), modifier = Modifier.width(44.dp))
        Box(Modifier.size(48.dp).clip(RoundedCornerShape(12.dp)).background(Color.White)) {
            Artwork(st.artwork, st.key, Modifier.fillMaxSize(), RoundedCornerShape(12.dp), icon = Icons.Rounded.Radio)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(st.title, style = MaterialTheme.typography.titleSmall, color = if (active) Amber else Palette.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(listOf(st.artist, st.extra).filter { it.isNotBlank() }.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = Palette.TextDim, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        IconButton(onClick = onFav) { Icon(if (fav) Icons.Rounded.Star else Icons.Rounded.StarBorder, "Favourite", tint = if (fav) Amber else Palette.TextFaint) }
    }
}

