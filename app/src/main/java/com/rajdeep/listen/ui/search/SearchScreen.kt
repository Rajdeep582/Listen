package com.rajdeep.listen.ui.search

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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.TrendingUp
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.NorthWest
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SearchOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.rajdeep.listen.data.SearchEngine
import com.rajdeep.listen.data.SearchHistory
import com.rajdeep.listen.data.SearchResults
import com.rajdeep.listen.data.online.InternetArchive
import com.rajdeep.listen.data.online.OnlineCatalog
import com.rajdeep.listen.data.online.OnlineTrack
import com.rajdeep.listen.data.online.RadioBrowser
import com.rajdeep.listen.ui.components.albumArt as albumArtUri
import com.rajdeep.listen.ui.AppViewModel
import com.rajdeep.listen.ui.LocalBottomInset
import com.rajdeep.listen.ui.Nav
import com.rajdeep.listen.ui.components.EmptyState
import com.rajdeep.listen.ui.components.MediaCard
import com.rajdeep.listen.ui.components.SectionHeader
import com.rajdeep.listen.ui.components.SongRow
import com.rajdeep.listen.ui.components.albumLookup
import com.rajdeep.listen.ui.components.artistLookup
import com.rajdeep.listen.ui.music.ListRow
import com.rajdeep.listen.ui.music.OnlineRow
import com.rajdeep.listen.ui.theme.LocalAccent
import com.rajdeep.listen.ui.theme.Mode
import com.rajdeep.listen.ui.theme.Palette
import com.rajdeep.listen.ui.video.VideoRow
import com.rajdeep.listen.video.SelectChip
import com.rajdeep.listen.video.VideoPlayerActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private data class OnlineBundle(val tracks: List<OnlineTrack>, val stations: List<OnlineTrack>, val archive: List<InternetArchive.Item>)

/** Process-wide LRU so backspacing / revisiting a query never refetches. */
private val onlineCache = object : LinkedHashMap<String, OnlineBundle>(32, 0.75f, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, OnlineBundle>?) = size > 30
}

