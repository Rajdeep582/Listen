package com.rajdeep.listen.ui.video

import androidx.lifecycle.compose.collectAsStateWithLifecycle

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Sort
import androidx.compose.material.icons.rounded.VideoLibrary
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.rajdeep.listen.data.db.Video
import com.rajdeep.listen.ui.AppViewModel
import com.rajdeep.listen.ui.LocalBottomInset
import com.rajdeep.listen.ui.MainActivity
import com.rajdeep.listen.ui.Nav
import com.rajdeep.listen.ui.components.Artwork
import com.rajdeep.listen.ui.components.EmptyState
import com.rajdeep.listen.ui.components.formatDuration
import com.rajdeep.listen.ui.components.formatSize
import com.rajdeep.listen.ui.components.seedBrush
import com.rajdeep.listen.ui.theme.LocalAccent
import com.rajdeep.listen.ui.theme.Palette
import com.rajdeep.listen.video.VideoPlayerActivity

private fun play(ctx: Context, list: List<Video>, i: Int) {
    // Pause music so audio doesn't overlap; VLC also takes audio focus.
    (ctx.applicationContext as com.rajdeep.listen.ListenApp).container.player.pause()
    ctx.startActivity(VideoPlayerActivity.intent(ctx, list, i))
}

private fun resLabel(v: Video): String? {
    val h = minOf(v.width, v.height).takeIf { it > 0 } ?: return null
    return when { h >= 2000 -> "4K"; h >= 1400 -> "1440p"; h >= 1000 -> "1080p"; h >= 700 -> "720p"; h >= 470 -> "480p"; else -> "${h}p" }
}

@Composable
fun VideoHomeScreen(vm: AppViewModel, nav: Nav, activity: MainActivity) {
    val cont by vm.continueWatching.collectAsStateWithLifecycle()
    val folders by vm.videoFolders.collectAsStateWithLifecycle()
    val videos by vm.videos.collectAsStateWithLifecycle()
    val scanning by vm.scanning.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    if (videos?.isEmpty() == true) {
        EmptyState(Icons.Rounded.VideoLibrary, if (scanning) "Scanning videos…" else "No videos found", "Videos on this phone (Movies, Download, Camera, WhatsApp…) appear here automatically.")
        return
    }
    LazyVerticalGrid(
        GridCells.Adaptive(160.dp), Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = LocalBottomInset.current),
        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Column(Modifier.padding(horizontal = 4.dp, vertical = 8.dp)) {
                Text("Watch", style = MaterialTheme.typography.displaySmall, color = Palette.Text)
                Text("${videos?.size ?: 0} videos · ${folders?.size ?: 0} folders", style = MaterialTheme.typography.bodyMedium, color = Palette.TextDim)
            }
        }
        val c = cont.orEmpty()
        if (c.isNotEmpty()) item(span = { GridItemSpan(maxLineSpan) }) {
            Column {
                SectionHeaderTight("Continue watching")
                LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(c, key = { it.id }) { v -> VideoCard(v, Modifier.width(240.dp)) { play(ctx, c, c.indexOf(v)) } }
                }
            }
        }
        val recent = videos.orEmpty().take(12)
        if (recent.isNotEmpty()) item(span = { GridItemSpan(maxLineSpan) }) {
            Column {
                SectionHeaderTight("Recently added")
                LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(recent, key = { it.id }) { v -> VideoCard(v, Modifier.width(200.dp)) { play(ctx, recent, recent.indexOf(v)) } }
                }
            }
        }
        if (!folders.isNullOrEmpty()) item(span = { GridItemSpan(maxLineSpan) }) { SectionHeaderTight("Folders") }
        items(folders.orEmpty().size, key = { "f" + folders!![it].folder }) { i ->
            val f = folders!![i]
            Column(
                Modifier.clip(RoundedCornerShape(16.dp)).background(Palette.SurfaceHigh).clickable { nav.videoFolder(f.folder) }.padding(14.dp),
            ) {
                Box(Modifier.size(42.dp).clip(RoundedCornerShape(12.dp)).background(seedBrush(f.folder)), contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.Folder, null, tint = Color.White)
                }
                Spacer(Modifier.height(12.dp))
                Text(f.folder.substringAfterLast('/'), style = MaterialTheme.typography.titleSmall, color = Palette.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${f.count} videos", style = MaterialTheme.typography.bodySmall, color = Palette.TextDim)
            }
        }
    }
}

@Composable
private fun SectionHeaderTight(title: String) {
    Text(title, style = MaterialTheme.typography.headlineSmall, color = Palette.Text, modifier = Modifier.padding(start = 4.dp, top = 18.dp, bottom = 10.dp))
}

