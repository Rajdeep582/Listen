package com.rajdeep.listen.ui.music

import androidx.lifecycle.compose.collectAsStateWithLifecycle

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.rajdeep.listen.data.Suggestion
import com.rajdeep.listen.ui.AppViewModel
import com.rajdeep.listen.ui.LocalBottomInset
import com.rajdeep.listen.ui.Nav
import com.rajdeep.listen.ui.Remote
import com.rajdeep.listen.ui.components.EmptyState
import com.rajdeep.listen.ui.components.MediaCard
import com.rajdeep.listen.ui.components.PlayButtons
import com.rajdeep.listen.ui.components.SectionHeader
import com.rajdeep.listen.ui.components.SongRow
import com.rajdeep.listen.ui.components.songArt
import com.rajdeep.listen.ui.components.songLookup
import com.rajdeep.listen.ui.theme.LocalAccent
import com.rajdeep.listen.ui.theme.Palette

private fun AppViewModel.play(list: List<Suggestion>, at: Suggestion) {
    when (at) {
        is Suggestion.Local -> player.playSongs(list.filterIsInstance<Suggestion.Local>().map { it.song }, list.filterIsInstance<Suggestion.Local>().indexOf(at))
        is Suggestion.Online -> { val on = list.filterIsInstance<Suggestion.Online>(); player.playOnline(on.map { it.track }, on.indexOf(at)) }
    }
}

/** Compact row on Home; tapping "Open" goes to the full list. */
@Composable
fun ForYouSection(vm: AppViewModel, nav: Nav) {
    val state by vm.forYou.collectAsStateWithLifecycle()
    val onlineFavorites by vm.onlineFavorites.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { vm.loadForYou() }
    val sub = if (com.rajdeep.listen.data.online.AiClient.fast) "AI · from your listening" else "From your listening"
    SectionHeader("Suggested for you", sub, action = "Open", onAction = { nav.controller.navigate("foryou") })
    when (val s = state) {
        Remote.Loading -> LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 20.dp), color = LocalAccent.current.primary, trackColor = Palette.SurfaceHigh)
        is Remote.Failed -> OfflineNote(s.message) { vm.loadForYou(force = true) }
        is Remote.Ready -> LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            items(s.data.take(12)) { sg ->
                when (sg) {
                    is Suggestion.Local -> MediaCard(sg.song.title, sg.song.artist, songArt(sg.song), sg.song.album, lookup = songLookup(sg.song), onClick = { vm.play(s.data, sg) }, width = 136.dp, badge = "Yours")
                    is Suggestion.Online -> MediaCard(sg.track.title, sg.track.artist, sg.track.artwork, sg.track.key, onClick = { vm.play(s.data, sg) }, width = 136.dp,
                        badge = if (sg.track.isPreview) "Preview" else sg.track.source,
                        lookup = com.rajdeep.listen.ui.components.songTrackLookup(sg.track.artist, sg.track.title),
                        isFavorite = onlineFavorites.any { it.songId == sg.track.key }, onFavoriteClick = { vm.toggleOnlineFavorite(sg.track) })
                }
            }
        }
    }
}

@Composable
fun ForYouScreen(vm: AppViewModel, nav: Nav) {
    val state by vm.forYou.collectAsStateWithLifecycle()
    val onlineFavorites by vm.onlineFavorites.collectAsStateWithLifecycle()
    val player by vm.player.state.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { vm.loadForYou() }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = LocalBottomInset.current)) {
        item(key = "head") {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { nav.back() }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back", tint = Palette.Text) }
                Column(Modifier.weight(1f)) {
                    Text("Suggested for you", style = MaterialTheme.typography.headlineMedium, color = Palette.Text)
                    Text(if (com.rajdeep.listen.data.online.AiClient.fast) "AI picks from your history, favourites and top artists" else "Based on your history, favourites and top artists",
                        style = MaterialTheme.typography.bodySmall, color = Palette.TextDim)
                }
                IconButton(onClick = { vm.loadForYou(force = true) }) { Icon(Icons.Rounded.Refresh, "Refresh", tint = LocalAccent.current.primary) }
            }
        }
        when (val s = state) {
            Remote.Loading -> item { Column(Modifier.padding(20.dp)) {
                LinearProgressIndicator(Modifier.fillMaxWidth(), color = LocalAccent.current.primary, trackColor = Palette.SurfaceHigh)
                Text("Finding songs you'll like…", color = Palette.TextDim, modifier = Modifier.padding(top = 12.dp))
            } }
            is Remote.Failed -> item { EmptyState(Icons.Rounded.AutoAwesome, "Nothing yet", s.message) }
            is Remote.Ready -> {
                item(key = "play") {
                    PlayButtons(
                        onPlay = { s.data.firstOrNull()?.let { vm.play(s.data, it) } },
                        onShuffle = { s.data.randomOrNull()?.let { vm.play(s.data, it) }; vm.player.toggleShuffle() },
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                    )
                }
                val groups = s.data.groupBy { it.reason }
                groups.forEach { (reason, list) ->
                    item(key = "h$reason") { SectionHeader(reason) }
                    items(list.size, key = { "s$reason$it" }) { i ->
                        when (val sg = list[i]) {
                            is Suggestion.Local -> SongRow(sg.song, sg.song.id == player.current?.localId, onClick = { vm.play(s.data, sg) }, onMore = { vm.menuSong.value = sg.song })
                            is Suggestion.Online -> OnlineRow(null, sg.track, { vm.play(s.data, sg) },
                                onFavorite = { vm.toggleOnlineFavorite(sg.track) }, isFavorite = onlineFavorites.any { it.songId == sg.track.key })
                        }
                    }
                }
            }
        }
    }
}
