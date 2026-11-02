package com.rajdeep.listen.video

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.FileOpen
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rajdeep.listen.data.Settings
import com.rajdeep.listen.ui.theme.LocalAccent
import com.rajdeep.listen.ui.theme.Palette
import kotlin.math.roundToInt

@Composable
private fun PanelScaffold(title: String, onBack: (() -> Unit)? = null, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (onBack != null) IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back", tint = Palette.Text) }
            Text(title, style = MaterialTheme.typography.headlineSmall, color = Palette.Text)
        }
        Spacer(Modifier.height(12.dp))
        content()
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(text.uppercase(), style = MaterialTheme.typography.labelSmall, color = Palette.TextFaint, modifier = Modifier.padding(top = 14.dp, bottom = 8.dp))
}

@Composable
private fun TrackRow(selected: Boolean, title: String, subtitle: String?, onClick: () -> Unit) {
    val accent = LocalAccent.current.primary
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
            .background(if (selected) accent.copy(alpha = 0.14f) else Color.Transparent)
            .clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = if (selected) accent else Palette.Text)
            if (!subtitle.isNullOrBlank()) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = Palette.TextDim)
        }
        if (selected) Icon(Icons.Rounded.Check, null, tint = accent, modifier = Modifier.size(20.dp))
    }
}

private fun Track.subtitleLine(): String? = listOfNotNull(language?.uppercase(), detail?.ifBlank { null }).joinToString(" · ").ifBlank { null }

@Composable
private fun DelayStepper(label: String, valueUs: Long, onChange: (Long) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.titleSmall, color = Palette.Text, modifier = Modifier.weight(1f))
        StepBtn(Icons.Rounded.Remove) { onChange(valueUs - 100_000) }
        Text(
            "${"%+.1f".format(valueUs / 1_000_000f)} s", style = MaterialTheme.typography.titleSmall, color = Palette.Text,
            textAlign = TextAlign.Center, modifier = Modifier.width(72.dp).clickable { onChange(0) },
        )
        StepBtn(Icons.Rounded.Add) { onChange(valueUs + 100_000) }
    }
    Text("Tap value to reset · ±100 ms per step", style = MaterialTheme.typography.bodySmall, color = Palette.TextFaint)
}

@Composable
private fun StepBtn(icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
    Box(Modifier.size(38.dp).clip(CircleShape).background(Palette.SurfaceTop).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(icon, null, tint = Palette.Text, modifier = Modifier.size(18.dp))
    }
}

@Composable
fun AudioPanel(vlc: VlcController, state: VideoUiState) {
    PanelScaffold("Audio") {
        SectionLabel("Tracks")
        val tracks = state.audioTracks
        if (tracks.isEmpty()) Text("No audio tracks detected", color = Palette.TextDim, style = MaterialTheme.typography.bodyMedium)
        tracks.forEach { t ->
            TrackRow(t.id == state.audioTrack, if (t.id < 0) "Disable audio" else t.name, t.subtitleLine()) { vlc.setAudioTrack(t.id) }
        }
        SectionLabel("Sync")
        DelayStepper("Audio delay", state.audioDelayUs) { vlc.setAudioDelay(it) }
    }
}

@Composable
fun SubtitlePanel(host: VideoPlayerActivity, state: VideoUiState, onStyle: () -> Unit, onToast: (String) -> Unit) {
    val vlc = host.vlc
    PanelScaffold("Subtitles") {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ChipButton(Icons.Rounded.FileOpen, "Load file") { host.pickSubtitleFile { err -> onToast(err ?: "Subtitle loaded") } }
            ChipButton(Icons.Rounded.Palette, "Style", onStyle)
        }
        SectionLabel("Tracks")
        if (state.spuTracks.isEmpty()) Text("No subtitles in this file. Load an SRT / VTT / ASS / SSA file.", color = Palette.TextDim, style = MaterialTheme.typography.bodyMedium)
        state.spuTracks.forEach { t ->
            TrackRow(t.id == state.spuTrack, if (t.id < 0) "Off" else t.name, t.subtitleLine()) { vlc.setSpuTrack(t.id) }
        }
        SectionLabel("Sync")
        DelayStepper("Subtitle delay", state.spuDelayUs) { vlc.setSpuDelay(it) }
    }
}

