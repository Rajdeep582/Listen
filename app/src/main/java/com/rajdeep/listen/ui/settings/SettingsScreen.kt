package com.rajdeep.listen.ui.settings

import androidx.lifecycle.compose.collectAsStateWithLifecycle

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings as SysSettings
import androidx.compose.foundation.background
import androidx.compose.material.icons.rounded.Check
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import kotlinx.coroutines.launch
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.rajdeep.listen.BuildConfig
import com.rajdeep.listen.ui.AppViewModel
import com.rajdeep.listen.ui.LocalBottomInset
import com.rajdeep.listen.ui.MainActivity
import com.rajdeep.listen.ui.theme.LocalAccent
import com.rajdeep.listen.ui.theme.Palette
import com.rajdeep.listen.video.PillButton
import com.rajdeep.listen.video.ToggleRow
import kotlin.math.roundToInt

@Composable
private fun Group(title: String, content: @Composable () -> Unit) {
    Text(title.uppercase(), style = MaterialTheme.typography.labelSmall, color = Palette.TextFaint, modifier = Modifier.padding(start = 24.dp, top = 22.dp, bottom = 8.dp))
    Column(Modifier.padding(horizontal = 16.dp).fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Palette.Surface).padding(horizontal = 16.dp, vertical = 8.dp)) { content() }
}

@Composable
private fun SliderRow(label: String, value: Int, range: IntRange, suffix: String, onChange: (Int) -> Unit) {
    var local by remember(value) { mutableFloatStateOf(value.toFloat()) }
    val accent = LocalAccent.current.primary
    Column(Modifier.padding(vertical = 6.dp)) {
        Row {
            Text(label, style = MaterialTheme.typography.titleSmall, color = Palette.Text, modifier = Modifier.weight(1f))
            Text("${local.roundToInt()}$suffix", style = MaterialTheme.typography.titleSmall, color = accent)
        }
        Slider(
            local, { local = it }, valueRange = range.first.toFloat()..range.last.toFloat(), onValueChangeFinished = { onChange(local.roundToInt()) },
            colors = SliderDefaults.colors(thumbColor = accent, activeTrackColor = accent, inactiveTrackColor = Palette.SurfaceTop),
        )
    }
}

@Composable
private fun TextRow(label: String, hint: String, value: String, onChange: (String) -> Unit) {
    var text by remember(value) { mutableStateOf(value) }
    Column(Modifier.padding(vertical = 6.dp)) {
        Text(label, style = MaterialTheme.typography.titleSmall, color = Palette.Text)
        OutlinedTextField(
            text, { text = it.take(12); onChange(text.trim().lowercase()) }, singleLine = true, placeholder = { Text(hint, color = Palette.TextFaint) },
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
            colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = LocalAccent.current.primary, focusedTextColor = Palette.Text, unfocusedTextColor = Palette.Text, unfocusedBorderColor = Palette.Line),
        )
    }
}

