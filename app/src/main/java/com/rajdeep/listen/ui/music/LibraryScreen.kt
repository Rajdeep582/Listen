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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Sort
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.rajdeep.listen.data.db.Song
import com.rajdeep.listen.ui.components.albumArt as albumArtUri
import com.rajdeep.listen.ui.AppViewModel
import com.rajdeep.listen.ui.LocalBottomInset
import com.rajdeep.listen.ui.Nav
import com.rajdeep.listen.ui.components.Artwork
import com.rajdeep.listen.ui.components.EmptyState
import com.rajdeep.listen.ui.components.MediaCard
import com.rajdeep.listen.ui.components.PlayButtons
import com.rajdeep.listen.ui.components.SongRow
import com.rajdeep.listen.ui.theme.LocalAccent
import com.rajdeep.listen.ui.theme.Palette
import com.rajdeep.listen.video.SelectChip
import kotlinx.coroutines.launch

private enum class LibTab(val label: String) { SONGS("Songs"), ALBUMS("Albums"), ARTISTS("Artists"), PLAYLISTS("Playlists"), FOLDERS("Folders"), FAVOURITES("Favourites"), RECENT("Recent") }
enum class SongSort(val label: String) { TITLE("Title"), ARTIST("Artist"), ADDED("Recently added"), DURATION("Duration"), PLAYS("Most played") }

fun List<Song>.sortedBy(s: SongSort): List<Song> = when (s) {
    SongSort.TITLE -> this
    SongSort.ARTIST -> sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it: Song -> it.artist }.thenBy { it.album })
    SongSort.ADDED -> sortedByDescending { it.dateAdded }
    SongSort.DURATION -> sortedByDescending { it.duration }
    SongSort.PLAYS -> sortedByDescending { it.playCount }
}

@Composable
fun LibraryScreen(vm: AppViewModel, nav: Nav) {
    var tab by rememberSaveable { mutableStateOf(LibTab.SONGS) }
    Column(Modifier.fillMaxSize()) {
        Text("Library", style = MaterialTheme.typography.displaySmall, color = Palette.Text, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
        LazyRow(contentPadding = PaddingValues(horizontal = 20.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(LibTab.entries) { t -> SelectChip(t.label, t == tab) { tab = t } }
        }
        when (tab) {
            LibTab.SONGS -> AllSongs(vm)
            LibTab.ALBUMS -> AlbumsGrid(vm, nav)
            LibTab.ARTISTS -> ArtistsList(vm, nav)
            LibTab.PLAYLISTS -> PlaylistsList(vm, nav)
            LibTab.FOLDERS -> FoldersList(vm, nav)
            LibTab.FAVOURITES -> FavouritesScreen(vm, nav)
            LibTab.RECENT -> { val r by vm.recentlyPlayed.collectAsStateWithLifecycle(); SongsColumn(vm, r, "Nothing played yet", "Your listening history shows up here.", sortable = false) }
        }
    }
}

@Composable
private fun AllSongs(vm: AppViewModel) {
    val songs by vm.songs.collectAsStateWithLifecycle()
    SongsColumn(vm, songs, "No songs", "Music on this phone will appear here.")
}

@Composable
fun SongsColumn(vm: AppViewModel, songs: List<Song>?, emptyTitle: String, emptyBody: String, sortable: Boolean = true, playlistId: Long? = null, header: (@Composable () -> Unit)? = null) {
    val state by vm.player.state.collectAsStateWithLifecycle()
    var sort by rememberSaveable { mutableStateOf(SongSort.TITLE) }
    var sortMenu by remember { mutableStateOf(false) }
    val list = remember(songs, sort) { songs?.let { if (sortable) it.sortedBy(sort) else it } }
    val curId = state.current?.localId
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = LocalBottomInset.current)) {
        header?.let { h -> item(key = "header") { h() } }
        if (list == null) return@LazyColumn
        if (list.isEmpty()) { item { EmptyState(Icons.Rounded.MusicNote, emptyTitle, emptyBody) }; return@LazyColumn }
        item(key = "actions") {
            Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 8.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                PlayButtons(onPlay = { vm.player.playSongs(list, 0) }, onShuffle = { vm.player.playSongs(list, 0, shuffle = true) }, modifier = Modifier.weight(1f))
                if (sortable) Box {
                    IconButton(onClick = { sortMenu = true }) { Icon(Icons.Rounded.Sort, "Sort", tint = Palette.TextDim) }
                    DropdownMenu(sortMenu, onDismissRequest = { sortMenu = false }) {
                        SongSort.entries.forEach { s -> DropdownMenuItem(text = { Text(s.label, color = if (s == sort) LocalAccent.current.primary else Palette.Text) }, onClick = { sort = s; sortMenu = false }) }
                    }
                }
            }
            Text("${list.size} songs · ${com.rajdeep.listen.ui.components.formatDuration(list.sumOf { it.duration })}", style = MaterialTheme.typography.bodySmall, color = Palette.TextFaint, modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
        }
        items(list.size, key = { list[it].id }, contentType = { "song" }) { i ->
            val s = list[i]
            SongRow(s, s.id == curId, onClick = { vm.player.playSongs(list, i) }, onMore = { vm.menuPlaylistContext.value = playlistId; vm.menuSong.value = s })
        }
    }
}