@Composable
private fun ChipButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    Row(
        Modifier.clip(CircleShape).background(Palette.SurfaceTop).clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = LocalAccent.current.secondary, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(label, style = MaterialTheme.typography.titleSmall, color = Palette.Text)
    }
}

private val subColors = listOf(0xFFFFFFFF, 0xFFFFE066, 0xFF7FE7FF, 0xFFA6FF8F, 0xFFFFA8D9, 0xFFFFB547).map { it.toInt() }

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SubtitleStylePanel(host: VideoPlayerActivity, s: Settings, onBack: () -> Unit) {
    val accent = LocalAccent.current
    var dirty by remember { mutableStateOf(false) }
    fun upd(f: (Settings) -> Settings) { host.updateSettings(f); dirty = true }
    PanelScaffold("Subtitle style", onBack = onBack) {
        // live preview
        Box(Modifier.fillMaxWidth().height(84.dp).clip(RoundedCornerShape(16.dp)).background(Brush3), contentAlignment = Alignment.BottomCenter) {
            Text(
                "The quick brown fox", color = Color(s.subColor),
                fontSize = (15 * s.subSizePercent / 100f).coerceIn(8f, 40f).sp,
                fontWeight = if (s.subBold) FontWeight.Bold else FontWeight.Normal,
                fontFamily = when (s.subFont) { "serif" -> FontFamily.Serif; "mono" -> FontFamily.Monospace; else -> FontFamily.SansSerif },
                modifier = Modifier.padding(bottom = (s.subMarginDp / 4).coerceIn(4, 40).dp).background(Color.Black.copy(alpha = s.subBgOpacity / 255f)).padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }
        SectionLabel("Size  ${s.subSizePercent}%")
        StyledSlider(s.subSizePercent.toFloat(), 50f..300f) { v -> upd { it.copy(subSizePercent = v.roundToInt()) } }
        SectionLabel("Colour")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            subColors.forEach { c ->
                Box(
                    Modifier.size(34.dp).clip(CircleShape).background(Color(c))
                        .border(if (s.subColor == c) 3.dp else 0.dp, accent.primary, CircleShape)
                        .clickable { upd { it.copy(subColor = c) } },
                )
            }
        }
        SectionLabel("Background opacity  ${(s.subBgOpacity / 2.55f).roundToInt()}%")
        StyledSlider(s.subBgOpacity.toFloat(), 0f..255f) { v -> upd { it.copy(subBgOpacity = v.roundToInt()) } }
        SectionLabel("Position (bottom margin)  ${s.subMarginDp} dp")
        StyledSlider(s.subMarginDp.toFloat(), 0f..200f) { v -> upd { it.copy(subMarginDp = v.roundToInt()) } }
        SectionLabel("Font")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("default" to "Default", "serif" to "Serif", "mono" to "Mono", "condensed" to "Condensed").forEach { (k, l) ->
                SelectChip(l, s.subFont == k) { upd { it.copy(subFont = k) } }
            }
        }
        ToggleRow("Outline + shadow", s.subOutline) { v -> upd { it.copy(subOutline = v) } }
        ToggleRow("Bold", s.subBold) { v -> upd { it.copy(subBold = v) } }
        Spacer(Modifier.height(16.dp))
        PillButton(if (dirty) "Apply to video" else "Applied") { if (dirty) { host.reloadKeepingState(); dirty = false } }
        Text("Applying reloads the decoder (~½ s); position, tracks and delays are kept.", style = MaterialTheme.typography.bodySmall, color = Palette.TextFaint, modifier = Modifier.padding(top = 8.dp))
    }
}

private val Brush3 = androidx.compose.ui.graphics.Brush.linearGradient(listOf(Color(0xFF2B3A55), Color(0xFF5B3A4E), Color(0xFF1E2E2A)))

