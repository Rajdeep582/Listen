package com.rajdeep.listen.data.db

import androidx.compose.runtime.Immutable
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** Raw MediaStore audio row. Fully replaced on every scan; user data lives in [SongStatsEntity]. */
@Entity(tableName = "songs", indices = [Index("albumId"), Index("artist"), Index("folder")])
data class SongEntity(
    @PrimaryKey val id: Long,
    val title: String,
    val artist: String,
    val album: String,
    val albumId: Long,
    val albumArtist: String,
    val duration: Long,
    val uri: String,
    val folder: String,
    val track: Int,
    val year: Int,
    val size: Long,
    val mimeType: String,
    val dateAdded: Long,
    val scanGen: Long,
)

@Entity(tableName = "song_stats")
data class SongStatsEntity(
    @PrimaryKey val songId: Long,
    val isFavorite: Boolean = false,
    val playCount: Int = 0,
    val lastPlayed: Long = 0,
)

/** Song joined with user stats. Column names match the SELECT aliases in [LibraryDao]. */
@Immutable
data class Song(
    val id: Long,
    val title: String,
    val artist: String,
    val album: String,
    val albumId: Long,
    val albumArtist: String,
    val duration: Long,
    val uri: String,
    val folder: String,
    val track: Int,
    val year: Int,
    val size: Long,
    val mimeType: String,
    val dateAdded: Long,
    val isFavorite: Boolean,
    val playCount: Int,
    val lastPlayed: Long,
)

@Entity(tableName = "videos", indices = [Index("folder")])
data class VideoEntity(
    @PrimaryKey val id: Long,
    val title: String,
    val uri: String,
    val folder: String,
    val duration: Long,
    val size: Long,
    val width: Int,
    val height: Int,
    val mimeType: String,
    val dateAdded: Long,
    val scanGen: Long,
)

/** Per-video playback memory: resume point, chosen tracks, external subtitle, delays. */
@Entity(tableName = "video_state")
data class VideoStateEntity(
    @PrimaryKey val videoKey: String,
    val position: Long = 0,
    val duration: Long = 0,
    val lastWatched: Long = 0,
    val audioTrack: Int = Int.MIN_VALUE,
    val spuTrack: Int = Int.MIN_VALUE,
    val subtitleUri: String? = null,
    val spuDelayUs: Long = 0,
    val audioDelayUs: Long = 0,
    val rate: Float = 1f,
)

@Immutable
data class Video(
    val id: Long,
    val title: String,
    val uri: String,
    val folder: String,
    val duration: Long,
    val size: Long,
    val width: Int,
    val height: Int,
    val mimeType: String,
    val dateAdded: Long,
    val position: Long,
    val lastWatched: Long,
)

@Entity(tableName = "playlists")
data class PlaylistEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val createdAt: Long = System.currentTimeMillis(),
)

@Entity(tableName = "playlist_songs", primaryKeys = ["playlistId", "songId"], indices = [Index("songId")])
data class PlaylistSongEntity(
    val playlistId: Long,
    val songId: Long,
    val position: Int,
)

@Entity(tableName = "history", indices = [Index("songId"), Index("playedAt")])
data class HistoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val songId: Long,
    val playedAt: Long,
)

@Entity(tableName = "lyrics")
data class LyricsEntity(
    @PrimaryKey val key: String,
    val synced: String?,
    val plain: String?,
    val notFound: Boolean,
    val fetchedAt: Long,
)

@Immutable
data class Album(val albumId: Long, val album: String, val artist: String, val songCount: Int, val year: Int)

@Immutable
data class Artist(val artist: String, val songCount: Int, val albumCount: Int, val albumId: Long)

@Immutable
data class Playlist(val id: Long, val name: String, val songCount: Int, val coverAlbumId: Long?)

@Immutable
data class Folder(val folder: String, val count: Int)
