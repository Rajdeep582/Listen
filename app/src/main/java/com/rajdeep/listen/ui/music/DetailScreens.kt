package com.rajdeep.listen.ui.music

import androidx.lifecycle.compose.collectAsStateWithLifecycle

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.History
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import com.rajdeep.listen.data.db.Song
import com.rajdeep.listen.ui.components.albumArt as albumArtUri
import com.rajdeep.listen.ui.AppViewModel
import com.rajdeep.listen.ui.Nav
import com.rajdeep.listen.ui.components.Artwork
import com.rajdeep.listen.ui.components.MediaCard
import com.rajdeep.listen.ui.components.SectionHeader
import com.rajdeep.listen.ui.components.SongRow
import com.rajdeep.listen.ui.LocalBottomInset
import com.rajdeep.listen.ui.components.formatDuration
import com.rajdeep.listen.ui.player.rememberArtColors
import com.rajdeep.listen.ui.theme.Palette

@Composable
private fun DetailHeader(
    art: Any?, seed: String, title: String, subtitle: String, meta: String, nav: Nav,
    circle: Boolean = false, icon: ImageVector = Icons.AutoMirrored.Rounded.QueueMusic,
    actions: @Composable () -> Unit = {}, onSubtitle: (() -> Unit)? = null,
    lookup: com.rajdeep.listen.data.online.ArtResolver.Query? = null,
) {
    val resolved = com.rajdeep.listen.ui.components.rememberArtModel(art, lookup)
    val colors = rememberArtColors(resolved)
    Box(Modifier.fillMaxWidth().background(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(colors.dark, Palette.Ink)))) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(Modifier.fillMaxWidth()) {
                IconButton(onClick = { nav.back() }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back", tint = Palette.Text) }
                Spacer(Modifier.weight(1f)); actions()
            }
            Artwork(
                resolved, seed, Modifier.width(220.dp).aspectRatio(1f).shadow(24.dp, if (circle) CircleShape else RoundedCornerShape(20.dp)),
                if (circle) CircleShape else RoundedCornerShape(20.dp), icon = icon,
            )
            Spacer(Modifier.height(18.dp))
            Text(title, style = MaterialTheme.typography.headlineMedium, color = Palette.Text, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(
                subtitle, style = MaterialTheme.typography.titleMedium, color = colors.vibrant, textAlign = TextAlign.Center,
                modifier = if (onSubtitle != null) Modifier.padding(top = 2.dp).then(Modifier.clickableNoRipple(onSubtitle)) else Modifier.padding(top = 2.dp),
            )
            Text(meta, style = MaterialTheme.typography.bodySmall, color = Palette.TextFaint, modifier = Modifier.padding(top = 4.dp, bottom = 6.dp))
        }
    }
}

@Composable
private fun Modifier.clickableNoRipple(onClick: () -> Unit): Modifier = this.clickable(
    interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }, indication = null, onClick = onClick,
)

private fun meta(songs: List<Song>?) = songs?.let { "${it.size} songs · ${formatDuration(it.sumOf { s -> s.duration })}" } ?: ""

@Composable
fun AlbumScreen(vm: AppViewModel, nav: Nav, albumId: Long) {
    val songs by remember(albumId) { vm.library.dao.albumSongs(albumId) }.collectAsStateWithLifecycle(null)
    val first = songs?.firstOrNull()
    SongsColumn(vm, songs, "Empty album", "", sortable = false, header = {
        DetailHeader(
            albumArtUri(albumId), first?.album ?: "", first?.album ?: "", first?.albumArtist?.ifEmpty { first.artist } ?: first?.artist ?: "",
            listOfNotNull(first?.year?.takeIf { it > 0 }?.toString(), meta(songs)).joinToString(" · "), nav,
            onSubtitle = first?.let { { nav.artist(it.artist) } },
            lookup = first?.let { com.rajdeep.listen.ui.components.albumLookup(it.albumArtist.ifEmpty { it.artist }, it.album) },
        )
    })
}

