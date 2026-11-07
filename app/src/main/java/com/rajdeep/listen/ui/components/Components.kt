package com.rajdeep.listen.ui.components

import android.net.Uri
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import coil3.compose.SubcomposeAsyncImage
import coil3.request.ImageRequest
import coil3.video.videoFrameMillis
import com.rajdeep.listen.data.db.Song
import com.rajdeep.listen.playback.albumArtUri
import com.rajdeep.listen.ui.theme.LocalAccent
import com.rajdeep.listen.ui.theme.Palette
import kotlin.math.absoluteValue

fun formatDuration(ms: Long): String {
    if (ms <= 0) return "0:00"
    val s = ms / 1000
    val h = s / 3600; val m = (s % 3600) / 60; val sec = s % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
}

fun formatSize(bytes: Long): String = when {
    bytes >= 1L shl 30 -> "%.1f GB".format(bytes / (1L shl 30).toFloat())
    bytes >= 1L shl 20 -> "%.0f MB".format(bytes / (1L shl 20).toFloat())
    else -> "${bytes / 1024} KB"
}

/** Deterministic two-tone gradient from any seed: placeholder art that still looks designed. */
fun seedBrush(seed: String): Brush {
    val h = seed.hashCode().absoluteValue
    val hues = listOf(
        Color(0xFFFF6A3D) to Color(0xFF6B2BD9), Color(0xFF16C4A0) to Color(0xFF1A3A8F),
        Color(0xFFFFB547) to Color(0xFFD9285E), Color(0xFF8B7CFF) to Color(0xFF19202F),
        Color(0xFF4CC9F0) to Color(0xFF3A0CA3), Color(0xFFE76F51) to Color(0xFF264653),
    )
    val (a, b) = hues[h % hues.size]
    return Brush.linearGradient(listOf(a, b))
}

@Composable
fun Artwork(
    model: Any?,
    seed: String,
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(12.dp),
    icon: ImageVector = Icons.Rounded.MusicNote,
    videoFrame: Boolean = false,
    /** Online fallback used when [model] is missing or fails (or first, if [preferLookup]). */
    lookup: com.rajdeep.listen.data.online.ArtResolver.Query? = null,
    preferLookup: Boolean = false,
) {
    val ctx = LocalContext.current
    val request = remember(model, videoFrame) {
        model?.let { ImageRequest.Builder(ctx).data(it).apply { if (videoFrame) videoFrameMillis(4000) }.build() }
    }
    var primaryFailed by remember(model) { mutableStateOf(model == null) }
    // cached remote hit is used synchronously → no flicker when scrolling back
    var remote by remember(lookup) { mutableStateOf(lookup?.let { com.rajdeep.listen.data.online.ArtResolver.peek(it)?.ifEmpty { null } }) }
    val wantRemote = lookup != null && (preferLookup || primaryFailed)
    if (wantRemote && remote == null) {
        androidx.compose.runtime.LaunchedEffect(lookup) { remote = com.rajdeep.listen.data.online.ArtResolver.resolve(lookup!!) }
    }
    // Placeholder underneath + plain AsyncImage on top (no subcomposition per item → smooth scrolling).
    Box(modifier.clip(shape).background(Palette.SurfaceHigh)) {
        Placeholder(seed, icon)
        val showRemote = remote != null && (preferLookup || primaryFailed)
        if (request != null && !(preferLookup && showRemote)) coil3.compose.AsyncImage(
            model = request, contentDescription = null, contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
            onError = { primaryFailed = true },
        )
        if (showRemote) coil3.compose.AsyncImage(
            model = remote, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize(),
        )
    }
}

/**
 * Resolves the final artwork model for a single prominent surface (Now Playing, headers):
 * local art if it decodes, otherwise online artwork.
 */
@Composable
fun rememberArtModel(model: Any?, lookup: com.rajdeep.listen.data.online.ArtResolver.Query?): Any? {
    val ctx = LocalContext.current
    var out by remember(model, lookup) { mutableStateOf(model) }
    androidx.compose.runtime.LaunchedEffect(model, lookup) {
        val ok = model != null && runCatching {
            coil3.SingletonImageLoader.get(ctx).execute(ImageRequest.Builder(ctx).data(model).size(64).build()) is coil3.request.SuccessResult
        }.getOrDefault(false)
        if (!ok && lookup != null) com.rajdeep.listen.data.online.ArtResolver.resolve(lookup)?.let { out = it }
    }
    return out
}

@Composable
private fun Placeholder(seed: String, icon: ImageVector) {
    val brush = remember(seed) { seedBrush(seed) }
    Box(Modifier.fillMaxSize().background(brush), contentAlignment = Alignment.Center) {
        Icon(icon, null, tint = Color.White.copy(alpha = 0.55f), modifier = Modifier.fillMaxSize(0.38f))
    }
}