@Composable
fun SearchScreen(vm: AppViewModel, nav: Nav, mode: Mode) {
    val ctx = LocalContext.current
    val history = remember { SearchHistory(ctx) }
    val modeKey = mode.name
    var query by rememberSaveable { mutableStateOf("") }
    var recents by remember { mutableStateOf(history.get(modeKey)) }
    var local by remember { mutableStateOf(SearchResults()) }
    var suggestions by remember { mutableStateOf<List<String>>(emptyList()) }
    var webSuggest by remember { mutableStateOf<List<String>>(emptyList()) }
    var aiSuggest by remember { mutableStateOf<List<String>>(emptyList()) }
    var bundle by remember { mutableStateOf<OnlineBundle?>(null) }
    var onlineLoading by remember { mutableStateOf(false) }
    var toast by remember { mutableStateOf<String?>(null) }
    var source by rememberSaveable { mutableStateOf("All") }

    val allSongs by vm.songs.collectAsStateWithLifecycle()
    val allAlbums by vm.albums.collectAsStateWithLifecycle()
    val allArtists by vm.artists.collectAsStateWithLifecycle()
    val allVideos by vm.videos.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val state by vm.player.state.collectAsStateWithLifecycle()

    val focus = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val accent = LocalAccent.current.primary
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    fun commit(q: String = query) { history.add(modeKey, q); recents = history.get(modeKey); if (mode == Mode.MUSIC) vm.recordSearch(q) }
    fun runQuery(q: String) {
        val raw = q.trim()
        if (raw.isEmpty()) return
        commit(raw)
        keyboard?.hide()
        focusManager.clearFocus()
        if (mode == Mode.MUSIC && settings.onlineEnabled && com.rajdeep.listen.data.online.AiClient.fast) {
            scope.launch {
                val norm = com.rajdeep.listen.data.online.AiClient.normalize(raw, 1500)
                query = norm
                if (!norm.equals(raw, ignoreCase = true)) {
                    commit(norm)
                }
            }
        } else {
            query = raw
        }
    }

    LaunchedEffect(Unit) { if (query.isEmpty()) runCatching { focus.requestFocus() } }
    // hide keyboard as soon as the user starts scrolling results
    LaunchedEffect(listState) { snapshotFlow { listState.isScrollInProgress }.collect { if (it) { keyboard?.hide(); focusManager.clearFocus() } } }

    // ---- local: instant, ranked, on a background thread ----
    LaunchedEffect(query, allSongs, allAlbums, allArtists, allVideos, mode) {
        val q = query.trim()
        if (q.isEmpty()) { local = SearchResults(); suggestions = emptyList(); return@LaunchedEffect }
        delay(90)
        local = withContext(Dispatchers.Default) {
            if (mode == Mode.MUSIC) SearchResults(
                songs = SearchEngine.songs(q, allSongs.orEmpty()),
                albums = SearchEngine.albums(q, allAlbums.orEmpty()),
                artists = SearchEngine.artists(q, allArtists.orEmpty()),
            ) else SearchResults(videos = SearchEngine.videos(q, allVideos.orEmpty()))
        }
        suggestions = if (mode == Mode.MUSIC) withContext(Dispatchers.Default) { SearchEngine.suggestions(q, allSongs.orEmpty()) }.filterNot { it.equals(q, true) } else emptyList()
    }

    // ---- auto-suggest while typing: instant web completions + AI completions (async, cached) ----
    LaunchedEffect(query, settings.onlineEnabled, mode) {
        val q = query.trim()
        webSuggest = emptyList(); aiSuggest = emptyList()
        if (mode != Mode.MUSIC || !settings.onlineEnabled || q.length < 2) return@LaunchedEffect
        coroutineScope {
            launch {
                delay(120)
                webSuggest = com.rajdeep.listen.data.online.QuerySuggest.complete(q).filterNot { it.equals(q, true) }
            }
            launch {
                delay(if (com.rajdeep.listen.data.online.AiClient.fast) 250 else 600)
                aiSuggest = com.rajdeep.listen.data.online.AiClient.suggest(q).filterNot { it.equals(q, true) }
            }
        }
    }

    // ---- online: debounced, parallel, cached, ranked ----
    LaunchedEffect(query, settings.onlineEnabled, mode) {
        val q = query.trim()
        if (mode != Mode.MUSIC || !settings.onlineEnabled || q.length < 2) { bundle = null; onlineLoading = false; return@LaunchedEffect }
        delay(450)
        onlineLoading = true
        // Normalize query with AI if available so misspelled queries (e.g. "arjit singh") yield clean matches
        val targetQ = if (com.rajdeep.listen.data.online.AiClient.fast) {
            com.rajdeep.listen.data.online.AiClient.normalize(q, 1500)
        } else q
        val key = SearchEngine.norm(targetQ)
        onlineCache[key]?.let { bundle = it; onlineLoading = false; return@LaunchedEffect }
        var tracks = emptyList<OnlineTrack>(); var stations = emptyList<OnlineTrack>(); var archive = emptyList<InternetArchive.Item>()
        coroutineScope {
            launch { tracks = SearchEngine.online(targetQ, OnlineCatalog.search(targetQ)); bundle = OnlineBundle(tracks, stations, archive) }
            launch { stations = runCatching { RadioBrowser.search(targetQ) }.getOrDefault(emptyList()).take(12) }
            launch { archive = runCatching { InternetArchive.search(targetQ) }.getOrDefault(emptyList()).take(12) }
        }
        OnlineBundle(tracks, stations, archive).also { bundle = it; if (tracks.isNotEmpty() || stations.isNotEmpty() || archive.isNotEmpty()) onlineCache[key] = it }
        onlineLoading = false
    }

    Column(Modifier.fillMaxSize()) {
        TextField(
            value = query, onValueChange = { query = it }, singleLine = true,
            placeholder = { Text(if (mode == Mode.MUSIC) "Songs, artists, albums, radio" else "Videos, folders", color = Palette.TextFaint) },
            leadingIcon = { Icon(Icons.Rounded.Search, null, tint = Palette.TextDim) },
            trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { query = ""; runCatching { focus.requestFocus() } }) { Icon(Icons.Rounded.Close, "Clear", tint = Palette.TextDim) } },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { runQuery(query) }),
            colors = TextFieldDefaults.colors(
                focusedContainerColor = Palette.SurfaceHigh, unfocusedContainerColor = Palette.SurfaceHigh,
                focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
                cursorColor = accent, focusedTextColor = Palette.Text, unfocusedTextColor = Palette.Text,
            ),
            textStyle = MaterialTheme.typography.titleMedium,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).clip(CircleShape).focusRequester(focus),
        )

        val b = bundle
        val r = local
        if (mode == Mode.MUSIC && query.isNotBlank()) {
            val counts = linkedMapOf(
                "Local" to r.songs.size,
                "JioSaavn" to b?.tracks.orEmpty().count { it.source == "JioSaavn" },
                "FreeToUse" to b?.tracks.orEmpty().count { it.source == "FreeToUse" },
                "Incompetech" to b?.tracks.orEmpty().count { it.source == "Incompetech" },
                "Openverse" to b?.tracks.orEmpty().count { it.source == "Openverse" },
                "Audius" to b?.tracks.orEmpty().count { it.source == "Audius" },
                "Jamendo" to b?.tracks.orEmpty().count { it.source == "Jamendo" },
                "ccMixter" to b?.tracks.orEmpty().count { it.source == "ccMixter" },
                "iTunes" to b?.tracks.orEmpty().count { it.source == "iTunes" },
                "Deezer" to b?.tracks.orEmpty().count { it.source == "Deezer" },
                "Archive" to (b?.archive?.size ?: 0),
                "Radio" to (b?.stations?.size ?: 0),
            )
            LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 4.dp)) {
                items(listOf("All") + counts.keys.filter { counts[it]!! > 0 || it == source }, key = { it }) { k ->
                    val n = counts[k]
                    SelectChip(if (n == null) k else "$k $n", source == k) { source = k }
                }
            }
        }
        if (onlineLoading) LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 20.dp).height(2.dp), color = accent, trackColor = Palette.SurfaceHigh)

        fun show(k: String) = source == "All" || source == k
        LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = PaddingValues(bottom = LocalBottomInset.current)) {
            // ---------- idle: recent searches ----------
            if (query.isBlank()) {
                if (recents.isEmpty()) item { EmptyState(Icons.Rounded.Search, "Search everything", if (mode == Mode.MUSIC) "Your phone, JioSaavn, FreeToUse, Incompetech, Audius, Jamendo, iTunes, Deezer, Internet Archive and live radio — in one place." else "Find any video on this phone.") }
                else {
                    item(key = "recH") {
                        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("Recent searches", style = MaterialTheme.typography.headlineSmall, color = Palette.Text, modifier = Modifier.weight(1f))
                            TextButton(onClick = { history.clear(modeKey); recents = emptyList() }) { Text("Clear", color = accent) }
                        }
                    }
                    items(recents, key = { "rec$it" }) { q ->
                        QueryRow(Icons.Rounded.History, q, onClick = { runQuery(q) }, onRemove = { history.remove(modeKey, q); recents = history.get(modeKey) })
                    }
                }
                return@LazyColumn
            }
            // ---------- suggestions ----------
            if (source == "All") {
                // local matches → AI picks → web completions; de-duplicated, max 8
                val seen = HashSet<String>()
                val merged = (suggestions.map { it to 0 } + aiSuggest.map { it to 1 } + webSuggest.map { it to 2 })
                    .filter { seen.add(it.first.lowercase().trim()) }.take(8)
                items(merged, key = { "sug${it.second}${it.first}" }) { (sg, kind) ->
                    QueryRow(
                        when (kind) { 1 -> Icons.Rounded.AutoAwesome; 2 -> Icons.Rounded.Search; else -> Icons.AutoMirrored.Rounded.TrendingUp },
                        sg, onClick = { runQuery(sg) }, onFill = { query = "$sg " },
                    )
                }
            }

            if (mode == Mode.MUSIC) {
                val on = b?.tracks.orEmpty().filter { show(it.source) }
                val localSongs = if (show("Local")) r.songs else emptyList()
                val nothing = localSongs.isEmpty() && on.isEmpty() && (r.artists.isEmpty() && r.albums.isEmpty() || !show("Local")) &&
                    (b?.stations.isNullOrEmpty() || !show("Radio")) && (b?.archive.isNullOrEmpty() || !show("Archive"))
                if (nothing && !onlineLoading) item(key = "none") { EmptyState(Icons.Rounded.SearchOff, "No matches", "Try fewer words or a different spelling.") }

                if (show("Local") && r.artists.isNotEmpty()) item(key = "artists") {
                    SectionHeader("Artists")
                    LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        items(r.artists, key = { it.artist }) { a -> MediaCard(a.artist, "${a.songCount} songs", albumArtUri(a.albumId), a.artist, lookup = artistLookup(a.artist), preferLookup = true, onClick = { commit(); nav.artist(a.artist) }, width = 108.dp, circle = true) }
                    }
                }
                if (show("Local") && r.albums.isNotEmpty()) item(key = "albums") {
                    SectionHeader("Albums")
                    LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        items(r.albums, key = { it.albumId }) { a -> MediaCard(a.album, a.artist, albumArtUri(a.albumId), a.album, lookup = albumLookup(a.artist, a.album), onClick = { commit(); nav.album(a.albumId) }, width = 132.dp) }
                    }
                }
                if (localSongs.isNotEmpty() || on.isNotEmpty()) item(key = "songsH") {
                    SectionHeader("Songs", "${localSongs.size + on.size} results · ${(listOf("phone").takeIf { localSongs.isNotEmpty() }.orEmpty() + on.map { it.source }.distinct()).joinToString(", ")}")
                }
                items(localSongs.size, key = { "s" + localSongs[it].id }) { i ->
                    val s = localSongs[i]
                    SongRow(s, s.id == state.current?.localId, onClick = { commit(); vm.player.playSongs(localSongs, i) }, onMore = { vm.menuSong.value = s })
                }
                items(on.size, key = { "o" + on[it].key }) { i -> OnlineRow(null, on[i]) { commit(); vm.player.playOnline(on, i) } }
                val st = b?.stations.orEmpty()
                if (show("Radio") && st.isNotEmpty()) {
                    item(key = "radH") { SectionHeader("Radio stations", "Live") }
                    items(st.size, key = { "r" + st[it].key }) { i -> OnlineRow(null, st[i]) { commit(); vm.player.playOnline(listOf(st[i]), 0) } }
                }
                val ar = b?.archive.orEmpty()
                if (show("Archive") && ar.isNotEmpty()) {
                    item(key = "arcH") { SectionHeader("Internet Archive", "Free recordings · tap to play all") }
                    items(ar.size, key = { "a" + ar[it].id }) { i ->
                        val it2 = ar[i]
                        ListRow(it2.artwork, it2.title, it2.id, it2.creator) { commit(); vm.playArchive(it2) { m -> toast = m } }
                    }
                }
                toast?.let { m -> item(key = "toast") { Text(m, color = Palette.Danger, modifier = Modifier.padding(20.dp)) } }
            } else {
                if (r.videos.isEmpty()) item(key = "none") { EmptyState(Icons.Rounded.SearchOff, "No matches", "Try fewer words or a different spelling.") }
                items(r.videos.size, key = { "v" + r.videos[it].id }) { i ->
                    VideoRow(r.videos[i]) { commit(); ctx.startActivity(VideoPlayerActivity.intent(ctx, r.videos, i)) }
                }
            }
        }
    }
}

@Composable
private fun QueryRow(icon: ImageVector, text: String, onClick: () -> Unit, onRemove: (() -> Unit)? = null, onFill: (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(start = 20.dp, end = 8.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = Palette.TextFaint, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(16.dp))
        Text(text, style = MaterialTheme.typography.titleMedium, color = Palette.Text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(vertical = 10.dp))
        if (onRemove != null) IconButton(onClick = onRemove) { Icon(Icons.Rounded.Close, "Remove", tint = Palette.TextFaint, modifier = Modifier.size(18.dp)) }
        if (onFill != null) IconButton(onClick = onFill) { Icon(Icons.Rounded.NorthWest, "Use", tint = Palette.TextFaint, modifier = Modifier.size(18.dp)) }
    }
}
