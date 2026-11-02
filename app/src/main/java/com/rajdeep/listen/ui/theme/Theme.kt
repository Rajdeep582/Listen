package com.rajdeep.listen.ui.theme

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.rajdeep.listen.R

object Palette {
    val Ink = Color(0xFF0A0A0F)
    val Surface = Color(0xFF13131B)
    val SurfaceHigh = Color(0xFF1C1C27)
    val SurfaceTop = Color(0xFF262634)
    val Line = Color(0xFF2A2A38)
    val Text = Color(0xFFF4EFE8)
    val TextDim = Color(0xFF9C98A6)
    val TextFaint = Color(0xFF6A6775)
    val Ember = Color(0xFFFF6A3D)
    val Saffron = Color(0xFFFFB547)
    val Violet = Color(0xFF8B7CFF)
    val Cyan = Color(0xFF4CC9F0)
    val Danger = Color(0xFFFF5470)
}

enum class Mode { MUSIC, VIDEO }

/** A selectable colour theme: primary accent + secondary (gradients, highlights). */
@Immutable
data class ThemeSpec(val name: String, val primary: Color, val secondary: Color)

object Themes {
    val music = listOf(
        ThemeSpec("Ember", Color(0xFFFF6A3D), Color(0xFFFFB547)),
        ThemeSpec("Emerald", Color(0xFF1FD6A0), Color(0xFFC3F36B)),
        ThemeSpec("Amethyst", Color(0xFFB07CFF), Color(0xFFFF8AD8)),
    )
    val video = listOf(
        ThemeSpec("Nebula", Color(0xFF8B7CFF), Color(0xFF4CC9F0)),
        ThemeSpec("Ocean", Color(0xFF2F9BFF), Color(0xFF5EF0D8)),
        ThemeSpec("Ruby", Color(0xFFFF4D6D), Color(0xFFFFB36B)),
    )
    fun of(mode: Mode): ThemeSpec = if (mode == Mode.MUSIC) music[ThemeChoice.music.coerceIn(0, 2)] else video[ThemeChoice.video.coerceIn(0, 2)]
}

/** Current selection; snapshot state so every themed screen recomposes when the user picks a new one. */
object ThemeChoice {
    var music by androidx.compose.runtime.mutableIntStateOf(0)
    var video by androidx.compose.runtime.mutableIntStateOf(0)
}

@Immutable
data class Accent(val primary: Color, val secondary: Color)

/** User setting: fewer/ shorter animations. */
val LocalReduceMotion = androidx.compose.runtime.compositionLocalOf { false }

val LocalAccent = staticCompositionLocalOf { Accent(Palette.Ember, Palette.Saffron) }

val Grotesk = FontFamily(
    Font(R.font.grotesk_medium, FontWeight.Medium),
    Font(R.font.grotesk_bold, FontWeight.Bold),
)
val Manrope = FontFamily(
    Font(R.font.manrope_regular, FontWeight.Normal),
    Font(R.font.manrope_medium, FontWeight.Medium),
    Font(R.font.manrope_semibold, FontWeight.SemiBold),
    Font(R.font.manrope_extrabold, FontWeight.ExtraBold),
)

private val type = Typography(
    displayLarge = TextStyle(fontFamily = Grotesk, fontWeight = FontWeight.Bold, fontSize = 44.sp, letterSpacing = (-0.03).em, lineHeight = 46.sp),
    displaySmall = TextStyle(fontFamily = Grotesk, fontWeight = FontWeight.Bold, fontSize = 32.sp, letterSpacing = (-0.02).em, lineHeight = 36.sp),
    headlineMedium = TextStyle(fontFamily = Grotesk, fontWeight = FontWeight.Bold, fontSize = 26.sp, letterSpacing = (-0.02).em),
    headlineSmall = TextStyle(fontFamily = Grotesk, fontWeight = FontWeight.Bold, fontSize = 21.sp, letterSpacing = (-0.01).em),
    titleLarge = TextStyle(fontFamily = Manrope, fontWeight = FontWeight.ExtraBold, fontSize = 19.sp),
    titleMedium = TextStyle(fontFamily = Manrope, fontWeight = FontWeight.SemiBold, fontSize = 15.sp),
    titleSmall = TextStyle(fontFamily = Manrope, fontWeight = FontWeight.SemiBold, fontSize = 13.sp),
    bodyLarge = TextStyle(fontFamily = Manrope, fontWeight = FontWeight.Medium, fontSize = 15.sp, lineHeight = 22.sp),
    bodyMedium = TextStyle(fontFamily = Manrope, fontWeight = FontWeight.Medium, fontSize = 13.sp, lineHeight = 18.sp),
    bodySmall = TextStyle(fontFamily = Manrope, fontWeight = FontWeight.Medium, fontSize = 12.sp),
    labelLarge = TextStyle(fontFamily = Grotesk, fontWeight = FontWeight.Bold, fontSize = 13.sp, letterSpacing = 0.12.em),
    labelMedium = TextStyle(fontFamily = Manrope, fontWeight = FontWeight.SemiBold, fontSize = 11.sp, letterSpacing = 0.04.em),
    labelSmall = TextStyle(fontFamily = Grotesk, fontWeight = FontWeight.Medium, fontSize = 10.sp, letterSpacing = 0.14.em),
)

@Composable
fun ListenTheme(mode: Mode = Mode.MUSIC, content: @Composable () -> Unit) {
    // Instant swap (no per-frame colour animation): animating the whole colour scheme recomposes
    // every themed node for ~0.4 s and was the main source of jank on mode switch.
    val spec = Themes.of(mode)
    val primary = spec.primary
    val secondary = spec.secondary
    val scheme = androidx.compose.runtime.remember(spec) { darkColorScheme(
        primary = primary, onPrimary = Palette.Ink,
        secondary = secondary, onSecondary = Palette.Ink,
        background = Palette.Ink, onBackground = Palette.Text,
        surface = Palette.Surface, onSurface = Palette.Text,
        surfaceVariant = Palette.SurfaceHigh, onSurfaceVariant = Palette.TextDim,
        surfaceContainer = Palette.SurfaceHigh, surfaceContainerHigh = Palette.SurfaceTop,
        surfaceContainerHighest = Palette.SurfaceTop, surfaceContainerLow = Palette.Surface,
        outline = Palette.Line, outlineVariant = Palette.Line, error = Palette.Danger,
    ) }
    val accent = androidx.compose.runtime.remember(spec) { Accent(primary, secondary) }
    CompositionLocalProvider(LocalAccent provides accent) {
        MaterialTheme(colorScheme = scheme, typography = type, content = content)
    }
}