fun songArt(song: Song): Any = com.rajdeep.listen.data.LocalArt(song.uri)

/** Album cover from the device (embedded art); online lookup is the fallback. */
fun albumArt(albumId: Long): Any = com.rajdeep.listen.data.localAlbumArt(albumId)

fun songLookup(song: Song): com.rajdeep.listen.data.online.ArtResolver.Query =
    if (song.album.lowercase().startsWith("unknown") || song.album.equals(song.folder.substringAfterLast('/'), true))
        com.rajdeep.listen.data.online.ArtResolver.TrackQ(song.artist, song.title)
    else com.rajdeep.listen.data.online.ArtResolver.AlbumQ(song.albumArtist.ifEmpty { song.artist }, song.album)

fun songTrackLookup(artist: String, title: String) = com.rajdeep.listen.data.online.ArtResolver.TrackQ(artist, title)

/** Lookup for whatever is playing: album for local files, track for online items; none for radio. */
fun com.rajdeep.listen.playback.NowPlaying.artLookup(): com.rajdeep.listen.data.online.ArtResolver.Query? = when {
    isLive -> null
    localId != null && album.isNotBlank() && !album.lowercase().startsWith("unknown") -> com.rajdeep.listen.data.online.ArtResolver.AlbumQ(artist, album)
    else -> com.rajdeep.listen.data.online.ArtResolver.TrackQ(artist, title)
}

fun albumLookup(artist: String, album: String) = com.rajdeep.listen.data.online.ArtResolver.AlbumQ(artist, album)
fun artistLookup(name: String) = com.rajdeep.listen.data.online.ArtResolver.ArtistQ(name)

