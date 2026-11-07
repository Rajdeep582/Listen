package com.rajdeep.listen.data

import androidx.compose.runtime.Immutable
import com.rajdeep.listen.data.db.Album
import com.rajdeep.listen.data.db.Artist
import com.rajdeep.listen.data.db.LibraryDao
import com.rajdeep.listen.data.db.LyricsEntity
import com.rajdeep.listen.data.db.Song
import com.rajdeep.listen.data.db.Video
import com.rajdeep.listen.data.db.VideoStateEntity
import com.rajdeep.listen.data.online.Lrclib
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

@Immutable
data class LyricLine(val timeMs: Long, val text: String)

@Immutable
sealed interface Lyrics {
    data class Synced(val lines: List<LyricLine>) : Lyrics
    data class Plain(val text: String) : Lyrics
    data object None : Lyrics
    data object Offline : Lyrics
}

@Immutable
data class SearchResults(
    val songs: List<Song> = emptyList(),
    val albums: List<Album> = emptyList(),
    val artists: List<Artist> = emptyList(),
    val videos: List<Video> = emptyList(),
)

class LibraryRepository(
    val dao: LibraryDao,
    private val scanner: MediaScanner,
    private val settings: SettingsStore,
    private val scope: CoroutineScope,
    private val account: AccountClient,
) {
    private val scanMutex = Mutex()
    private val favoriteMutex = Mutex()
    private val _scanning = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = _scanning.asStateFlow()

    fun scan(audio: Boolean = true, video: Boolean = true) {
        scope.launch {
            if (scanMutex.isLocked) return@launch
            scanMutex.withLock {
                _scanning.value = true
                try {
                    val s = settings.current()
                    runCatching { scanner.scan(s.minAudioSec * 1000L, audio, video, s.hiddenFolders) }
                } finally { _scanning.value = false }
            }
        }
    }

    suspend fun allFolders() = scanner.allFolders()

    // ---- reads ----
    val songs = dao.songs()
    val albums = dao.albums()
    val artists = dao.artists()
    val playlists = dao.playlists()
    val favourites = dao.favourites()
    val songFolders = dao.songFolders()
    val videos = dao.videos()
    val videoFolders = dao.videoFolders()
    val continueWatching = dao.continueWatching()
    fun recentlyPlayed(limit: Int = 30) = dao.recentlyPlayed(limit)
    fun mostPlayed(limit: Int = 30) = dao.mostPlayed(limit)
    fun recentlyAdded(limit: Int = 30) = dao.recentlyAdded(limit)
    fun topArtists(limit: Int = 12) = dao.topArtists(limit)

    suspend fun search(q: String): SearchResults {
        val t = q.trim()
        if (t.isEmpty()) return SearchResults()
        return SearchResults(dao.searchSongs(t), dao.searchAlbums(t), dao.searchArtists(t), dao.searchVideos(t))
    }

    // ---- writes ----
    fun toggleFavorite(id: Long) = scope.launch {
        favoriteMutex.withLock {
            val song = dao.songsByIds(listOf(id)).firstOrNull()
            val isFavorite = dao.toggleFavorite(id)
            if (song != null && account.token != null) account.user?.id?.let { ownerId -> runCatching {
                account.syncFavorite(ownerId, "local:$id", isFavorite, song.title, song.artist, song.album)
            } }
        }
    }
    fun recordPlay(id: Long) = scope.launch {
        dao.recordPlay(id, System.currentTimeMillis())
        if (account.token != null) dao.songsByIds(listOf(id)).firstOrNull()?.let { song ->
            runCatching { account.recordPlay("local:$id", song.title, song.artist, song.album) }
        }
    }
    fun clearHistory() = scope.launch { dao.clearHistory(); dao.clearLastPlayed() }

    suspend fun createPlaylist(name: String, songIds: List<Long> = emptyList()): Long {
        val id = dao.insertPlaylist(com.rajdeep.listen.data.db.PlaylistEntity(name = name.trim()))
        if (songIds.isNotEmpty()) dao.addToPlaylist(id, songIds)
        return id
    }
    fun addToPlaylist(pid: Long, ids: List<Long>) = scope.launch { dao.addToPlaylist(pid, ids) }
    fun removeFromPlaylist(pid: Long, songId: Long) = scope.launch { dao.removeFromPlaylist(pid, songId) }
    fun renamePlaylist(pid: Long, name: String) = scope.launch { dao.renamePlaylist(pid, name.trim()) }
    fun deletePlaylist(pid: Long) = scope.launch { dao.deletePlaylist(pid) }
    fun reorderPlaylist(pid: Long, ids: List<Long>) = scope.launch { dao.reorderPlaylist(pid, ids) }

    // ---- video state ----
    suspend fun videoState(key: String) = dao.videoState(key)
    suspend fun saveVideoState(state: VideoStateEntity) = dao.saveVideoState(state)

    // ---- lyrics: cache → LRCLIB ----
    suspend fun lyrics(key: String, title: String, artist: String, album: String, durationMs: Long): Lyrics {
        val cached = dao.lyrics(key)
        val entity = if (cached != null && (!cached.notFound || System.currentTimeMillis() - cached.fetchedAt < 7 * 86_400_000L)) {
            cached
        } else {
            val r = try {
                Lrclib.fetch(cleanTitle(title), artist.substringBefore(",").substringBefore(" feat").trim(), album, durationMs / 1000)
            } catch (e: Exception) {
                return cached?.toLyrics() ?: Lyrics.Offline
            }
            LyricsEntity(key, r?.synced, r?.plain, r == null, System.currentTimeMillis()).also { dao.saveLyrics(it) }
        }
        return entity.toLyrics()
    }

    private fun cleanTitle(t: String) =
        t.replace(Regex("""\s*[\(\[](official|lyric|audio|video|from|feat|ft\.|remaster)[^\)\]]*[\)\]]""", RegexOption.IGNORE_CASE), "").trim()

    private fun LyricsEntity.toLyrics(): Lyrics = when {
        synced != null -> parseLrc(synced).let { if (it.isEmpty()) plain?.let(Lyrics::Plain) ?: Lyrics.None else Lyrics.Synced(it) }
        plain != null -> Lyrics.Plain(plain)
        else -> Lyrics.None
    }

    companion object {
        private val TAG = Regex("""\[(\d{1,2}):(\d{2})(?:[.:](\d{1,3}))?]""")

        fun parseLrc(lrc: String): List<LyricLine> {
            val out = ArrayList<LyricLine>()
            lrc.lineSequence().forEach { line ->
                val tags = TAG.findAll(line).toList()
                if (tags.isEmpty()) return@forEach
                val text = line.substring(tags.last().range.last + 1).trim()
                tags.forEach { m ->
                    val (mm, ss, frac) = m.destructured
                    val ms = when (frac.length) { 0 -> 0; 1 -> frac.toInt() * 100; 2 -> frac.toInt() * 10; else -> frac.take(3).toInt() }
                    out += LyricLine(mm.toLong() * 60_000 + ss.toLong() * 1000 + ms, text)
                }
            }
            return out.sortedBy { it.timeMs }
        }
    }
}
