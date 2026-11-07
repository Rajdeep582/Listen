package com.rajdeep.listen.ui.music

import androidx.lifecycle.compose.collectAsStateWithLifecycle

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Radio
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import com.rajdeep.listen.data.online.OnlineTrack
import com.rajdeep.listen.ui.AppViewModel
import com.rajdeep.listen.ui.LocalBottomInset
import com.rajdeep.listen.ui.Remote
import com.rajdeep.listen.ui.components.Artwork
import com.rajdeep.listen.ui.components.Badge
import com.rajdeep.listen.ui.components.EmptyState
import com.rajdeep.listen.ui.components.MediaCard
import com.rajdeep.listen.ui.components.SectionHeader
import com.rajdeep.listen.ui.components.formatDuration
import com.rajdeep.listen.ui.theme.Palette
import com.rajdeep.listen.video.SelectChip

private val genres = listOf(null to "All", "Electronic" to "Electronic", "Hip-Hop/Rap" to "Hip-Hop", "Pop" to "Pop", "Lo-Fi" to "Lo-Fi",
    "Alternative" to "Alternative", "Rock" to "Rock", "R&B/Soul" to "R&B", "Ambient" to "Ambient", "Acoustic" to "Acoustic",
    "Jazz" to "Jazz", "Classical" to "Classical", "World" to "World")

private val radioFilters = listOf("IN" to "India", null to "Worldwide", "tag:bollywood" to "Bollywood", "tag:hindi" to "Hindi",
    "tag:bengali" to "Bengali", "tag:lofi" to "Lo-fi", "tag:pop" to "Pop", "tag:classical" to "Classical", "tag:jazz" to "Jazz", "tag:news" to "News")

