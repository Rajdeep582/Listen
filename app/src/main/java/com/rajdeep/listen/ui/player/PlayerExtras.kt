package com.rajdeep.listen.ui.player

import androidx.lifecycle.compose.collectAsStateWithLifecycle

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.automirrored.rounded.PlaylistPlay
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.automirrored.rounded.TextSnippet
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.DragHandle
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PlaylistRemove
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.TextSnippet
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.rajdeep.listen.data.Lyrics
import com.rajdeep.listen.playback.NowPlaying
import com.rajdeep.listen.playback.PlayerUiState
import com.rajdeep.listen.ui.AppViewModel
import com.rajdeep.listen.ui.Nav
import com.rajdeep.listen.ui.components.Artwork
import com.rajdeep.listen.ui.components.artLookup
import com.rajdeep.listen.ui.components.EmptyState
import com.rajdeep.listen.ui.components.formatDuration
import com.rajdeep.listen.ui.components.songArt
import com.rajdeep.listen.ui.theme.LocalAccent
import com.rajdeep.listen.ui.theme.Palette
import kotlinx.coroutines.launch

// ---------------- Lyrics ----------------

@Composable
fun LyricsPane(vm: AppViewModel, np: NowPlaying) {
    var lyrics by remember(np.mediaId) { mutableStateOf<Lyrics?>(null) }
    LaunchedEffect(np.mediaId) {
        lyrics = null
        lyrics = vm.library.lyrics(np.mediaId, np.title, np.artist, np.album, if (np.durationHint > 0) np.durationHint else vm.player.state.value.duration)
    }
    Box(Modifier.fillMaxSize().padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
        when (val l = lyrics) {
            null -> Text("Finding lyrics…", style = MaterialTheme.typography.titleMedium, color = Palette.TextDim)
            Lyrics.None -> EmptyState(Icons.AutoMirrored.Rounded.TextSnippet, "No lyrics found", "No lyrics available for this track yet.")
            Lyrics.Offline -> EmptyState(Icons.Rounded.CloudOff, "You're offline", "Lyrics are fetched once, then work offline.")
            is Lyrics.Plain -> Text(
                l.text, style = MaterialTheme.typography.titleLarge, color = Palette.Text.copy(alpha = 0.9f),
                modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(vertical = 16.dp),
            )
            is Lyrics.Synced -> SyncedLyrics(vm, l)
        }
    }
}

@Composable
private fun SyncedLyrics(vm: AppViewModel, l: Lyrics.Synced) {
    val pos by remember { vm.player.positionFlow(150) }.collectAsStateWithLifecycle(0L)
    val current by remember(l) { derivedStateOf { (l.lines.indexOfLast { it.timeMs <= pos + 250 }).coerceAtLeast(0) } }
    val listState = rememberLazyListState()
    LaunchedEffect(current) { listState.animateScrollToItem((current - 2).coerceAtLeast(0)) }
    LazyColumn(state = listState, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 80.dp)) {
        itemsIndexed(l.lines, key = { i, _ -> i }) { i, line ->
            val active = i == current
            val color by animateColorAsState(if (active) Palette.Text else Palette.Text.copy(alpha = if (i < current) 0.35f else 0.45f), label = "lc")
            val s by animateFloatAsState(if (active) 1f else 0.94f, label = "ls")
            Text(
                line.text.ifEmpty { "♪" }, style = MaterialTheme.typography.headlineSmall, color = color,
                modifier = Modifier.fillMaxWidth().graphicsLayer { scaleX = s; scaleY = s; transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0f, 0.5f) }
                    .clip(RoundedCornerShape(10.dp)).clickable { vm.player.seekTo(line.timeMs) }.padding(vertical = 9.dp),
            )
        }
    }
}

// ---------------- Queue ----------------