@Composable
private fun ActionRow(label: String, sub: String, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 12.dp)) {
        Text(label, style = MaterialTheme.typography.titleSmall, color = Palette.Text)
        Text(sub, style = MaterialTheme.typography.bodySmall, color = Palette.TextDim)
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@SuppressLint("BatteryLife")
@Composable
fun SettingsScreen(vm: AppViewModel, activity: MainActivity) {
    val s by vm.settings.collectAsStateWithLifecycle()
    val scanning by vm.scanning.collectAsStateWithLifecycle()
    val songs by vm.songs.collectAsStateWithLifecycle()
    val videos by vm.videos.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    val pm = remember { ctx.getSystemService(PowerManager::class.java) }
    var unrestricted by remember { mutableStateOf(pm?.isIgnoringBatteryOptimizations(ctx.packageName) == true) }
    androidx.lifecycle.compose.LifecycleResumeEffect(Unit) {
        unrestricted = pm?.isIgnoringBatteryOptimizations(ctx.packageName) == true
        onPauseOrDispose { }
    }
    var showFolders by remember { mutableStateOf(false) }
    if (showFolders) HiddenFoldersDialog(vm, s.hiddenFolders) { showFolders = false }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = LocalBottomInset.current)) {
        Text("Settings", style = MaterialTheme.typography.displaySmall, color = Palette.Text, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))

        Group("Library") {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 8.dp)) {
                Column(Modifier.weight(1f)) {
                    Text("${songs?.size ?: 0} songs · ${videos?.size ?: 0} videos", style = MaterialTheme.typography.titleSmall, color = Palette.Text)
                    Text(if (scanning) "Scanning…" else "Updates automatically when files change", style = MaterialTheme.typography.bodySmall, color = Palette.TextDim)
                }
                PillButton("Rescan") { vm.rescan() }
            }
            SliderRow("Ignore audio shorter than", s.minAudioSec, 0..120, " s") { v -> vm.updateSettings { it.copy(minAudioSec = v) }.invokeOnCompletion { vm.rescan() } }
            if (android.os.Build.VERSION.SDK_INT >= 31) {
                var canManage by remember { mutableStateOf(android.provider.MediaStore.canManageMedia(ctx)) }
                androidx.lifecycle.compose.LifecycleResumeEffect(Unit) { canManage = android.provider.MediaStore.canManageMedia(ctx); onPauseOrDispose { } }
                ActionRow(
                    if (canManage) "Media management: allowed ✓" else "Allow media management",
                    "Lets Listen delete or edit your videos and songs without asking every time",
                ) { if (!canManage) runCatching { ctx.startActivity(Intent(SysSettings.ACTION_REQUEST_MANAGE_MEDIA, Uri.parse("package:${ctx.packageName}"))) } }
            }
            ActionRow("Hidden folders", if (s.hiddenFolders.isEmpty()) "Hide WhatsApp audio, call recordings, ringtones…" else "${s.hiddenFolders.size} hidden — tap to change") { showFolders = true }
            ActionRow("Clear listening history", "Resets Recently played and play counts' recency") { vm.library.clearHistory() }
        }

        Group("Music playback") {
            ToggleRow("Online sources (JioSaavn, FreeToUse, Incompetech, Audius, Jamendo, radio, lyrics)", s.onlineEnabled) { v -> vm.updateSettings { it.copy(onlineEnabled = v) } }
            ToggleRow("Skip silence between and inside tracks", s.skipSilence) { v -> vm.updateSettings { it.copy(skipSilence = v) } }
            ToggleRow("Equalizer", s.eqEnabled) { v -> vm.updateSettings { it.copy(eqEnabled = v) } }
            androidx.compose.animation.AnimatedVisibility(s.eqEnabled) {
                Column {
                    val presets by com.rajdeep.listen.playback.AudioFx.presets.collectAsStateWithLifecycle()
                    if (presets.isEmpty()) Text("Play any song once to load your phone's equalizer presets.", style = MaterialTheme.typography.bodySmall, color = Palette.TextDim, modifier = Modifier.padding(vertical = 6.dp))
                    else androidx.compose.foundation.layout.FlowRow(
                        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
                        verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
                        modifier = Modifier.padding(vertical = 8.dp),
                    ) { presets.forEachIndexed { i, name -> com.rajdeep.listen.video.SelectChip(name, s.eqPreset == i) { vm.updateSettings { it.copy(eqPreset = i) } } } }
                    SliderRow("Bass boost", s.bassBoost / 10, 0..100, "%") { v -> vm.updateSettings { it.copy(bassBoost = v * 10) } }
                }
            }
            ActionRow(
                if (unrestricted) "Background playback: unrestricted ✓" else "Allow uninterrupted background playback",
                "Stops aggressive battery savers (Xiaomi, Oppo, Vivo, Realme) from killing music",
            ) {
                if (!unrestricted) runCatching {
                    ctx.startActivity(Intent(SysSettings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${ctx.packageName}")))
                }
            }
        }

        Group("Video player") {
            if (android.os.Build.VERSION.SDK_INT >= 26) {
                fun pipOn(): Boolean {
                    val ops = ctx.getSystemService(android.app.AppOpsManager::class.java) ?: return true
                    val m = if (android.os.Build.VERSION.SDK_INT >= 29) ops.unsafeCheckOpNoThrow(android.app.AppOpsManager.OPSTR_PICTURE_IN_PICTURE, ctx.applicationInfo.uid, ctx.packageName)
                    else @Suppress("DEPRECATION") ops.checkOpNoThrow(android.app.AppOpsManager.OPSTR_PICTURE_IN_PICTURE, ctx.applicationInfo.uid, ctx.packageName)
                    return m == android.app.AppOpsManager.MODE_ALLOWED
                }
                var pip by remember { mutableStateOf(pipOn()) }
                androidx.lifecycle.compose.LifecycleResumeEffect(Unit) { pip = pipOn(); onPauseOrDispose { } }
                ActionRow(
                    if (pip) "Mini player (picture-in-picture): allowed ✓" else "Allow mini player (picture-in-picture)",
                    "Keep watching in a floating window when you leave the player",
                ) {
                    if (!pip) runCatching { ctx.startActivity(Intent("android.settings.PICTURE_IN_PICTURE_SETTINGS", Uri.parse("package:${ctx.packageName}"))) }
                        .onFailure { runCatching { ctx.startActivity(Intent(SysSettings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}"))) } }
                }
            }
            ToggleRow("Auto-play next video in folder", s.autoPlayNextVideo) { v -> vm.updateSettings { it.copy(autoPlayNextVideo = v) } }
            ToggleRow("Resume where I left off", s.resumeVideos) { v -> vm.updateSettings { it.copy(resumeVideos = v) } }
            ToggleRow("Hardware decoding", s.hwDecoding) { v -> vm.updateSettings { it.copy(hwDecoding = v) } }
            ToggleRow("Volume boost up to 200%", s.volumeBoost) { v -> vm.updateSettings { it.copy(volumeBoost = v) } }
            SliderRow("Double-tap seek", s.seekStepSec, 5..30, " s") { v -> vm.updateSettings { it.copy(seekStepSec = v) } }
            TextRow("Preferred audio language", "e.g. hin, eng, hi, en", s.prefAudioLang) { v -> vm.updateSettings { it.copy(prefAudioLang = v) } }
            TextRow("Preferred subtitle language", "e.g. eng, en", s.prefSubLang) { v -> vm.updateSettings { it.copy(prefSubLang = v) } }
        }

        Group("Gestures") {
            ToggleRow("Brightness — swipe left side", s.gestureBrightness) { v -> vm.updateSettings { it.copy(gestureBrightness = v) } }
            ToggleRow("Volume — swipe right side", s.gestureVolume) { v -> vm.updateSettings { it.copy(gestureVolume = v) } }
            ToggleRow("Seek — swipe horizontally", s.gestureSeek) { v -> vm.updateSettings { it.copy(gestureSeek = v) } }
            ToggleRow("Double-tap (rewind / play-pause / forward)", s.gestureDoubleTap) { v -> vm.updateSettings { it.copy(gestureDoubleTap = v) } }
            ToggleRow("Pinch to zoom", s.gesturePinch) { v -> vm.updateSettings { it.copy(gesturePinch = v) } }
        }

        Group("Subtitles") {
            Text("Size, colour, background, outline, font and position are adjustable live from the player (CC → Style).", style = MaterialTheme.typography.bodyMedium, color = Palette.TextDim, modifier = Modifier.padding(vertical = 8.dp))
            SliderRow("Default size", s.subSizePercent, 50..300, "%") { v -> vm.updateSettings { it.copy(subSizePercent = v) } }
        }

        Group("Storage") {
            var bytes by remember { mutableStateOf<Long?>(null) }
            val scope = androidx.compose.runtime.rememberCoroutineScope()
            LaunchedEffect(Unit) { bytes = com.rajdeep.listen.data.CacheManager.cacheBytes(ctx) }
            ActionRow(
                "Clear cache" + (bytes?.let { " · " + com.rajdeep.listen.ui.components.formatSize(it) } ?: ""),
                "Removes cached covers, artist photos and suggestions. Your library, playlists and settings stay.",
            ) {
                scope.launch {
                    com.rajdeep.listen.data.CacheManager.clear(ctx)
                    bytes = com.rajdeep.listen.data.CacheManager.cacheBytes(ctx)
                }
            }
        }

        Group("Appearance & startup") {
            ThemePicker("Music theme", com.rajdeep.listen.ui.theme.Themes.music, s.musicTheme) { i -> vm.updateSettings { it.copy(musicTheme = i) } }
            ThemePicker("Video theme", com.rajdeep.listen.ui.theme.Themes.video, s.videoTheme) { i -> vm.updateSettings { it.copy(videoTheme = i) } }
            ToggleRow("Open in Video mode", s.startInVideo) { v -> vm.updateSettings { it.copy(startInVideo = v) } }
            ToggleRow("Reduce motion", s.reduceMotion) { v -> vm.updateSettings { it.copy(reduceMotion = v) } }
        }

        Group("About") {
            Column(Modifier.padding(vertical = 10.dp)) {
                Text("listen", style = MaterialTheme.typography.headlineMedium, color = Palette.Text)
                Text("Version ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.labelMedium, color = Palette.TextFaint)
                Spacer(Modifier.height(12.dp))
                Text(
                    "Listen is a personal music and video player built for local files first. " +
                        "Music plays with streaming-app polish — dynamic artwork colours, synced lyrics, smart mixes from your own listening — " +
                        "while video gets desktop-class control: every codec, every audio track, every subtitle format, and gestures for brightness, volume, seeking and zoom.",
                    style = MaterialTheme.typography.bodyMedium, color = Palette.TextDim,
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    "Your files never leave the device. No accounts, no ads, no tracking. Online features are optional and use only free, legal sources.",
                    style = MaterialTheme.typography.bodyMedium, color = Palette.TextDim,
                )
                Spacer(Modifier.height(16.dp))
                Text("CREATED BY", style = MaterialTheme.typography.labelSmall, color = Palette.TextFaint)
                Text("Rajdeep Biswas", style = MaterialTheme.typography.titleLarge, color = LocalAccent.current.primary)
                Text("Designed and built in Kolkata, India", style = MaterialTheme.typography.bodySmall, color = Palette.TextDim)
                Spacer(Modifier.height(16.dp))
                Text("BUILT WITH", style = MaterialTheme.typography.labelSmall, color = Palette.TextFaint)
                Text(
                    "Kotlin · Jetpack Compose · Media3 ExoPlayer · libVLC · Room · Coil\nMusic & Lyrics: JioSaavn, FreeToUse, Incompetech, Audius, Jamendo, Deezer, iTunes, ccMixter, Internet Archive, RadioBrowser, LRCLIB, Lyrics.ovh",
                    style = MaterialTheme.typography.bodySmall, color = Palette.TextDim,
                )
                Spacer(Modifier.height(12.dp))
                Text("© 2026 Rajdeep Biswas. All rights reserved.", style = MaterialTheme.typography.bodySmall, color = Palette.TextFaint)
            }
        }
        Spacer(Modifier.height(24.dp))
    }
    @Suppress("unused") val a = activity
}


@Composable
private fun HiddenFoldersDialog(vm: AppViewModel, hidden: Set<String>, onDismiss: () -> Unit) {
    val folders by androidx.compose.runtime.produceState<List<String>?>(null) { value = vm.library.allFolders() }
    var sel by remember { mutableStateOf(hidden) }
    val accent = LocalAccent.current.primary
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss, containerColor = Palette.SurfaceHigh,
        title = { Text("Hidden folders", color = Palette.Text) },
        text = {
            Column(Modifier.height(380.dp).verticalScroll(rememberScrollState())) {
                Text("Checked folders are left out of your music and video library.", style = MaterialTheme.typography.bodySmall, color = Palette.TextDim)
                Spacer(Modifier.height(8.dp))
                if (folders == null) Text("Loading…", color = Palette.TextDim)
                folders.orEmpty().forEach { f ->
                    Row(
                        Modifier.fillMaxWidth().clickable { sel = if (f in sel) sel - f else sel + f }.padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        androidx.compose.material3.Checkbox(
                            checked = f in sel, onCheckedChange = { sel = if (it) sel + f else sel - f },
                            colors = androidx.compose.material3.CheckboxDefaults.colors(checkedColor = accent, checkmarkColor = Palette.Ink),
                        )
                        Column {
                            Text(f.substringAfterLast('/'), style = MaterialTheme.typography.titleSmall, color = Palette.Text)
                            Text(f, style = MaterialTheme.typography.bodySmall, color = Palette.TextFaint)
                        }
                    }
                }
            }
        },
        confirmButton = {
            androidx.compose.material3.TextButton(onClick = {
                vm.updateSettings { it.copy(hiddenFolders = sel) }.invokeOnCompletion { vm.rescan() }
                onDismiss()
            }) { Text("Save", color = accent) }
        },
        dismissButton = { androidx.compose.material3.TextButton(onClick = onDismiss) { Text("Cancel", color = Palette.TextDim) } },
    )
}


@Composable
private fun ThemePicker(label: String, themes: List<com.rajdeep.listen.ui.theme.ThemeSpec>, selected: Int, onPick: (Int) -> Unit) {
    Column(Modifier.padding(vertical = 8.dp)) {
        Text(label, style = MaterialTheme.typography.titleSmall, color = Palette.Text)
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(10.dp)) {
            themes.forEachIndexed { i, t ->
                val sel = i == selected
                Column(
                    Modifier.weight(1f).clip(RoundedCornerShape(16.dp))
                        .background(if (sel) t.primary.copy(alpha = 0.14f) else Palette.SurfaceHigh)
                        .border(if (sel) 2.dp else 1.dp, if (sel) t.primary else Palette.Line, RoundedCornerShape(16.dp))
                        .clickable { onPick(i) }.padding(vertical = 12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    androidx.compose.foundation.layout.Box(
                        Modifier.size(34.dp).clip(androidx.compose.foundation.shape.CircleShape)
                            .background(androidx.compose.ui.graphics.Brush.linearGradient(listOf(t.primary, t.secondary))),
                        contentAlignment = Alignment.Center,
                    ) { if (sel) androidx.compose.material3.Icon(androidx.compose.material.icons.Icons.Rounded.Check, null, tint = Palette.Ink, modifier = Modifier.size(18.dp)) }
                    Spacer(Modifier.height(6.dp))
                    Text(t.name, style = MaterialTheme.typography.labelMedium, color = if (sel) t.primary else Palette.TextDim)
                }
            }
        }
    }
}