@Composable
fun ArtistScreen(vm: AppViewModel, nav: Nav, artist: String) {
    val songs by remember(artist) { vm.library.dao.artistSongs(artist) }.collectAsStateWithLifecycle(null)
    val albums by remember(artist) { vm.library.dao.artistAlbums(artist) }.collectAsStateWithLifecycle(emptyList())
    SongsColumn(vm, songs, "No songs", "", header = {
        Column {
            val photo = remember(artist) { mutableStateOf<Any?>(null) }
            androidx.compose.runtime.LaunchedEffect(artist) {
                photo.value = com.rajdeep.listen.data.online.ArtResolver.resolve(com.rajdeep.listen.ui.components.artistLookup(artist))
            }
            DetailHeader(photo.value ?: albums.firstOrNull()?.let { albumArtUri(it.albumId) }, artist, artist, "Artist", meta(songs), nav, circle = true)
            if (albums.size > 1 || (albums.size == 1 && albums[0].songCount > 1)) {
                SectionHeader("Albums")
                LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(14.dp)) {
                    items(albums, key = { it.albumId }) { a ->
                        MediaCard(a.album, if (a.year > 0) "${a.year}" else "${a.songCount} songs", albumArtUri(a.albumId), a.album, lookup = com.rajdeep.listen.ui.components.albumLookup(a.artist, a.album), onClick = { nav.album(a.albumId) }, width = 132.dp)
                    }
                }
                SectionHeader("Songs")
            }
        }
    })
}

@Composable
fun PlaylistScreen(vm: AppViewModel, nav: Nav, id: Long) {
    val songs by remember(id) { vm.library.dao.playlistSongs(id) }.collectAsStateWithLifecycle(null)
    val name by remember(id) { vm.library.dao.playlistName(id) }.collectAsStateWithLifecycle("")
    var rename by remember { mutableStateOf(false) }
    var delete by remember { mutableStateOf(false) }
    var text by remember(name) { mutableStateOf(name.orEmpty()) }
    SongsColumn(vm, songs, "Empty playlist", "Long-press any song → Add to playlist.", sortable = false, playlistId = id, header = {
        DetailHeader(
            songs?.firstOrNull()?.let { albumArtUri(it.albumId) }, name.orEmpty(), name.orEmpty(), "Playlist", meta(songs), nav,
            actions = {
                IconButton(onClick = { rename = true }) { Icon(Icons.Rounded.Edit, "Rename", tint = Palette.TextDim) }
                IconButton(onClick = { delete = true }) { Icon(Icons.Rounded.Delete, "Delete", tint = Palette.TextDim) }
            },
        )
    })
    if (rename) AlertDialog(
        onDismissRequest = { rename = false }, containerColor = Palette.SurfaceHigh,
        title = { Text("Rename playlist", color = Palette.Text) },
        text = { OutlinedTextField(text, { text = it }, singleLine = true) },
        confirmButton = { TextButton(enabled = text.isNotBlank(), onClick = { vm.library.renamePlaylist(id, text); rename = false }) { Text("Save") } },
        dismissButton = { TextButton(onClick = { rename = false }) { Text("Cancel") } },
    )
    if (delete) AlertDialog(
        onDismissRequest = { delete = false }, containerColor = Palette.SurfaceHigh,
        title = { Text("Delete “${name.orEmpty()}”?", color = Palette.Text) },
        text = { Text("Songs stay on your phone; only the playlist is removed.", color = Palette.TextDim) },
        confirmButton = { TextButton(onClick = { vm.library.deletePlaylist(id); delete = false; nav.back() }) { Text("Delete", color = Palette.Danger) } },
        dismissButton = { TextButton(onClick = { delete = false }) { Text("Cancel") } },
    )
}

@Composable
fun FolderSongsScreen(vm: AppViewModel, nav: Nav, folder: String) {
    val songs by remember(folder) { vm.library.dao.folderSongs(folder) }.collectAsStateWithLifecycle(null)
    SongsColumn(vm, songs, "Empty folder", "", header = {
        DetailHeader(null, folder, folder.substringAfterLast('/'), folder, meta(songs), nav, icon = Icons.Rounded.Folder)
    })
}

@Composable
fun SongListScreen(vm: AppViewModel, nav: Nav, kind: String) {
    if (kind == "favourites") {
        FavouritesScreen(vm, nav)
        return
    }
    if (kind == "recent") {
        RecentlyPlayedScreen(vm, nav)
        return
    }
    val (title, flow) = when (kind) {
        "recent" -> "Recently played" to vm.recentlyPlayed
        "most" -> "On repeat" to vm.mostPlayed
        else -> "Recently added" to vm.recentlyAdded
    }
    val songs by flow.collectAsStateWithLifecycle()
    SongsColumn(vm, songs, "Nothing here yet", "Keep listening — this fills up automatically.", sortable = kind == "favourites", header = {
        DetailHeader(songs?.firstOrNull()?.let { albumArtUri(it.albumId) }, title, title, "Auto playlist", meta(songs), nav)
    })
}