@Composable
fun QueuePane(vm: AppViewModel, state: PlayerUiState) {
    val accent = LocalAccent.current.primary
    val listState = rememberLazyListState()
    val curPos = state.queue.indexOfFirst { it.index == state.currentIndex }.coerceAtLeast(0)
    LaunchedEffect(Unit) { listState.scrollToItem((curPos - 1).coerceAtLeast(0)) }
    Column(Modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 8.dp)) {
            Text("Up next", style = MaterialTheme.typography.headlineSmall, color = Palette.Text, modifier = Modifier.weight(1f))
            Text("${state.queue.size} tracks", style = MaterialTheme.typography.bodyMedium, color = Palette.TextDim)
            TextButton(onClick = { vm.player.clearQueue() }) { Text("Clear", color = accent) }
        }
        if (state.shuffle) Text("Shuffle is on — showing play order. Turn shuffle off to reorder.", style = MaterialTheme.typography.bodySmall, color = Palette.TextFaint)
        LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
            itemsIndexed(state.queue, key = { _, e -> "${e.index}:${e.item.mediaId}" }) { _, e ->
                val isCur = e.index == state.currentIndex
                var dy by remember { mutableFloatStateOf(0f) }
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(if (isCur) accent.copy(alpha = 0.14f) else Color.Transparent)
                        .clickable { vm.player.skipTo(e.index) }.padding(horizontal = 6.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Artwork(e.item.artwork, e.item.album, Modifier.size(42.dp), RoundedCornerShape(8.dp), lookup = e.item.artLookup())
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(e.item.title, style = MaterialTheme.typography.titleSmall, color = if (isCur) accent else Palette.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(e.item.artist, style = MaterialTheme.typography.bodySmall, color = Palette.TextDim, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    if (!isCur) IconButton(onClick = { vm.player.removeAt(e.index) }) { Icon(Icons.Rounded.Close, "Remove", tint = Palette.TextFaint, modifier = Modifier.size(18.dp)) }
                    if (!state.shuffle) Icon(
                        Icons.Rounded.DragHandle, "Reorder", tint = Palette.TextDim,
                        modifier = Modifier.padding(8.dp).pointerInput(e.index, state.queue.size) {
                            val step = 54.dp.toPx()
                            var from = e.index
                            detectVerticalDragGestures(onDragEnd = { dy = 0f }) { ch, d ->
                                ch.consume(); dy += d
                                if (dy > step && from < state.queue.size - 1) { vm.player.move(from, from + 1); from += 1; dy -= step }
                                else if (dy < -step && from > 0) { vm.player.move(from, from - 1); from -= 1; dy += step }
                            }
                        },
                    )
                }
            }
        }
    }
}