@Composable
private fun StyledSlider(value: Float, range: ClosedFloatingPointRange<Float>, onChange: (Float) -> Unit) {
    val accent = LocalAccent.current.primary
    var local by remember(value) { mutableFloatStateOf(value) }
    Slider(
        value = local, onValueChange = { local = it }, onValueChangeFinished = { onChange(local) }, valueRange = range,
        colors = SliderDefaults.colors(thumbColor = accent, activeTrackColor = accent, inactiveTrackColor = Palette.SurfaceTop),
    )
}

@Composable
fun ToggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.titleSmall, color = Palette.Text, modifier = Modifier.weight(1f))
        Switch(
            checked = checked, onCheckedChange = onChange,
            colors = SwitchDefaults.colors(checkedTrackColor = LocalAccent.current.primary, checkedThumbColor = Palette.Ink, uncheckedTrackColor = Palette.SurfaceTop),
        )
    }
}

@Composable
fun SelectChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val accent = LocalAccent.current.primary
    Text(
        label, style = MaterialTheme.typography.titleSmall,
        color = if (selected) Palette.Ink else Palette.Text,
        modifier = Modifier.clip(CircleShape).background(if (selected) accent else Palette.SurfaceTop).clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 9.dp),
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SpeedPanel(vlc: VlcController, rate: Float) {
    PanelScaffold("Playback speed") {
        Text("${"%.2f".format(rate)}×", style = MaterialTheme.typography.displaySmall, color = LocalAccent.current.primary)
        Spacer(Modifier.height(8.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(0.25f, 0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f, 2.5f, 3f, 4f).forEach { r ->
                SelectChip(if (r == 1f) "Normal" else "${r}×", abs(rate - r) < 0.01f) { vlc.setRate(r) }
            }
        }
        SectionLabel("Fine tune")
        StyledSlider(rate, 0.25f..4f) { vlc.setRate((it * 20).roundToInt() / 20f) }
        Text("Pitch is preserved (time-stretch).", style = MaterialTheme.typography.bodySmall, color = Palette.TextFaint)
    }
}

private fun abs(f: Float) = kotlin.math.abs(f)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ScalePanel(vlc: VlcController, scale: Scale, zoom: Float, onResetZoom: () -> Unit, rotation: Int, onRotation: (Int) -> Unit) {
    PanelScaffold("Aspect & zoom") {
        SectionLabel("Fit")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Scale.entries.filter { it.vlc != null }.forEach { s -> SelectChip(s.label, s == scale) { vlc.applyScale(s) } }
        }
        SectionLabel("Aspect ratio")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Scale.entries.filter { it.aspect != null }.forEach { s -> SelectChip(s.label, s == scale) { vlc.applyScale(s) } }
        }
        SectionLabel("Rotation")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(0, 90, 180, 270).forEach { r -> SelectChip("$r°", rotation == r) { onRotation(r) } }
        }
        SectionLabel("Zoom  ${(zoom * 100).roundToInt()}%")
        Text("Pinch on the video to zoom and pan.", style = MaterialTheme.typography.bodySmall, color = Palette.TextDim)
        Spacer(Modifier.height(8.dp))
        PillButton("Reset zoom", filled = false, onClick = onResetZoom)
    }
}

@Composable
fun InfoPanel(host: VideoPlayerActivity, state: VideoUiState) {
    PanelScaffold("Media info") {
        val e = host.current
        Text(e?.title.orEmpty(), style = MaterialTheme.typography.titleMedium, color = Palette.Text)
        Spacer(Modifier.height(6.dp))
        if (state.videoW > 0) Text("${state.videoW} × ${state.videoH}", style = MaterialTheme.typography.bodyMedium, color = Palette.TextDim)
        SectionLabel("Streams")
        Text(state.codecInfo.ifBlank { "Reading…" }, style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace), color = Palette.TextDim)
        SectionLabel("Queue")
        Text("${host.index.intValue + 1} of ${host.playlist.value.size}", style = MaterialTheme.typography.bodyMedium, color = Palette.TextDim)
    }
}
