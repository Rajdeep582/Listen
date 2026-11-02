package com.rajdeep.listen.data

import android.content.ContentUris
import android.content.Context
import android.database.Cursor
import android.os.Build
import android.provider.MediaStore
import com.rajdeep.listen.data.db.LibraryDao
import com.rajdeep.listen.data.db.SongEntity
import com.rajdeep.listen.data.db.VideoEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Reads the device MediaStore and mirrors it into Room. Incremental via scan generations. */
class MediaScanner(private val context: Context, private val dao: LibraryDao) {

    data class Result(val songs: Int, val videos: Int)

    suspend fun scan(minAudioMs: Long, scanAudio: Boolean, scanVideo: Boolean, hidden: Set<String> = emptySet()): Result = withContext(Dispatchers.IO) {
        val gen = System.currentTimeMillis()
        val songs = if (scanAudio) querySongs(gen, minAudioMs).filter { it.folder !in hidden } else null
        val videos = if (scanVideo) queryVideos(gen).filter { it.folder !in hidden } else null
        songs?.let { dao.syncSongs(it, gen) }
        videos?.let { dao.syncVideos(it, gen) }
        Result(songs?.size ?: -1, videos?.size ?: -1)
    }

    /** Every folder that contains audio or video, ignoring the hidden list (for the Settings picker). */
    suspend fun allFolders(): List<String> = withContext(Dispatchers.IO) {
        val out = sortedSetOf<String>(String.CASE_INSENSITIVE_ORDER)
        listOf(audioCollection(), videoCollection()).forEach { uri ->
            context.contentResolver.query(uri, arrayOf(folderCol()), null, null, null)?.use { c -> while (c.moveToNext()) out += c.folderOf(0) }
        }
        out.toList()
    }

    private fun audioCollection() =
        if (Build.VERSION.SDK_INT >= 29) MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        else MediaStore.Audio.Media.EXTERNAL_CONTENT_URI

    private fun videoCollection() =
        if (Build.VERSION.SDK_INT >= 29) MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        else MediaStore.Video.Media.EXTERNAL_CONTENT_URI

    @Suppress("DEPRECATION")
    private fun folderCol() = if (Build.VERSION.SDK_INT >= 29) MediaStore.MediaColumns.RELATIVE_PATH else MediaStore.MediaColumns.DATA

    private fun Cursor.folderOf(idx: Int): String {
        val raw = getString(idx) ?: return "Unknown"
        return if (Build.VERSION.SDK_INT >= 29) raw.trimEnd('/').ifEmpty { "Root" }
        else File(raw).parentFile?.name ?: "Unknown"
    }

    private fun clean(v: String?, fallback: String) =
        if (v.isNullOrBlank() || v == MediaStore.UNKNOWN_STRING) fallback else v.trim()

    private fun querySongs(gen: Long, minMs: Long): List<SongEntity> {
        val proj = arrayOf(
            MediaStore.Audio.Media._ID, MediaStore.Audio.Media.TITLE, MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM, MediaStore.Audio.Media.ALBUM_ID, "album_artist",
            MediaStore.Audio.Media.DURATION, MediaStore.Audio.Media.TRACK, MediaStore.Audio.Media.YEAR,
            MediaStore.Audio.Media.SIZE, MediaStore.Audio.Media.MIME_TYPE, MediaStore.Audio.Media.DATE_ADDED,
            folderCol(), MediaStore.Audio.Media.DISPLAY_NAME,
        )
        val sel = "${MediaStore.Audio.Media.IS_MUSIC} != 0 AND ${MediaStore.Audio.Media.DURATION} >= ?"
        val out = ArrayList<SongEntity>(2048)
        val base = audioCollection()
        context.contentResolver.query(base, proj, sel, arrayOf(minMs.toString()), null)?.use { c ->
            val iId = c.getColumnIndexOrThrow(proj[0]); val iTitle = c.getColumnIndexOrThrow(proj[1])
            val iArtist = c.getColumnIndexOrThrow(proj[2]); val iAlbum = c.getColumnIndexOrThrow(proj[3])
            val iAlbumId = c.getColumnIndexOrThrow(proj[4]); val iAlbumArtist = c.getColumnIndex(proj[5])
            val iDur = c.getColumnIndexOrThrow(proj[6]); val iTrack = c.getColumnIndexOrThrow(proj[7])
            val iYear = c.getColumnIndexOrThrow(proj[8]); val iSize = c.getColumnIndexOrThrow(proj[9])
            val iMime = c.getColumnIndexOrThrow(proj[10]); val iAdded = c.getColumnIndexOrThrow(proj[11])
            val iFolder = c.getColumnIndexOrThrow(proj[12]); val iName = c.getColumnIndexOrThrow(proj[13])
            while (c.moveToNext()) {
                val id = c.getLong(iId)
                out += SongEntity(
                    id = id,
                    title = clean(c.getString(iTitle), c.getString(iName)?.substringBeforeLast('.') ?: "Untitled"),
                    artist = clean(c.getString(iArtist), "Unknown artist"),
                    album = clean(c.getString(iAlbum), "Unknown album"),
                    albumId = c.getLong(iAlbumId),
                    albumArtist = if (iAlbumArtist >= 0) clean(c.getString(iAlbumArtist), "") else "",
                    duration = c.getLong(iDur),
                    uri = ContentUris.withAppendedId(base, id).toString(),
                    folder = c.folderOf(iFolder),
                    track = c.getInt(iTrack),
                    year = c.getInt(iYear),
                    size = c.getLong(iSize),
                    mimeType = c.getString(iMime) ?: "",
                    dateAdded = c.getLong(iAdded),
                    scanGen = gen,
                )
            }
        }
        return out
    }

    private fun queryVideos(gen: Long): List<VideoEntity> {
        val proj = arrayOf(
            MediaStore.Video.Media._ID, MediaStore.Video.Media.DISPLAY_NAME, MediaStore.Video.Media.DURATION,
            MediaStore.Video.Media.SIZE, MediaStore.Video.Media.WIDTH, MediaStore.Video.Media.HEIGHT,
            MediaStore.Video.Media.MIME_TYPE, MediaStore.Video.Media.DATE_ADDED, folderCol(),
        )
        val out = ArrayList<VideoEntity>(512)
        val base = videoCollection()
        context.contentResolver.query(base, proj, null, null, null)?.use { c ->
            val iId = c.getColumnIndexOrThrow(proj[0]); val iName = c.getColumnIndexOrThrow(proj[1])
            val iDur = c.getColumnIndexOrThrow(proj[2]); val iSize = c.getColumnIndexOrThrow(proj[3])
            val iW = c.getColumnIndexOrThrow(proj[4]); val iH = c.getColumnIndexOrThrow(proj[5])
            val iMime = c.getColumnIndexOrThrow(proj[6]); val iAdded = c.getColumnIndexOrThrow(proj[7])
            val iFolder = c.getColumnIndexOrThrow(proj[8])
            while (c.moveToNext()) {
                val id = c.getLong(iId)
                out += VideoEntity(
                    id = id,
                    title = (c.getString(iName) ?: "Video").substringBeforeLast('.'),
                    uri = ContentUris.withAppendedId(base, id).toString(),
                    folder = c.folderOf(iFolder),
                    duration = c.getLong(iDur),
                    size = c.getLong(iSize),
                    width = c.getInt(iW),
                    height = c.getInt(iH),
                    mimeType = c.getString(iMime) ?: "",
                    dateAdded = c.getLong(iAdded),
                    scanGen = gen,
                )
            }
        }
        return out
    }
}