private enum class VSort(val label: String) { ADDED("Recently added"), NAME("Name"), DURATION("Longest"), SIZE("Largest") }

@Composable
fun VideoAllScreen(vm: AppViewModel, activity: MainActivity, historyOnly: Boolean) {
    val all by vm.videos.collectAsStateWithLifecycle()
    val cont by vm.continueWatching.collectAsStateWithLifecycle()
    var sort by rememberSaveable { mutableStateOf(VSort.ADDED) }
    var menu by remember { mutableStateOf(false) }
    val ctx = LocalContext.current
    val base = if (historyOnly) all.orEmpty().filter { it.lastWatched > 0 }.sortedByDescending { it.lastWatched } else all.orEmpty()
    val list = remember(base, sort, historyOnly) {
        if (historyOnly) base else when (sort) {
            VSort.ADDED -> base
            VSort.NAME -> base.sortedBy { it.title.lowercase() }
            VSort.DURATION -> base.sortedByDescending { it.duration }
            VSort.SIZE -> base.sortedByDescending { it.size }
        }
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = LocalBottomInset.current)) {
        item {
            Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(if (historyOnly) "History" else "All videos", style = MaterialTheme.typography.displaySmall, color = Palette.Text, modifier = Modifier.weight(1f))
                if (!historyOnly) Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.Sort, "Sort", tint = Palette.TextDim) }
                    DropdownMenu(menu, { menu = false }) {
                        VSort.entries.forEach { s -> DropdownMenuItem(text = { Text(s.label, color = if (s == sort) LocalAccent.current.primary else Palette.Text) }, onClick = { sort = s; menu = false }) }
                    }
                }
            }
        }
        if (list.isEmpty()) item { EmptyState(Icons.Rounded.Movie, if (historyOnly) "Nothing watched yet" else "No videos", if (historyOnly) "Videos you watch are remembered with their position." else "Add videos to your phone.") }
        items(list.size, key = { list[it].id }) { i -> VideoRow(list[i]) { play(ctx, list, i) } }
    }
}

@Composable
fun VideoFolderScreen(vm: AppViewModel, nav: Nav, activity: MainActivity, folder: String) {
    val videos by remember(folder) { vm.library.dao.folderVideos(folder) }.collectAsStateWithLifecycle(null)
    val ctx = LocalContext.current
    val list = videos.orEmpty()
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = LocalBottomInset.current)) {
        item {
            Row(Modifier.padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { nav.back() }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back", tint = Palette.Text) }
                Column {
                    Text(folder.substringAfterLast('/'), style = MaterialTheme.typography.headlineSmall, color = Palette.Text)
                    Text("${list.size} videos · $folder", style = MaterialTheme.typography.bodySmall, color = Palette.TextDim, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        items(list.size, key = { list[it].id }) { i -> VideoRow(list[i]) { play(ctx, list, i) } }
    }
}

@Composable
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
fun VideoCard(v: Video, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Column(modifier.combinedClickable(onClick = onClick, onLongClick = { VideoMenu.target.value = v })) {
        Thumb(v, Modifier.fillMaxWidth().aspectRatio(16 / 9f))
        Spacer(Modifier.height(8.dp))
        Text(v.title, style = MaterialTheme.typography.titleSmall, color = Palette.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(
            if (v.position > 0 && v.duration > 0) "${formatDuration(v.duration - v.position)} left" else formatDuration(v.duration),
            style = MaterialTheme.typography.bodySmall, color = Palette.TextDim,
        )
    }
}

@Composable
private fun Thumb(v: Video, modifier: Modifier) {
    val accent = LocalAccent.current.primary
    Box(modifier.clip(RoundedCornerShape(14.dp))) {
        Artwork(v.uri, v.title, Modifier.fillMaxSize(), RoundedCornerShape(14.dp), icon = Icons.Rounded.Movie, videoFrame = true)
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(0.55f)))))
        Text(formatDuration(v.duration), style = MaterialTheme.typography.labelMedium, color = Color.White,
            modifier = Modifier.align(Alignment.BottomEnd).padding(6.dp).clip(RoundedCornerShape(6.dp)).background(Color.Black.copy(0.6f)).padding(horizontal = 5.dp, vertical = 1.dp))
        resLabel(v)?.let {
            Text(it, style = MaterialTheme.typography.labelSmall, color = Palette.Ink,
                modifier = Modifier.align(Alignment.TopStart).padding(6.dp).clip(RoundedCornerShape(5.dp)).background(LocalAccent.current.secondary).padding(horizontal = 5.dp, vertical = 1.dp))
        }
        if (v.position > 0 && v.duration > 0) {
            Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(3.dp).background(Color.White.copy(0.25f))) {
                Box(Modifier.fillMaxHeight().fillMaxWidth((v.position.toFloat() / v.duration).coerceIn(0f, 1f)).background(accent))
            }
        }
    }
}

