package com.rajdeep.listen.ui.player

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.palette.graphics.Palette as AndroidPalette
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import com.rajdeep.listen.ui.theme.Palette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Immutable
data class ArtColors(val vibrant: Color, val dark: Color, val muted: Color)

private val cache = HashMap<Any, ArtColors>()
private val fallback get() = ArtColors(com.rajdeep.listen.ui.theme.Themes.of(com.rajdeep.listen.ui.theme.Mode.MUSIC).primary, Color(0xFF1C1822), Color(0xFF2E2A36))

/** Extracts a palette from artwork off the main thread; animates between tracks. */
@Composable
fun rememberArtColors(model: Any?): ArtColors {
    val ctx = LocalContext.current
    var target by remember { mutableStateOf(model?.let { cache[it] } ?: fallback) }
    LaunchedEffect(model) {
        if (model == null) { target = fallback; return@LaunchedEffect }
        cache[model]?.let { target = it; return@LaunchedEffect }
        val c = withContext(Dispatchers.Default) {
            val req = ImageRequest.Builder(ctx).data(model).size(128).allowHardware(false).build()
            val res = SingletonImageLoader.get(ctx).execute(req)
            val bmp = (res as? SuccessResult)?.image?.toBitmap() ?: return@withContext null
            val p = AndroidPalette.from(bmp).maximumColorCount(16).generate()
            val vib = p.vibrantSwatch ?: p.lightVibrantSwatch ?: p.dominantSwatch
            val dark = p.darkVibrantSwatch ?: p.darkMutedSwatch ?: p.dominantSwatch
            val muted = p.mutedSwatch ?: p.dominantSwatch
            ArtColors(
                vibrant = vib?.let { Color(it.rgb) } ?: com.rajdeep.listen.ui.theme.Themes.of(com.rajdeep.listen.ui.theme.Mode.MUSIC).primary,
                dark = lerp(dark?.let { Color(it.rgb) } ?: Color(0xFF221A2A), Palette.Ink, 0.35f),
                muted = muted?.let { Color(it.rgb) } ?: Color(0xFF3A2A2A),
            )
        } ?: fallback
        if (cache.size > 200) cache.clear()
        cache[model] = c
        target = c
    }
    val spec = tween<Color>(700)
    val v by animateColorAsState(target.vibrant, spec, label = "v")
    val d by animateColorAsState(target.dark, spec, label = "d")
    val m by animateColorAsState(target.muted, spec, label = "m")
    return ArtColors(v, d, m)
}
