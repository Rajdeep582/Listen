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
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

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

    fun updateSettings(f: (Settings) -> Settings) = viewModelScope.launch { c.settings.update(f) }
    fun rescan() = library.scan()

    // ---- song action sheet / playlist picker ----
    val menuSong = MutableStateFlow<Song?>(null)
    val menuPlaylistContext = MutableStateFlow<Long?>(null)
    val addToPlaylistSongs = MutableStateFlow<List<Song>?>(null)
}