@Composable
fun ExploreScreen(vm: AppViewModel) {
    val onlineFavorites by vm.onlineFavorites.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val trending by vm.trending.collectAsStateWithLifecycle()
    val charts by vm.charts.collectAsStateWithLifecycle()
    var genre by rememberSaveable { mutableStateOf<String?>(null) }
    val player = vm.player
    LaunchedEffect(genre, settings.onlineEnabled) { if (settings.onlineEnabled) vm.loadTrending(genre) }
    LaunchedEffect(settings.onlineEnabled) { if (settings.onlineEnabled) vm.loadCharts() }
    val jamendo by vm.jamendo.collectAsStateWithLifecycle()
    val radio by vm.radio.collectAsStateWithLifecycle()
    var radioKey by rememberSaveable { mutableStateOf<String?>("IN") }
    LaunchedEffect(genre, settings.onlineEnabled) { if (settings.onlineEnabled) vm.loadJamendo(genre?.lowercase()?.substringBefore('/')?.replace("-", "")) }
    LaunchedEffect(radioKey, settings.onlineEnabled) { if (settings.onlineEnabled) vm.loadRadio(radioKey) }

    if (!settings.onlineEnabled) {
        EmptyState(Icons.Rounded.CloudOff, "Online sources are off", "Turn on online sources in Settings to explore free music from Audius and Deezer charts.")
        return
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = LocalBottomInset.current)) {
        item {
            Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
                Text("Explore", style = MaterialTheme.typography.displaySmall, color = Palette.Text)
                Text("Free, legal streaming. Requires internet.", style = MaterialTheme.typography.bodyMedium, color = Palette.TextDim)
            }
        }
        item {
            LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(genres, key = { it.second }) { (g, label) -> SelectChip(label, genre == g) { genre = g } }
            }
        }
        item { SectionHeader("Trending this week", "Audius · full tracks") }
        when (val t = trending) {
            Remote.Loading -> item { Text("Loading…", color = Palette.TextDim, modifier = Modifier.padding(20.dp)) }
            is Remote.Failed -> item { OfflineNote(t.message) { vm.loadTrending(genre, force = true) } }
            is Remote.Ready -> {
                if (t.data.isEmpty()) item { Text("Nothing here right now.", color = Palette.TextDim, modifier = Modifier.padding(20.dp)) }
                items(t.data.size, key = { t.data[it].key }) { i ->
                    val track = t.data[i]
                    OnlineRow(i + 1, track, { player.playOnline(t.data, i) },
                        onFavorite = { vm.toggleOnlineFavorite(track) }, isFavorite = onlineFavorites.any { it.songId == track.key })
                }
            }
        }
        item { SectionHeader("Live radio", "Radio Browser · 40k+ stations") }
        item {
            LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(radioFilters, key = { it.second }) { (k, label) -> SelectChip(label, radioKey == k) { radioKey = k } }
            }
        }
        when (val r = radio) {
            Remote.Loading -> item { Text("Loading…", color = Palette.TextDim, modifier = Modifier.padding(20.dp)) }
            is Remote.Failed -> item { OfflineNote(r.message) { vm.loadRadio(radioKey, force = true) } }
            is Remote.Ready -> item {
                LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    items(r.data, key = { it.key }) { st -> MediaCard(st.title, st.artist, st.artwork, st.key, onClick = { player.playOnline(listOf(st), 0) }, width = 120.dp, badge = "Live") }
                }
            }
        }
        item { SectionHeader("Popular on Jamendo", "Creative Commons · full tracks") }
        when (val j = jamendo) {
            Remote.Loading -> item { Text("Loading…", color = Palette.TextDim, modifier = Modifier.padding(20.dp)) }
            is Remote.Failed -> item { OfflineNote(j.message) { vm.loadJamendo(force = true) } }
            is Remote.Ready -> items(j.data.size.coerceAtMost(20), key = { j.data[it].key }) { i ->
                val track = j.data[i]
                OnlineRow(i + 1, track, { player.playOnline(j.data, i) },
                    onFavorite = { vm.toggleOnlineFavorite(track) }, isFavorite = onlineFavorites.any { it.songId == track.key })
            }
        }
        item { SectionHeader("Global chart", "Deezer · 30-second previews") }
        when (val c = charts) {
            Remote.Loading -> item { Text("Loading…", color = Palette.TextDim, modifier = Modifier.padding(20.dp)) }
            is Remote.Failed -> item { OfflineNote(c.message) { vm.loadCharts(force = true) } }
            is Remote.Ready -> item {
                LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    items(c.data, key = { it.key }) { tr -> MediaCard(
                        tr.title, tr.artist, tr.artwork, tr.key,
                        onClick = { player.playOnline(c.data, c.data.indexOf(tr)) }, badge = "Preview",
                        isFavorite = onlineFavorites.any { it.songId == tr.key }, onFavoriteClick = { vm.toggleOnlineFavorite(tr) },
                    ) }
                }
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
fun OnlineRow(
    rank: Int?, t: OnlineTrack, onClick: () -> Unit,
    onFavorite: (() -> Boolean)? = null, isFavorite: Boolean = false, showBadge: Boolean = true,
) {
    var favoriteMessage by remember(t.key) { mutableStateOf<String?>(null) }
    LaunchedEffect(favoriteMessage) { if (favoriteMessage != null) { delay(1400); favoriteMessage = null } }
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        if (rank != null) { Text("$rank", style = MaterialTheme.typography.titleMedium, color = Palette.TextFaint, modifier = Modifier.width(30.dp)) }
        Artwork(t.artwork, t.key, Modifier.size(50.dp), RoundedCornerShape(10.dp), lookup = if (t.isLive) null else com.rajdeep.listen.ui.components.songTrackLookup(t.artist, t.title), icon = if (t.isLive) Icons.Rounded.Radio else Icons.Rounded.MusicNote)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(t.title, style = MaterialTheme.typography.titleMedium, color = Palette.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(favoriteMessage ?: if (t.isLive) t.artist else "${t.artist} · ${formatDuration(t.durationMs)}", style = MaterialTheme.typography.bodyMedium, color = if (favoriteMessage != null) com.rajdeep.listen.ui.theme.LocalAccent.current.primary else Palette.TextDim, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (onFavorite != null && !t.isLive) IconButton(onClick = {
            favoriteMessage = if (!onFavorite()) "Sign in to save favourites" else if (isFavorite) "Removed from favourites" else "Added to favourites"
        }) {
            Icon(if (isFavorite) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                if (isFavorite) "Remove from favourites" else "Add to favourites",
                tint = if (isFavorite) com.rajdeep.listen.ui.theme.LocalAccent.current.primary else Palette.TextDim)
        }
        if (showBadge) Badge(when { t.isLive -> "Live"; t.isPreview -> "Preview"; else -> t.source })
    }
}
