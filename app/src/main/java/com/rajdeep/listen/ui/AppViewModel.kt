package com.rajdeep.listen.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.rajdeep.listen.ListenApp
import com.rajdeep.listen.data.Settings
import com.rajdeep.listen.data.db.Song
import com.rajdeep.listen.data.online.Audius
import com.rajdeep.listen.data.online.Deezer
import com.rajdeep.listen.data.online.InternetArchive
import com.rajdeep.listen.data.online.Jamendo
import com.rajdeep.listen.data.online.RadioBrowser
import com.rajdeep.listen.data.online.OnlineTrack
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import com.rajdeep.listen.data.AccountUser
import com.rajdeep.listen.data.AccountFavorite
import com.rajdeep.listen.data.AccountHistoryItem

sealed interface Remote<out T> {
    data object Loading : Remote<Nothing>
    data class Ready<T>(val data: T) : Remote<T>
    data class Failed(val message: String) : Remote<Nothing>
}

/** Activity-scoped VM: shared, cached library flows so tab switches never re-query or flash. */
class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val c = (app as ListenApp).container
    val library = c.library
    val player = c.player
    private val _accountUser = MutableStateFlow<AccountUser?>(c.account.user)
    val accountUser: StateFlow<AccountUser?> = _accountUser.asStateFlow()
    private val _accountReady = MutableStateFlow(false)
    val accountReady: StateFlow<Boolean> = _accountReady.asStateFlow()
    private val _onlineFavorites = MutableStateFlow(c.account.user?.id?.let(c.account::pendingFavoriteAdds).orEmpty())
    val onlineFavorites: StateFlow<List<AccountFavorite>> = _onlineFavorites.asStateFlow()
    private val _onlineHistory = MutableStateFlow<List<AccountHistoryItem>>(emptyList())
    val onlineHistory: StateFlow<List<AccountHistoryItem>> = _onlineHistory.asStateFlow()

    init {
        viewModelScope.launch {
            _accountUser.value = runCatching { c.account.restore() }.getOrNull()
            if (_accountUser.value != null) {
                runCatching { syncCloudFavorites() }
                runCatching { syncCloudPreferences() }
            }
            _accountReady.value = true
        }
        viewModelScope.launch {
            _accountUser.collectLatest { user ->
                if (user == null) _onlineHistory.value = emptyList()
                else runCatching { refreshCloudHistory() }
            }
        }
        viewModelScope.launch {
            kotlinx.coroutines.flow.combine(player.state, _accountUser) { state, user ->
                val track = state.current
                if (user != null && track != null && track.localId == null && !track.isLive) user to track else null
            }.filterNotNull().distinctUntilChangedBy { "${it.first.id}:${it.second.mediaId}" }.collect { (_, track) ->
                val historyItem = AccountHistoryItem(track.mediaId, track.title, track.artist, track.album, track.artwork?.toString().orEmpty())
                _onlineHistory.value = listOf(historyItem) + _onlineHistory.value.filterNot { it.songId == historyItem.songId }
                runCatching { c.account.recordPlay(track.mediaId, track.title, track.artist, track.album, track.artwork?.toString().orEmpty()) }
            }
        }
    }

    suspend fun authenticate(username: String, password: String, create: Boolean): Result<AccountUser> = runCatching {
        val localFavorites = if (create) c.library.dao.allFavourites() else emptyList()
        val session = if (create) c.account.signUp(username, password) else c.account.signIn(username, password)
        _accountUser.value = session.user
        if (create) localFavorites.forEach { song ->
            runCatching { c.account.syncFavorite(session.user.id, "local:${song.id}", true, song.title, song.artist, song.album) }
        }
        runCatching { syncCloudFavorites() }
        runCatching { syncCloudPreferences() }
        session.user
    }

    fun signOut() {
        player.stop()
        c.account.signOut(); _accountUser.value = null
        _onlineFavorites.value = emptyList()
        viewModelScope.launch { c.library.dao.replaceFavorites(emptyList()) }
    }
    private suspend fun syncCloudFavorites() {
        val ownerId = _accountUser.value?.id ?: return
        // A failed offline write must not block the separate read that restores saved favourites.
        runCatching { c.account.flushPendingFavorites(ownerId) }
        val records = c.account.favorites()
        val pending = c.account.pendingFavoriteChanges(ownerId).associateBy { it.favorite.songId }
        val ids = buildList {
            repeat(records.length()) { i ->
                val id = records.optJSONObject(i)?.optString("songId").orEmpty()
                if (id.startsWith("local:") && pending[id]?.shouldBeFavorite != false) id.removePrefix("local:").toLongOrNull()?.let(::add)
            }
            pending.values.filter { it.shouldBeFavorite && it.favorite.songId.startsWith("local:") }
                .mapNotNull { it.favorite.songId.removePrefix("local:").toLongOrNull() }.forEach(::add)
        }.distinct()
        c.library.dao.replaceFavorites(ids)
        _onlineFavorites.value = buildList {
            repeat(records.length()) { i ->
                val item = records.optJSONObject(i) ?: return@repeat
                val id = item.optString("songId")
                if (id.isNotBlank() && !id.startsWith("local:") && pending[id]?.shouldBeFavorite != false) add(
                    AccountFavorite(id, item.optString("title"), item.optString("artist"), item.optString("album"), item.optString("artwork")),
                )
            }
            pending.values.filter { it.shouldBeFavorite && !it.favorite.songId.startsWith("local:") }
                .forEach { add(it.favorite) }
        }.distinctBy { it.songId }
    }

    fun isOnlineFavorite(songId: String) = _onlineFavorites.value.any { it.songId == songId }

    fun toggleOnlineFavorite(songId: String, title: String, artist: String, album: String = "", artwork: String = ""): Boolean {
        val ownerId = _accountUser.value?.id ?: return false
        val existing = _onlineFavorites.value.firstOrNull { it.songId == songId }
        val saved = AccountFavorite(songId, title, artist, album, artwork)
        val add = existing == null
        _onlineFavorites.value = if (add) listOf(saved) + _onlineFavorites.value else _onlineFavorites.value.filterNot { it.songId == songId }
        viewModelScope.launch { c.account.syncFavorite(ownerId, songId, add, title, artist, album, artwork) }
        return true
    }

    fun toggleOnlineFavorite(track: OnlineTrack): Boolean = toggleOnlineFavorite(
        track.key, track.title, track.artist, track.album, track.artwork.orEmpty(),
    )

    fun playFavorite(favorite: AccountFavorite, onError: (String) -> Unit) = viewModelScope.launch {
        val query = "${favorite.title} ${favorite.artist}".trim()
        val match = runCatching {
            val results = com.rajdeep.listen.data.online.OnlineCatalog.search(query).filterNot { it.isLive }
            results.firstOrNull { it.key == favorite.songId }
        }.getOrNull()
        if (match == null) onError("Couldn't find a playable stream for this favourite.")
        else player.playOnline(listOf(match), 0)
    }
    fun recordSearch(query: String) = viewModelScope.launch { if (c.account.token != null) runCatching { c.account.recordSearch(query) } }
    suspend fun cloudFavorites() = runCatching { c.account.favorites() }.getOrNull()
    suspend fun cloudHistory() = runCatching { c.account.history() }.getOrNull()
    private suspend fun refreshCloudHistory() {
        val records = c.account.history()
        _onlineHistory.value = buildList {
            repeat(records.length()) { i ->
                val item = records.optJSONObject(i) ?: return@repeat
                val id = item.optString("songId")
                if (id.isNotBlank() && !id.startsWith("local:")) add(
                    AccountHistoryItem(id, item.optString("title"), item.optString("artist"), item.optString("album"), item.optString("artwork")),
                )
            }
        }
    }

    private fun <T> Flow<T>.cached(initial: T): StateFlow<T> = stateIn(viewModelScope, SharingStarted.WhileSubscribed(15_000), initial)

    val settings: StateFlow<Settings> = c.settings.settings.cached(Settings())
    val songs = library.songs.cached(null)
    val albums = library.albums.cached(null)
    val artists = library.artists.cached(null)
    val playlists = library.playlists.cached(null)
    val favourites = library.favourites.cached(null)
    val songFolders = library.songFolders.cached(null)
    val recentlyPlayed = library.recentlyPlayed(40).cached(null)
    val mostPlayed = library.mostPlayed(30).cached(null)
    val recentlyAdded = library.recentlyAdded(30).cached(null)
    val topArtists = library.topArtists(10).cached(null)
    val videos = library.videos.cached(null)
    val videoFolders = library.videoFolders.cached(null)
    val continueWatching = library.continueWatching.cached(null)
    val scanning = library.scanning

    private val _rediscover = MutableStateFlow<List<Song>>(emptyList())
    val rediscover = _rediscover.asStateFlow()
    private val _discover = MutableStateFlow<List<Song>>(emptyList())
    val discover = _discover.asStateFlow()

    private val _trending = MutableStateFlow<Remote<List<OnlineTrack>>>(Remote.Loading)
    val trending = _trending.asStateFlow()
    private val _charts = MutableStateFlow<Remote<List<OnlineTrack>>>(Remote.Loading)
    val charts = _charts.asStateFlow()
    private var trendingGenre: String? = null

    fun refreshMixes() = viewModelScope.launch {
        _rediscover.value = library.dao.unheard(20)
        _discover.value = library.dao.discoverFromTopArtists(20)
    }

    fun loadTrending(genre: String? = trendingGenre, force: Boolean = false) {
        if (!force && genre == trendingGenre && _trending.value is Remote.Ready) return
        trendingGenre = genre
        _trending.value = Remote.Loading
        viewModelScope.launch {
            _trending.value = runCatching { Audius.trending(genre) }
                .fold({ Remote.Ready(it) }, { Remote.Failed("Couldn't reach Audius. Check your connection.") })
        }
    }

    fun loadCharts(force: Boolean = false) {
        if (!force && _charts.value is Remote.Ready) return
        _charts.value = Remote.Loading
        viewModelScope.launch {
            _charts.value = runCatching { Deezer.chart() }
                .fold({ Remote.Ready(it) }, { Remote.Failed("Couldn't reach Deezer charts.") })
        }
    }

    private val _jamendo = MutableStateFlow<Remote<List<OnlineTrack>>>(Remote.Loading)
    val jamendo = _jamendo.asStateFlow()
    private val _radio = MutableStateFlow<Remote<List<OnlineTrack>>>(Remote.Loading)
    val radio = _radio.asStateFlow()
    private var radioKey: String? = "IN"
    private var jamendoTag: String? = null

    fun loadJamendo(tag: String? = jamendoTag, force: Boolean = false) {
        if (!Jamendo.enabled) { _jamendo.value = Remote.Failed("Add JAMENDO_CLIENT_ID to secrets.properties"); return }
        if (!force && tag == jamendoTag && _jamendo.value is Remote.Ready) return
        jamendoTag = tag; _jamendo.value = Remote.Loading
        viewModelScope.launch {
            _jamendo.value = runCatching { Jamendo.popular(tag) }.fold({ Remote.Ready(it) }, { Remote.Failed("Couldn't reach Jamendo.") })
        }
    }

    /** key: ISO country code (e.g. "IN") or "tag:<genre>" or null for worldwide. */
    fun loadRadio(key: String? = radioKey, force: Boolean = false) {
        if (!force && key == radioKey && _radio.value is Remote.Ready) return
        radioKey = key; _radio.value = Remote.Loading
        viewModelScope.launch {
            _radio.value = runCatching {
                when {
                    key == null -> RadioBrowser.top()
                    key.startsWith("tag:") -> RadioBrowser.byTag(key.removePrefix("tag:"))
                    else -> RadioBrowser.top(key)
                }
            }.fold({ Remote.Ready(it) }, { Remote.Failed("Couldn't reach Radio Browser.") })
        }
    }

    // ---------- Radio bands ----------
    private val _bands = MutableStateFlow<Map<String, Remote<List<OnlineTrack>>>>(emptyMap())
    val bands = _bands.asStateFlow()

    fun loadBand(id: String, force: Boolean = false) {
        if (!force && _bands.value[id] is Remote.Ready) return
        _bands.value = _bands.value + (id to Remote.Loading)
        viewModelScope.launch {
            val r = runCatching {
                when (id) {
                    "air" -> RadioBrowser.allIndiaRadio()
                    "india" -> RadioBrowser.top("IN")
                    "world" -> RadioBrowser.top()
                    else -> RadioBrowser.byTag(id)
                }
            }.fold({ if (it.isEmpty()) Remote.Failed("No stations found") else Remote.Ready(it) }, { Remote.Failed("Couldn't reach Radio Browser. Check your connection.") })
            _bands.value = _bands.value + (id to r)
        }
    }

    suspend fun searchStations(q: String) = runCatching { RadioBrowser.search(q) }.getOrDefault(emptyList())

    // ---------- Suggested for you (AI + history) ----------
    private val _forYou = MutableStateFlow<Remote<List<com.rajdeep.listen.data.Suggestion>>>(Remote.Loading)
    val forYou = _forYou.asStateFlow()
    private var forYouJob: kotlinx.coroutines.Job? = null

    fun loadForYou(force: Boolean = false) {
        if (!force && (_forYou.value is Remote.Ready || forYouJob?.isActive == true)) return
        forYouJob?.cancel()
        if (force || _forYou.value !is Remote.Ready) _forYou.value = Remote.Loading
        forYouJob = viewModelScope.launch {
            _forYou.value = runCatching { c.recommender.suggestions(force) }
                .fold({ if (it.isEmpty()) Remote.Failed("No suggestions yet — play a few songs first.") else Remote.Ready(it) }, { Remote.Failed("Couldn't build suggestions. Check your connection.") })
        }
    }

    /** Resolves an Internet Archive item to tracks, then plays it. */
    fun playArchive(item: InternetArchive.Item, onError: (String) -> Unit) = viewModelScope.launch {
        val tracks = runCatching { InternetArchive.tracks(item) }.getOrDefault(emptyList())
        if (tracks.isEmpty()) onError("No playable audio in this item") else player.playOnline(tracks, 0)
    }

    private suspend fun syncCloudPreferences() {
        val remote = c.account.preferences()
        if (remote.length() == 0) {
            val s = c.settings.current()
            c.account.savePreferences(org.json.JSONObject().put("onlineEnabled", s.onlineEnabled).put("musicTheme", s.musicTheme).put("reduceMotion", s.reduceMotion))
        } else {
            c.settings.update { current -> current.copy(
                onlineEnabled = if (remote.has("onlineEnabled")) remote.optBoolean("onlineEnabled") else current.onlineEnabled,
                musicTheme = if (remote.has("musicTheme")) remote.optInt("musicTheme").coerceIn(0, com.rajdeep.listen.ui.theme.Themes.music.lastIndex) else current.musicTheme,
                reduceMotion = if (remote.has("reduceMotion")) remote.optBoolean("reduceMotion") else current.reduceMotion,
            ) }
        }
    }

    fun updateSettings(f: (Settings) -> Settings) = viewModelScope.launch {
        c.settings.update(f)
        if (c.account.token != null) runCatching {
            val s = c.settings.current()
            c.account.savePreferences(org.json.JSONObject().put("onlineEnabled", s.onlineEnabled).put("musicTheme", s.musicTheme).put("reduceMotion", s.reduceMotion))
        }
    }
    fun rescan() = library.scan()

    // ---- song action sheet / playlist picker ----
    val menuSong = MutableStateFlow<Song?>(null)
    val menuPlaylistContext = MutableStateFlow<Long?>(null)
    val addToPlaylistSongs = MutableStateFlow<List<Song>?>(null)
}
