package com.rajdeep.listen.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

private const val SONG_COLS = """
    s.id, s.title, s.artist, s.album, s.albumId, s.albumArtist, s.duration, s.uri, s.folder,
    s.track, s.year, s.size, s.mimeType, s.dateAdded,
    IFNULL(st.isFavorite, 0) AS isFavorite, IFNULL(st.playCount, 0) AS playCount,
    IFNULL(st.lastPlayed, 0) AS lastPlayed
"""
private const val SONG_FROM = "FROM songs s LEFT JOIN song_stats st ON st.songId = s.id"

private const val VIDEO_COLS = """
    v.id, v.title, v.uri, v.folder, v.duration, v.size, v.width, v.height, v.mimeType, v.dateAdded,
    IFNULL(vs.position, 0) AS position, IFNULL(vs.lastWatched, 0) AS lastWatched
"""
private const val VIDEO_FROM = "FROM videos v LEFT JOIN video_state vs ON vs.videoKey = 'local:' || v.id"

@Dao
interface LibraryDao {
    // ---------- scan sync ----------
    @Upsert suspend fun upsertSongs(songs: List<SongEntity>)
    @Query("DELETE FROM songs WHERE scanGen != :gen") suspend fun pruneSongs(gen: Long)
    @Upsert suspend fun upsertVideos(videos: List<VideoEntity>)
    @Query("DELETE FROM videos WHERE scanGen != :gen") suspend fun pruneVideos(gen: Long)

    @Transaction
    suspend fun syncSongs(songs: List<SongEntity>, gen: Long) {
        songs.chunked(500).forEach { upsertSongs(it) }
        pruneSongs(gen)
    }

    @Transaction
    suspend fun syncVideos(videos: List<VideoEntity>, gen: Long) {
        videos.chunked(500).forEach { upsertVideos(it) }
        pruneVideos(gen)
    }

    // ---------- songs ----------
    @Query("SELECT $SONG_COLS $SONG_FROM ORDER BY s.title COLLATE NOCASE")
    fun songs(): Flow<List<Song>>

    @Query("SELECT $SONG_COLS $SONG_FROM WHERE s.id IN (:ids)")
    suspend fun songsByIds(ids: List<Long>): List<Song>

    @Query("SELECT $SONG_COLS $SONG_FROM WHERE s.id = :id")
    fun songFlow(id: Long): Flow<Song?>

    @Query("SELECT $SONG_COLS $SONG_FROM WHERE s.albumId = :albumId ORDER BY s.track, s.title COLLATE NOCASE")
    fun albumSongs(albumId: Long): Flow<List<Song>>

    @Query("SELECT $SONG_COLS $SONG_FROM WHERE s.artist = :artist ORDER BY s.album COLLATE NOCASE, s.track")
    fun artistSongs(artist: String): Flow<List<Song>>

    @Query("SELECT $SONG_COLS $SONG_FROM WHERE s.folder = :folder ORDER BY s.title COLLATE NOCASE")
    fun folderSongs(folder: String): Flow<List<Song>>

    @Query("SELECT $SONG_COLS $SONG_FROM WHERE st.isFavorite = 1 ORDER BY s.title COLLATE NOCASE")
    fun favourites(): Flow<List<Song>>
    @Query("SELECT $SONG_COLS $SONG_FROM WHERE st.isFavorite = 1 ORDER BY s.title COLLATE NOCASE")
    suspend fun allFavourites(): List<Song>

    @Query("SELECT $SONG_COLS $SONG_FROM WHERE st.lastPlayed > 0 ORDER BY st.lastPlayed DESC LIMIT :limit")
    fun recentlyPlayed(limit: Int): Flow<List<Song>>

    @Query("SELECT $SONG_COLS $SONG_FROM WHERE st.playCount > 0 ORDER BY st.playCount DESC, st.lastPlayed DESC LIMIT :limit")
    fun mostPlayed(limit: Int): Flow<List<Song>>

    @Query("SELECT $SONG_COLS $SONG_FROM ORDER BY s.dateAdded DESC LIMIT :limit")
    fun recentlyAdded(limit: Int): Flow<List<Song>>

    /** Songs never played, by artists the user plays most — simple local "discover" signal. */
    @Query(
        """SELECT $SONG_COLS $SONG_FROM
           WHERE IFNULL(st.playCount,0) = 0 AND s.artist IN (
             SELECT s2.artist FROM songs s2 JOIN song_stats st2 ON st2.songId = s2.id
             GROUP BY s2.artist ORDER BY SUM(st2.playCount) DESC LIMIT 8)
           ORDER BY RANDOM() LIMIT :limit"""
    )
    suspend fun discoverFromTopArtists(limit: Int): List<Song>

