package com.rajdeep.listen.ui.music

import androidx.lifecycle.compose.collectAsStateWithLifecycle

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.NewReleases
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Whatshot
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.rajdeep.listen.data.db.Song
import com.rajdeep.listen.ui.components.albumArt as albumArtUri
import com.rajdeep.listen.ui.AppViewModel
import com.rajdeep.listen.ui.LocalBottomInset
import com.rajdeep.listen.ui.Nav
import com.rajdeep.listen.ui.Remote
import com.rajdeep.listen.ui.components.Artwork
import com.rajdeep.listen.ui.components.CardRow
import com.rajdeep.listen.ui.components.EmptyState
import com.rajdeep.listen.ui.components.MediaCard
import com.rajdeep.listen.ui.components.SectionHeader
import com.rajdeep.listen.ui.components.seedBrush
import com.rajdeep.listen.ui.components.songArt
import com.rajdeep.listen.ui.theme.LocalAccent
import com.rajdeep.listen.ui.theme.Palette
import java.util.Calendar

private fun greeting(): String = when (Calendar.getInstance().get(Calendar.HOUR_OF_DAY)) {
    in 5..11 -> "Good morning"
    in 12..16 -> "Good afternoon"
    in 17..21 -> "Good evening"
    else -> "Late night"
}