@Composable
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
fun VideoRow(v: Video, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().combinedClickable(onClick = onClick, onLongClick = { VideoMenu.target.value = v }).padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        // Compact thumbnail so the title / details get most of the row.
        Thumb(v, Modifier.width(104.dp).aspectRatio(16 / 9f))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(v.title, style = MaterialTheme.typography.titleSmall, color = Palette.Text, maxLines = 3, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(3.dp))
            Text(listOfNotNull(formatDuration(v.duration).takeIf { v.duration > 0 }, resLabel(v), formatSize(v.size)).joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = Palette.TextDim, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(v.folder.substringAfterLast('/'), style = MaterialTheme.typography.bodySmall, color = Palette.TextFaint, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (v.position > 5_000 && v.duration > 0 && v.position < v.duration - 10_000) {
                Text("Resume from ${formatDuration(v.position)}", style = MaterialTheme.typography.labelSmall, color = LocalAccent.current.primary, maxLines = 1)
            }
        }
        IconButton(onClick = { VideoMenu.target.value = v }) { Icon(Icons.Rounded.MoreVert, "More", tint = Palette.TextDim) }
    }
}



/** Long-press / ⋮ target for the video action sheet. */
object VideoMenu { val target = kotlinx.coroutines.flow.MutableStateFlow<Video?>(null) }

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun VideoMenuSheet(activity: MainActivity) {
    val v by VideoMenu.target.collectAsStateWithLifecycle()
    val video = v ?: return
    val ctx = LocalContext.current
    var confirm by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf<String?>(null) }
    androidx.compose.material3.ModalBottomSheet(onDismissRequest = { VideoMenu.target.value = null }, containerColor = Palette.SurfaceHigh) {
        Column(Modifier.navigationBarsPadding().padding(bottom = 12.dp)) {
            Row(Modifier.padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Thumb(video, Modifier.width(120.dp).aspectRatio(16 / 9f))
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(video.title, style = MaterialTheme.typography.titleMedium, color = Palette.Text, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(listOfNotNull(resLabel(video), formatDuration(video.duration), formatSize(video.size)).joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = Palette.TextDim)
                    Text(video.folder, style = MaterialTheme.typography.bodySmall, color = Palette.TextFaint, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            SheetItem(Icons.Rounded.PlayArrow, "Play") { VideoMenu.target.value = null; play(ctx, listOf(video), 0) }
            SheetItem(Icons.Rounded.Share, "Share") {
                val send = android.content.Intent(android.content.Intent.ACTION_SEND).setType(video.mimeType.ifEmpty { "video/*" })
                    .putExtra(android.content.Intent.EXTRA_STREAM, android.net.Uri.parse(video.uri)).addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                ctx.startActivity(android.content.Intent.createChooser(send, video.title)); VideoMenu.target.value = null
            }
            SheetItem(Icons.Rounded.Delete, "Delete from device", danger = true) { confirm = true }
            msg?.let { Text(it, color = Palette.Danger, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = 22.dp, vertical = 6.dp)) }
        }
    }
    if (confirm) androidx.compose.material3.AlertDialog(
        onDismissRequest = { confirm = false }, containerColor = Palette.SurfaceHigh,
        title = { Text("Delete this video?", color = Palette.Text) },
        text = { Text("“${video.title}” (${formatSize(video.size)}) will be permanently removed from your phone. This can't be undone.", color = Palette.TextDim) },
        confirmButton = {
            androidx.compose.material3.TextButton(onClick = {
                confirm = false
                activity.deleteMedia(listOf(android.net.Uri.parse(video.uri))) { ok -> if (ok) VideoMenu.target.value = null else msg = "Couldn't delete this file." }
            }) { Text("Delete", color = Palette.Danger) }
        },
        dismissButton = { androidx.compose.material3.TextButton(onClick = { confirm = false }) { Text("Cancel", color = Palette.TextDim) } },
    )
}

@Composable
private fun SheetItem(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, danger: Boolean = false, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 22.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = if (danger) Palette.Danger else Palette.TextDim)
        Spacer(Modifier.width(18.dp))
        Text(label, style = MaterialTheme.typography.titleMedium, color = if (danger) Palette.Danger else Palette.Text)
    }
}