    // Favourites weigh 10× a play, so AI / suggestions lean mostly on hearted songs, then history.
    @Query("SELECT s.artist FROM songs s JOIN song_stats st ON st.songId = s.id WHERE st.playCount > 0 OR st.isFavorite = 1 GROUP BY s.artist ORDER BY SUM(st.playCount + 10 * st.isFavorite) DESC LIMIT :limit")
    suspend fun topArtistNames(limit: Int): List<String>

    @Query("SELECT $SONG_COLS $SONG_FROM WHERE st.lastPlayed > 0 OR st.isFavorite = 1 ORDER BY st.isFavorite DESC, st.lastPlayed DESC LIMIT :limit")
    suspend fun tasteSongs(limit: Int): List<Song>

    @Query("SELECT $SONG_COLS $SONG_FROM WHERE IFNULL(st.playCount,0) = 0 ORDER BY RANDOM() LIMIT :limit")
    suspend fun unheard(limit: Int): List<Song>

    @Query("SELECT $SONG_COLS $SONG_FROM WHERE s.title LIKE '%' || :q || '%' OR s.artist LIKE '%' || :q || '%' OR s.album LIKE '%' || :q || '%' ORDER BY s.title COLLATE NOCASE LIMIT 60")
    suspend fun searchSongs(q: String): List<Song>

    // ---------- albums / artists / folders ----------
    @Query(
        """SELECT albumId, album, CASE WHEN albumArtist != '' THEN albumArtist ELSE artist END AS artist,
           COUNT(*) AS songCount, MAX(year) AS year FROM songs GROUP BY albumId ORDER BY album COLLATE NOCASE"""
    )
    fun albums(): Flow<List<Album>>

    @Query(
        """SELECT albumId, album, CASE WHEN albumArtist != '' THEN albumArtist ELSE artist END AS artist,
           COUNT(*) AS songCount, MAX(year) AS year FROM songs WHERE album LIKE '%' || :q || '%'
           GROUP BY albumId ORDER BY album COLLATE NOCASE LIMIT 20"""
    )
    suspend fun searchAlbums(q: String): List<Album>

    @Query(
        """SELECT albumId, album, CASE WHEN albumArtist != '' THEN albumArtist ELSE artist END AS artist,
           COUNT(*) AS songCount, MAX(year) AS year FROM songs WHERE artist = :artist
           GROUP BY albumId ORDER BY year DESC"""
    )
    fun artistAlbums(artist: String): Flow<List<Album>>

    @Query("SELECT artist, COUNT(*) AS songCount, COUNT(DISTINCT albumId) AS albumCount, MAX(albumId) AS albumId FROM songs GROUP BY artist ORDER BY artist COLLATE NOCASE")
    fun artists(): Flow<List<Artist>>

    @Query(
        """SELECT s.artist, COUNT(*) AS songCount, COUNT(DISTINCT s.albumId) AS albumCount, MAX(s.albumId) AS albumId
           FROM songs s JOIN song_stats st ON st.songId = s.id WHERE st.playCount > 0
           GROUP BY s.artist ORDER BY SUM(st.playCount) DESC LIMIT :limit"""
    )
    fun topArtists(limit: Int): Flow<List<Artist>>

    @Query("SELECT artist, COUNT(*) AS songCount, COUNT(DISTINCT albumId) AS albumCount, MAX(albumId) AS albumId FROM songs WHERE artist LIKE '%' || :q || '%' GROUP BY artist LIMIT 20")
    suspend fun searchArtists(q: String): List<Artist>

    @Query("SELECT folder, COUNT(*) AS count FROM songs GROUP BY folder ORDER BY folder COLLATE NOCASE")
    fun songFolders(): Flow<List<Folder>>

    // ---------- stats ----------
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun ensureStats(stats: SongStatsEntity)
    @Query("UPDATE song_stats SET isFavorite = NOT isFavorite WHERE songId = :id") suspend fun flipFavorite(id: Long)
    @Query("SELECT isFavorite FROM song_stats WHERE songId = :id") suspend fun isFavorite(id: Long): Boolean?
    @Query("UPDATE song_stats SET playCount = playCount + 1, lastPlayed = :at WHERE songId = :id") suspend fun bumpPlay(id: Long, at: Long)
    @Query("UPDATE song_stats SET isFavorite = 0") suspend fun clearFavorites()
    @Query("UPDATE song_stats SET isFavorite = 1 WHERE songId IN (:ids)") suspend fun markFavorites(ids: List<Long>)
    @Insert suspend fun insertHistory(h: HistoryEntity)
    @Query("DELETE FROM history") suspend fun clearHistory()
    @Query("UPDATE song_stats SET lastPlayed = 0") suspend fun clearLastPlayed()

    @Transaction
    suspend fun toggleFavorite(id: Long): Boolean {
        ensureStats(SongStatsEntity(id)); flipFavorite(id)
        return isFavorite(id) == true
    }