@Composable
fun Modifier.pressScale(source: MutableInteractionSource): Modifier {
    val pressed by source.collectIsPressedAsState()
    val s by animateFloatAsState(if (pressed) 0.96f else 1f, label = "press")
    return this.graphicsLayer { scaleX = s; scaleY = s }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SongRow(
    song: Song,
    isCurrent: Boolean,
    onClick: () -> Unit,
    onMore: () -> Unit,
    modifier: Modifier = Modifier,
    leading: (@Composable () -> Unit)? = null,
) {
    val accent = LocalAccent.current.primary
    var favoriteMessage by remember(song.id) { mutableStateOf<String?>(null) }
    LaunchedEffect(favoriteMessage) {
        if (favoriteMessage != null) { delay(1400); favoriteMessage = null }
    }
    Row(
        modifier.fillMaxWidth().combinedClickable(onClick = onClick, onLongClick = onMore).padding(horizontal = 16.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leading != null) leading() else Box {
            Artwork(songArt(song), song.album, Modifier.size(50.dp), RoundedCornerShape(10.dp), lookup = songLookup(song))
            if (isCurrent) Box(Modifier.size(50.dp).clip(RoundedCornerShape(10.dp)).background(Color.Black.copy(0.5f)), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.GraphicEq, null, tint = accent, modifier = Modifier.size(24.dp))
            }
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(song.title, style = MaterialTheme.typography.titleMedium, color = if (isCurrent) accent else Palette.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (song.isFavorite) { Icon(Icons.Rounded.Favorite, null, tint = accent, modifier = Modifier.size(12.dp)); Spacer(Modifier.width(4.dp)) }
                Text(favoriteMessage ?: "${song.artist} · ${formatDuration(song.duration)}", style = MaterialTheme.typography.bodyMedium, color = if (favoriteMessage != null) accent else Palette.TextDim, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        val app = LocalContext.current.applicationContext as com.rajdeep.listen.ListenApp
        IconButton(onClick = {
            app.container.library.toggleFavorite(song.id)
            favoriteMessage = if (song.isFavorite) "Removed from favourites" else "Added to favourites"
        }) {
            Icon(
                if (song.isFavorite) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                if (song.isFavorite) "Remove from favourites" else "Add to favourites",
                tint = if (song.isFavorite) accent else Palette.TextDim,
            )
        }
        IconButton(onClick = onMore) { Icon(Icons.Rounded.MoreVert, "More", tint = Palette.TextDim) }
    }
}

@Composable
fun SectionHeader(title: String, subtitle: String? = null, action: String? = null, onAction: (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 26.dp, bottom = 12.dp), verticalAlignment = Alignment.Bottom) {
        Column(Modifier.weight(1f)) {
            if (subtitle != null) Text(subtitle.uppercase(), style = MaterialTheme.typography.labelSmall, color = Palette.TextFaint)
            Text(title, style = MaterialTheme.typography.headlineSmall, color = Palette.Text)
        }
        if (action != null && onAction != null) {
            Text(
                action, style = MaterialTheme.typography.titleSmall, color = LocalAccent.current.primary,
                modifier = Modifier.clip(CircleShape).combinedClickableSimple(onAction).padding(horizontal = 10.dp, vertical = 6.dp),
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun Modifier.combinedClickableSimple(onClick: () -> Unit): Modifier = this.combinedClickable(onClick = onClick)

@Composable
fun MediaCard(
    title: String,
    subtitle: String,
    art: Any?,
    seed: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    width: Dp = 148.dp,
    circle: Boolean = false,
    badge: String? = null,
    onLongClick: (() -> Unit)? = null,
    lookup: com.rajdeep.listen.data.online.ArtResolver.Query? = null,
    preferLookup: Boolean = false,
    isFavorite: Boolean? = null,
    onFavoriteClick: (() -> Boolean)? = null,
) {
    val src = remember { MutableInteractionSource() }
    var favoriteMessage by remember(seed) { mutableStateOf<String?>(null) }
    LaunchedEffect(favoriteMessage) {
        if (favoriteMessage != null) { delay(1400); favoriteMessage = null }
    }
    Column(modifier.width(width).pressScale(src).clickableWith(src, onClick, onLongClick)) {
        Box {
            Artwork(art, seed, Modifier.fillMaxWidth().aspectRatio(1f), if (circle) CircleShape else RoundedCornerShape(16.dp), lookup = lookup, preferLookup = preferLookup)
            if (badge != null) Badge(badge, Modifier.align(Alignment.TopStart).padding(8.dp))
            if (onFavoriteClick != null && isFavorite != null) {
                val accent = LocalAccent.current.primary
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp)
                        .size(30.dp)
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.55f))
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        onClick = {
                            favoriteMessage = if (!onFavoriteClick()) "Sign in to save favourites" else if (isFavorite) "Removed from favourites" else "Added to favourites"
                        }
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        if (isFavorite) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                        contentDescription = if (isFavorite) "Remove from favourites" else "Add to favourites",
                        tint = if (isFavorite) accent else Color.White,
                        modifier = Modifier.size(17.dp)
                    )
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        Text(title, style = MaterialTheme.typography.titleSmall, color = Palette.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(favoriteMessage ?: subtitle, style = MaterialTheme.typography.bodySmall, color = if (favoriteMessage != null) LocalAccent.current.primary else Palette.TextDim, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Modifier.clickableWith(src: MutableInteractionSource, onClick: () -> Unit, onLong: (() -> Unit)?): Modifier =
    this.combinedClickable(interactionSource = src, indication = null, onClick = onClick, onLongClick = onLong)

@Composable
fun Badge(text: String, modifier: Modifier = Modifier, color: Color = LocalAccent.current.secondary) {
    Text(
        text.uppercase(), style = MaterialTheme.typography.labelSmall, color = Palette.Ink,
        modifier = modifier.clip(RoundedCornerShape(6.dp)).background(color).padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

@Composable
fun <T> CardRow(items: List<T>, key: (T) -> Any, content: @Composable (T) -> Unit) {
    LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        items(items, key = key) { content(it) }
    }
}

@Composable
fun EmptyState(icon: ImageVector, title: String, body: String, modifier: Modifier = Modifier, action: (@Composable () -> Unit)? = null) {
    Column(modifier.fillMaxWidth().padding(40.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(84.dp).clip(CircleShape).background(Palette.SurfaceHigh), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = LocalAccent.current.primary, modifier = Modifier.size(38.dp))
        }
        Spacer(Modifier.height(18.dp))
        Text(title, style = MaterialTheme.typography.titleLarge, color = Palette.Text)
        Spacer(Modifier.height(6.dp))
        Text(body, style = MaterialTheme.typography.bodyMedium, color = Palette.TextDim, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        if (action != null) { Spacer(Modifier.height(18.dp)); action() }
    }
}

@Composable
fun PlayButtons(onPlay: () -> Unit, onShuffle: () -> Unit, modifier: Modifier = Modifier) {
    val accent = LocalAccent.current
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Row(
            Modifier.clip(CircleShape).background(accent.primary).combinedClickableSimple(onPlay).padding(horizontal = 22.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Rounded.PlayArrow, null, tint = Palette.Ink)
            Spacer(Modifier.width(6.dp))
            Text("Play", style = MaterialTheme.typography.titleSmall, color = Palette.Ink)
        }
        Row(
            Modifier.clip(CircleShape).background(Palette.SurfaceTop).combinedClickableSimple(onShuffle).padding(horizontal = 22.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Rounded.Shuffle, null, tint = Palette.Text)
            Spacer(Modifier.width(6.dp))
            Text("Shuffle", style = MaterialTheme.typography.titleSmall, color = Palette.Text)
        }
    }
}