@Composable
private fun AlbumsGrid(vm: AppViewModel, nav: Nav) {
    val albums by vm.albums.collectAsStateWithLifecycle()
    val list = albums ?: return
    if (list.isEmpty()) { EmptyState(Icons.Rounded.MusicNote, "No albums", "Albums are grouped from your music tags."); return }
    LazyVerticalGrid(
        GridCells.Adaptive(150.dp), Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = LocalBottomInset.current),
        horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        items(list, key = { it.albumId }) { a ->
            MediaCard(a.album, "${a.artist} · ${a.songCount}", albumArtUri(a.albumId), a.album, lookup = com.rajdeep.listen.ui.components.albumLookup(a.artist, a.album), onClick = { nav.album(a.albumId) }, width = 400.dp)
        }
    }
}

@Composable
private fun ArtistsList(vm: AppViewModel, nav: Nav) {
    val artists by vm.artists.collectAsStateWithLifecycle()
    val list = artists ?: return
    if (list.isEmpty()) { EmptyState(Icons.Rounded.Person, "No artists", "Artists come from your music tags."); return }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = LocalBottomInset.current)) {
        items(list, key = { it.artist }) { a ->
            ListRow(albumArtUri(a.albumId), a.artist, a.artist, "${a.albumCount} albums · ${a.songCount} songs", circle = true, lookup = com.rajdeep.listen.ui.components.artistLookup(a.artist), preferLookup = true) { nav.artist(a.artist) }
        }
    }
}

@Composable
private fun PlaylistsList(vm: AppViewModel, nav: Nav) {
    val playlists by vm.playlists.collectAsStateWithLifecycle()
    var creating by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = LocalBottomInset.current)) {
        item {
            Row(
                Modifier.fillMaxWidth().clickable { creating = true }.padding(horizontal = 20.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(56.dp).clip(RoundedCornerShape(12.dp)).background(Palette.SurfaceTop), contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.Add, null, tint = LocalAccent.current.primary)
                }
                Spacer(Modifier.width(14.dp))
                Text("New playlist", style = MaterialTheme.typography.titleMedium, color = Palette.Text)
            }
        }
        ListRowItem(Icons.Rounded.Favorite, "Favourites", "Liked songs") { nav.list("favourites") }
        ListRowItem(Icons.Rounded.History, "Recently played", "Auto playlist") { nav.list("recent") }
        items(playlists.orEmpty(), key = { it.id }) { p ->
            ListRow(p.coverAlbumId?.let(::albumArtUri), p.name, p.name, "${p.songCount} songs", icon = Icons.AutoMirrored.Rounded.QueueMusic) { nav.playlist(p.id) }
        }
    }
    if (creating) {
        AlertDialog(
            onDismissRequest = { creating = false }, containerColor = Palette.SurfaceHigh,
            title = { Text("New playlist", color = Palette.Text) },
            text = { OutlinedTextField(name, { name = it }, singleLine = true, placeholder = { Text("Name") }) },
            confirmButton = {
                TextButton(enabled = name.isNotBlank(), onClick = {
                    scope.launch { val id = vm.library.createPlaylist(name); creating = false; name = ""; nav.playlist(id) }
                }) { Text("Create") }
            },
            dismissButton = { TextButton(onClick = { creating = false }) { Text("Cancel") } },
        )
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.ListRowItem(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, sub: String, onClick: () -> Unit) {
    item(key = title) { ListRow(null, title, title, sub, icon = icon, onClick = onClick) }
}

@Composable
private fun FoldersList(vm: AppViewModel, nav: Nav) {
    val folders by vm.songFolders.collectAsStateWithLifecycle()
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = LocalBottomInset.current)) {
        items(folders.orEmpty(), key = { it.folder }) { f ->
            ListRow(null, f.folder.substringAfterLast('/'), f.folder, "${f.count} songs · ${f.folder}", icon = Icons.Rounded.Folder) { nav.folder(f.folder) }
        }
    }
}

@Composable
fun ListRow(art: Any?, title: String, seed: String, subtitle: String, circle: Boolean = false, icon: androidx.compose.ui.graphics.vector.ImageVector = Icons.Rounded.MusicNote, lookup: com.rajdeep.listen.data.online.ArtResolver.Query? = null, preferLookup: Boolean = false, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Artwork(art, seed, Modifier.size(56.dp), if (circle) androidx.compose.foundation.shape.CircleShape else RoundedCornerShape(12.dp), icon = icon, lookup = lookup, preferLookup = preferLookup)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = Palette.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = Palette.TextDim, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