    @Transaction
    suspend fun replaceFavorites(ids: List<Long>) {
        clearFavorites()
        ids.chunked(500).forEach { chunk ->
            chunk.forEach { ensureStats(SongStatsEntity(it)) }
            markFavorites(chunk)
        }
    }

    @Transaction
    suspend fun recordPlay(id: Long, at: Long) {
        ensureStats(SongStatsEntity(id)); bumpPlay(id, at); insertHistory(HistoryEntity(songId = id, playedAt = at))
    }

    // ---------- playlists ----------
    @Query(
        """SELECT p.id, p.name, COUNT(ps.songId) AS songCount,
           (SELECT s.albumId FROM playlist_songs x JOIN songs s ON s.id = x.songId WHERE x.playlistId = p.id ORDER BY x.position LIMIT 1) AS coverAlbumId
           FROM playlists p LEFT JOIN playlist_songs ps ON ps.playlistId = p.id GROUP BY p.id ORDER BY p.createdAt DESC"""
    )
    fun playlists(): Flow<List<Playlist>>

    @Query("SELECT $SONG_COLS FROM playlist_songs ps JOIN songs s ON s.id = ps.songId LEFT JOIN song_stats st ON st.songId = s.id WHERE ps.playlistId = :id ORDER BY ps.position")
    fun playlistSongs(id: Long): Flow<List<Song>>

    @Query("SELECT name FROM playlists WHERE id = :id") fun playlistName(id: Long): Flow<String?>
    @Insert suspend fun insertPlaylist(p: PlaylistEntity): Long
    @Query("UPDATE playlists SET name = :name WHERE id = :id") suspend fun renamePlaylist(id: Long, name: String)
    @Query("DELETE FROM playlists WHERE id = :id") suspend fun deletePlaylistRow(id: Long)
    @Query("DELETE FROM playlist_songs WHERE playlistId = :id") suspend fun clearPlaylist(id: Long)
    @Query("SELECT IFNULL(MAX(position), -1) + 1 FROM playlist_songs WHERE playlistId = :id") suspend fun nextPosition(id: Long): Int
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertPlaylistSong(e: PlaylistSongEntity)
    @Query("DELETE FROM playlist_songs WHERE playlistId = :pid AND songId = :sid") suspend fun removeFromPlaylist(pid: Long, sid: Long)
    @Upsert suspend fun upsertPlaylistSongs(items: List<PlaylistSongEntity>)

    @Transaction
    suspend fun deletePlaylist(id: Long) { clearPlaylist(id); deletePlaylistRow(id) }

    @Transaction
    suspend fun addToPlaylist(pid: Long, songIds: List<Long>) {
        var pos = nextPosition(pid)
        songIds.forEach { insertPlaylistSong(PlaylistSongEntity(pid, it, pos++)) }
    }

    @Transaction
    suspend fun reorderPlaylist(pid: Long, orderedIds: List<Long>) {
        upsertPlaylistSongs(orderedIds.mapIndexed { i, id -> PlaylistSongEntity(pid, id, i) })
    }

    // ---------- videos ----------
    @Query("SELECT $VIDEO_COLS $VIDEO_FROM ORDER BY v.dateAdded DESC")
    fun videos(): Flow<List<Video>>

    @Query("SELECT $VIDEO_COLS $VIDEO_FROM WHERE v.folder = :folder ORDER BY v.title COLLATE NOCASE")
    fun folderVideos(folder: String): Flow<List<Video>>

    @Query("SELECT folder, COUNT(*) AS count FROM videos GROUP BY folder ORDER BY folder COLLATE NOCASE")
    fun videoFolders(): Flow<List<Folder>>

    @Query("SELECT $VIDEO_COLS $VIDEO_FROM WHERE vs.lastWatched > 0 AND vs.position > 5000 AND (vs.duration = 0 OR vs.position < vs.duration - 10000) ORDER BY vs.lastWatched DESC LIMIT 20")
    fun continueWatching(): Flow<List<Video>>

    @Query("SELECT $VIDEO_COLS $VIDEO_FROM WHERE v.title LIKE '%' || :q || '%' OR v.folder LIKE '%' || :q || '%' LIMIT 40")
    suspend fun searchVideos(q: String): List<Video>

    @Query("SELECT * FROM video_state WHERE videoKey = :key") suspend fun videoState(key: String): VideoStateEntity?
    @Upsert suspend fun saveVideoState(state: VideoStateEntity)

    // ---------- lyrics ----------
    @Query("SELECT * FROM lyrics WHERE `key` = :key") suspend fun lyrics(key: String): LyricsEntity?
    @Upsert suspend fun saveLyrics(l: LyricsEntity)
}