@Composable
private fun RecentlyPlayedScreen(vm: AppViewModel, nav: Nav) {
    val context = LocalContext.current
    val local by vm.recentlyPlayed.collectAsStateWithLifecycle()
    val online by vm.onlineHistory.collectAsStateWithLifecycle()
    val onlineFavorites by vm.onlineFavorites.collectAsStateWithLifecycle()
    val songs = local.orEmpty()
    val total = songs.size + online.size

    LazyColumn(
        Modifier.fillMaxWidth().fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = LocalBottomInset.current),
    ) {
        item(key = "recently-played-header") {
            DetailHeader(
                songs.firstOrNull()?.let { albumArtUri(it.albumId) } ?: online.firstOrNull()?.artwork,
                "recent", "Recently played", "Your listening history", "$total songs", nav,
                icon = Icons.Rounded.History,
            )
        }
        if (total == 0) {
            item { com.rajdeep.listen.ui.components.EmptyState(Icons.Rounded.History, "Nothing here yet", "Keep listening — your recent songs will appear here.") }
        }
        items(songs.size, key = { "local-recent-${songs[it].id}" }) { i ->
            val song = songs[i]
            SongRow(song, false, onClick = { vm.player.playSongs(songs, i) }, onMore = { vm.menuSong.value = song })
        }
        items(online.size, key = { "online-recent-${online[it].songId}" }) { i ->
            val item = online[i]
            val track = com.rajdeep.listen.data.online.OnlineTrack(
                key = item.songId, title = item.title, artist = item.artist, album = item.album,
                artwork = item.artwork.takeIf { it.isNotBlank() }, durationMs = 0L, streamUrl = "",
                isPreview = false, source = item.songId.substringBefore(':').ifBlank { "Online" },
            )
            OnlineRow(
                null, track,
                onClick = {
                    vm.playFavorite(com.rajdeep.listen.data.AccountFavorite(item.songId, item.title, item.artist, item.album, item.artwork)) { message ->
                        android.widget.Toast.makeText(context, message, android.widget.Toast.LENGTH_SHORT).show()
                    }
                },
                onFavorite = { vm.toggleOnlineFavorite(track) },
                isFavorite = onlineFavorites.any { it.songId == item.songId },
                showBadge = false,
            )
        }
    }
}

@Composable
fun FavouritesScreen(vm: AppViewModel, nav: Nav) {
    val context = LocalContext.current
    val local by vm.favourites.collectAsStateWithLifecycle()
    val online by vm.onlineFavorites.collectAsStateWithLifecycle()
    val songs = local.orEmpty()
    val total = songs.size + online.size
    LazyColumn(Modifier.fillMaxWidth().fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = LocalBottomInset.current)) {
        item(key = "favourites-header") {
            DetailHeader(
                songs.firstOrNull()?.let { albumArtUri(it.albumId) } ?: online.firstOrNull()?.artwork,
                "favourites", "Favourites", "Your saved music", "$total songs", nav,
            )
        }
        if (total == 0) {
            item { com.rajdeep.listen.ui.components.EmptyState(Icons.Rounded.Favorite, "No favourites yet", "Tap the heart on a song to save it here.") }
        }
        items(songs.size, key = { "local-favourite-${songs[it].id}" }) { i ->
            val song = songs[i]
            SongRow(song, false, onClick = { vm.player.playSongs(songs, i) }, onMore = { vm.menuSong.value = song })
        }
        items(online.size, key = { "online-favourite-${online[it].songId}" }) { i ->
            val favorite = online[i]
            val track = com.rajdeep.listen.data.online.OnlineTrack(
                key = favorite.songId, title = favorite.title, artist = favorite.artist, album = favorite.album,
                artwork = favorite.artwork.takeIf { it.isNotBlank() }, durationMs = 0L, streamUrl = "",
                isPreview = false, source = favorite.songId.substringBefore(':').ifBlank { "Online" },
            )
            OnlineRow(
                null, track,
                onClick = { vm.playFavorite(favorite) { message ->
                    android.widget.Toast.makeText(context, message, android.widget.Toast.LENGTH_SHORT).show()
                } },
                onFavorite = { vm.toggleOnlineFavorite(track) }, isFavorite = true, showBadge = false,
            )
        }
    }
}