@Composable
fun HomeScreen(vm: AppViewModel, nav: Nav) {
    val songs by vm.songs.collectAsStateWithLifecycle()
    val recent by vm.recentlyPlayed.collectAsStateWithLifecycle()
    val most by vm.mostPlayed.collectAsStateWithLifecycle()
    val added by vm.recentlyAdded.collectAsStateWithLifecycle()
    val topArtists by vm.topArtists.collectAsStateWithLifecycle()
    val albums by vm.albums.collectAsStateWithLifecycle()
    val playlists by vm.playlists.collectAsStateWithLifecycle()
    val favs by vm.favourites.collectAsStateWithLifecycle()
    val onlineFavorites by vm.onlineFavorites.collectAsStateWithLifecycle()
    val rediscover by vm.rediscover.collectAsStateWithLifecycle()
    val discover by vm.discover.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val trending by vm.trending.collectAsStateWithLifecycle()
    val scanning by vm.scanning.collectAsStateWithLifecycle()
    val player = vm.player

    LaunchedEffect(songs?.size) { if (!songs.isNullOrEmpty()) vm.refreshMixes() }
    LaunchedEffect(settings.onlineEnabled) { if (settings.onlineEnabled) vm.loadTrending() }

    val bottom = LocalBottomInset.current
    if (songs?.isEmpty() == true) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            EmptyState(
                Icons.Rounded.LibraryMusic, if (scanning) "Scanning your music…" else "No music found",
                "Copy songs into Music or Download on this phone. Listen picks them up automatically.",
            ) { com.rajdeep.listen.video.PillButton("Rescan") { vm.rescan() } }
        }
        return
    }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = bottom)) {
        item(key = "hero") {
            Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
                Text(greeting(), style = MaterialTheme.typography.displaySmall, color = Palette.Text)
                Text("${songs?.size ?: 0} songs · ${albums?.size ?: 0} albums in your library", style = MaterialTheme.typography.bodyMedium, color = Palette.TextDim)
            }
        }
        // Quick tiles
        item(key = "tiles") {
            val tiles = buildList {
                add(Tile("Favourites", Icons.Rounded.Favorite, "fav", (favs?.size ?: 0) + onlineFavorites.size) { nav.list("favourites") })
                add(Tile("Recently played", Icons.Rounded.History, "recent", recent?.size ?: 0) { nav.list("recent") })
                add(Tile("On repeat", Icons.Rounded.Whatshot, "most", most?.size ?: 0) { nav.list("most") })
                add(Tile("Fresh adds", Icons.Rounded.NewReleases, "added", added?.size ?: 0) { nav.list("added") })
                playlists.orEmpty().take(2).forEach { p -> add(Tile(p.name, null, "pl${p.id}", p.songCount, p.coverAlbumId?.let(::albumArtUri)) { nav.playlist(p.id) }) }
            }
            Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                tiles.chunked(2).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        row.forEach { QuickTile(it, Modifier.weight(1f)) }
                        if (row.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
            }
        }
        // Continue listening
        val cont = recent.orEmpty()
        if (cont.isNotEmpty()) {
            item(key = "cont") {
                SectionHeader("Continue listening", "Jump back in")
                SongCards(cont.take(15), onPlay = { i -> player.playSongs(cont, i) }, onLong = { vm.menuSong.value = it })
            }
        }
        // Mixes generated from listening data
        item(key = "mixes") {
            val mixes = remember(most, discover, rediscover, added, topArtists, songs) { buildList {
                if (!most.isNullOrEmpty()) add(Mix("On Repeat", "Your most played", most!!))
                if (discover.isNotEmpty()) add(Mix("Deep Cuts", "Unplayed from artists you love", discover))
                if (rediscover.isNotEmpty()) add(Mix("Rediscover", "Songs you haven't heard yet", rediscover))
                if (!added.isNullOrEmpty()) add(Mix("Fresh Finds", "Latest in your library", added!!))
                topArtists.orEmpty().take(4).forEach { a ->
                    val list = songs.orEmpty().filter { it.artist == a.artist }
                    if (list.size >= 3) add(Mix("${a.artist} Mix", "${list.size} songs", list.shuffled().take(50), a.albumId))
                }
                if (isEmpty() && !songs.isNullOrEmpty()) add(Mix("Shuffle All", "Your whole library", songs!!.shuffled().take(100)))
            } }
            if (mixes.isNotEmpty()) {
                SectionHeader("Made for you", "Built from your listening", action = "Refresh", onAction = { vm.refreshMixes() })
                CardRow(mixes, key = { it.title }) { m -> MixCard(m) { player.playSongs(m.songs, 0) } }
            }
        }
        if (!topArtists.isNullOrEmpty()) {
            item(key = "artists") {
                SectionHeader("Your top artists")
                CardRow(topArtists!!, key = { it.artist }) { a ->
                    MediaCard(a.artist, "${a.songCount} songs", albumArtUri(a.albumId), a.artist, lookup = com.rajdeep.listen.ui.components.artistLookup(a.artist), preferLookup = true, onClick = { nav.artist(a.artist) }, width = 120.dp, circle = true)
                }
            }
        }
        if (!albums.isNullOrEmpty()) {
            item(key = "albums") {
                val featured = remember(albums) { albums!!.shuffled().take(14) }
                SectionHeader("Albums to revisit", action = "All", onAction = { nav.controller.navigate("library") })
                CardRow(featured, key = { it.albumId }) { a ->
                    MediaCard(a.album, a.artist, albumArtUri(a.albumId), a.album, lookup = com.rajdeep.listen.ui.components.albumLookup(a.artist, a.album), onClick = { nav.album(a.albumId) })
                }
            }
        }
        if (!added.isNullOrEmpty()) {
            item(key = "added") {
                SectionHeader("Recently added")
                SongCards(added!!.take(15), onPlay = { i -> player.playSongs(added!!, i) }, onLong = { vm.menuSong.value = it })
            }
        }
        if (settings.onlineEnabled) {
            item(key = "online") {
                SectionHeader("Trending on Audius", "Online · free full tracks", action = "Explore", onAction = { nav.controller.navigate("explore") })
                when (val t = trending) {
                    is Remote.Ready -> CardRow(t.data.take(16), key = { it.key }) { tr ->
                        MediaCard(
                            tr.title, tr.artist, tr.artwork, tr.key,
                            onClick = { player.playOnline(t.data, t.data.indexOf(tr)) }, badge = "Online",
                            isFavorite = onlineFavorites.any { it.songId == tr.key }, onFavoriteClick = { vm.toggleOnlineFavorite(tr) },
                        )
                    }
                    is Remote.Failed -> OfflineNote(t.message) { vm.loadTrending(force = true) }
                    Remote.Loading -> Text("Loading…", color = Palette.TextDim, modifier = Modifier.padding(horizontal = 20.dp))
                }
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

private data class Tile(val title: String, val icon: ImageVector?, val seed: String, val count: Int, val art: Any? = null, val onClick: () -> Unit)
private data class Mix(val title: String, val subtitle: String, val songs: List<Song>, val albumId: Long? = null)

@Composable
private fun QuickTile(t: Tile, modifier: Modifier) {
    Row(
        modifier.height(56.dp).clip(RoundedCornerShape(12.dp)).background(Palette.SurfaceHigh).clickable(onClick = t.onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (t.icon != null) Box(Modifier.size(56.dp).background(seedBrush(t.seed)), contentAlignment = Alignment.Center) { Icon(t.icon, null, tint = Color.White) }
        else Artwork(t.art, t.title, Modifier.size(56.dp), RoundedCornerShape(0.dp))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(t.title, style = MaterialTheme.typography.titleSmall, color = Palette.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("${t.count}", style = MaterialTheme.typography.bodySmall, color = Palette.TextFaint)
        }
    }
}

@Composable
private fun MixCard(m: Mix, onClick: () -> Unit) {
    val accent = LocalAccent.current
    Column(Modifier.width(164.dp).clickable(onClick = onClick)) {
        Box(Modifier.size(164.dp).clip(RoundedCornerShape(18.dp))) {
            // 2×2 collage of distinct album covers
            val covers = m.songs.distinctBy { it.albumId }.take(4)
            if (covers.size >= 4) Column {
                covers.chunked(2).forEach { row ->
                    Row { row.forEach { s -> Artwork(songArt(s), s.album, Modifier.size(82.dp), RoundedCornerShape(0.dp), lookup = com.rajdeep.listen.ui.components.songLookup(s)) } }
                }
            } else covers.firstOrNull()?.let { s -> Artwork(songArt(s), s.album, Modifier.fillMaxSize(), RoundedCornerShape(0.dp), lookup = com.rajdeep.listen.ui.components.songLookup(s)) }
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.75f)))))
            Text(
                m.title, style = MaterialTheme.typography.titleLarge, color = Color.White, maxLines = 2,
                modifier = Modifier.align(Alignment.BottomStart).padding(12.dp),
            )
            Box(
                Modifier.align(Alignment.TopEnd).padding(10.dp).size(34.dp).clip(CircleShape).background(accent.primary),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Rounded.PlayArrow, null, tint = Palette.Ink, modifier = Modifier.size(20.dp)) }
        }
        Spacer(Modifier.height(8.dp))
        Text(m.subtitle, style = MaterialTheme.typography.bodySmall, color = Palette.TextDim, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
fun SongCards(songs: List<Song>, onPlay: (Int) -> Unit, onLong: (Song) -> Unit) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val app = ctx.applicationContext as com.rajdeep.listen.ListenApp
    LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        items(songs.size, key = { songs[it].id }) { i ->
            val s = songs[i]
            MediaCard(
                s.title, s.artist, songArt(s), s.album,
                lookup = com.rajdeep.listen.ui.components.songLookup(s),
                onClick = { onPlay(i) },
                width = 136.dp,
                onLongClick = { onLong(s) },
                isFavorite = s.isFavorite,
                onFavoriteClick = { app.container.library.toggleFavorite(s.id).let { true } },
            )
        }
    }
}

@Composable
fun OfflineNote(message: String, onRetry: () -> Unit) {
    Row(
        Modifier.padding(horizontal = 20.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Palette.SurfaceHigh).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(message, style = MaterialTheme.typography.bodyMedium, color = Palette.TextDim, modifier = Modifier.weight(1f))
        Icon(Icons.Rounded.Refresh, "Retry", tint = LocalAccent.current.primary, modifier = Modifier.clip(CircleShape).clickable(onClick = onRetry).padding(6.dp))
    }
}