// ---------------- Song action sheet ----------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SongMenuSheet(vm: AppViewModel, nav: Nav, onNavigated: () -> Unit) {
    val song by vm.menuSong.collectAsStateWithLifecycle()
    val playlistCtx by vm.menuPlaylistContext.collectAsStateWithLifecycle()
    val s = song ?: return
    val sheet = rememberModalBottomSheetState()
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    fun close(after: () -> Unit = {}) { scope.launch { sheet.hide() }.invokeOnCompletion { vm.menuSong.value = null; vm.menuPlaylistContext.value = null; after() } }
    ModalBottomSheet(onDismissRequest = { vm.menuSong.value = null; vm.menuPlaylistContext.value = null }, sheetState = sheet, containerColor = Palette.SurfaceHigh) {
        Column(Modifier.navigationBarsPadding().padding(bottom = 12.dp)) {
            Row(Modifier.padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Artwork(songArt(s), s.album, Modifier.size(56.dp), RoundedCornerShape(12.dp), lookup = com.rajdeep.listen.ui.components.songLookup(s))
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(s.title, style = MaterialTheme.typography.titleMedium, color = Palette.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("${s.artist} · ${s.album}", style = MaterialTheme.typography.bodyMedium, color = Palette.TextDim, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("${s.mimeType.substringAfter('/').uppercase()} · ${formatDuration(s.duration)} · ${s.folder}", style = MaterialTheme.typography.bodySmall, color = Palette.TextFaint, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Spacer(Modifier.height(6.dp))
            MenuItem(Icons.AutoMirrored.Rounded.PlaylistPlay, "Play next") { vm.player.playNext(listOf(s)); close() }
            MenuItem(Icons.AutoMirrored.Rounded.QueueMusic, "Add to queue") { vm.player.addToQueue(listOf(s)); close() }
            MenuItem(Icons.AutoMirrored.Rounded.PlaylistAdd, "Add to playlist") { close { vm.addToPlaylistSongs.value = listOf(s) } }
            playlistCtx?.let { pid -> MenuItem(Icons.Rounded.PlaylistRemove, "Remove from this playlist") { vm.library.removeFromPlaylist(pid, s.id); close() } }
            MenuItem(if (s.isFavorite) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder, if (s.isFavorite) "Remove from favourites" else "Add to favourites") { vm.library.toggleFavorite(s.id); close() }
            MenuItem(Icons.Rounded.Album, "Go to album") { close { onNavigated(); nav.album(s.albumId) } }
            MenuItem(Icons.Rounded.Person, "Go to artist") { close { onNavigated(); nav.artist(s.artist) } }
            (ctx as? com.rajdeep.listen.ui.MainActivity)?.let { act ->
                MenuItem(Icons.Rounded.Delete, "Delete from device") {
                    act.deleteMedia(listOf(android.net.Uri.parse(s.uri))) { ok -> if (ok) close() }
                }
            }
            MenuItem(Icons.Rounded.Share, "Share file") {
                val send = android.content.Intent(android.content.Intent.ACTION_SEND).setType(s.mimeType.ifEmpty { "audio/*" })
                    .putExtra(android.content.Intent.EXTRA_STREAM, android.net.Uri.parse(s.uri)).addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                ctx.startActivity(android.content.Intent.createChooser(send, s.title)); close()
            }
        }
    }
}

@Composable
private fun MenuItem(icon: ImageVector, label: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 22.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = Palette.TextDim)
        Spacer(Modifier.width(18.dp))
        Text(label, style = MaterialTheme.typography.titleMedium, color = Palette.Text)
    }
}

// ---------------- Add to playlist ----------------

@Composable
fun AddToPlaylistDialog(vm: AppViewModel) {
    val songs by vm.addToPlaylistSongs.collectAsStateWithLifecycle()
    val list = songs ?: return
    val playlists by vm.playlists.collectAsStateWithLifecycle()
    var newName by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val accent = LocalAccent.current.primary
    AlertDialog(
        onDismissRequest = { vm.addToPlaylistSongs.value = null },
        containerColor = Palette.SurfaceHigh,
        title = { Text(if (list.size == 1) "Add to playlist" else "Add ${list.size} songs", color = Palette.Text) },
        text = {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        newName, { newName = it }, placeholder = { Text("New playlist name") }, singleLine = true, modifier = Modifier.weight(1f),
                        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = accent, cursorColor = accent, focusedTextColor = Palette.Text, unfocusedTextColor = Palette.Text),
                    )
                    IconButton(enabled = newName.isNotBlank(), onClick = {
                        scope.launch { vm.library.createPlaylist(newName, list.map { it.id }); vm.addToPlaylistSongs.value = null; newName = "" }
                    }) { Icon(Icons.Rounded.Add, "Create", tint = if (newName.isNotBlank()) accent else Palette.TextFaint) }
                }
                Spacer(Modifier.height(10.dp))
                Column(Modifier.verticalScroll(rememberScrollState()).height(260.dp)) {
                    playlists.orEmpty().forEach { p ->
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { vm.library.addToPlaylist(p.id, list.map { it.id }); vm.addToPlaylistSongs.value = null }.padding(vertical = 10.dp, horizontal = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Artwork(p.coverAlbumId?.let { com.rajdeep.listen.ui.components.albumArt(it) }, p.name, Modifier.size(40.dp), RoundedCornerShape(8.dp), icon = Icons.AutoMirrored.Rounded.QueueMusic)
                            Spacer(Modifier.width(12.dp))
                            Column {
                                Text(p.name, style = MaterialTheme.typography.titleSmall, color = Palette.Text)
                                Text("${p.songCount} songs", style = MaterialTheme.typography.bodySmall, color = Palette.TextDim)
                            }
                        }
                    }
                    if (playlists.isNullOrEmpty()) Text("No playlists yet — create one above.", color = Palette.TextDim, style = MaterialTheme.typography.bodyMedium)
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = { vm.addToPlaylistSongs.value = null }) { Text("Cancel") } },
    )
}
