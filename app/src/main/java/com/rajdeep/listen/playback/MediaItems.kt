package com.rajdeep.listen.playback

import android.net.Uri
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import com.rajdeep.listen.data.db.Song
import com.rajdeep.listen.data.online.OnlineTrack

object MediaKeys {
    const val EXTRA_PREVIEW = "listen.preview"
    const val EXTRA_LIVE = "listen.live"
    const val EXTRA_SOURCE = "listen.source"
    const val EXTRA_ALBUM_ID = "listen.albumId"
    const val EXTRA_DURATION = "listen.duration"
    const val EXTRA_URI = "listen.uri"

    fun localId(mediaId: String?): Long? = mediaId?.takeIf { it.startsWith("local:") }?.removePrefix("local:")?.toLongOrNull()
}

fun albumArtUri(albumId: Long): Uri = Uri.parse("content://media/external/audio/albumart/$albumId")

fun Song.toMediaItem(): MediaItem = MediaItem.Builder()
    .setMediaId("local:$id")
    .setUri(uri)
    .setMediaMetadata(
        MediaMetadata.Builder()
            .setTitle(title)
            .setArtist(artist)
            .setAlbumTitle(album)
            .setAlbumArtist(albumArtist.ifEmpty { artist })
            // Android 10+: point at the audio file itself; LocalArtBitmapLoader reads its embedded cover
            // for the notification / lock screen (the legacy albumart URI no longer works there).
            .setArtworkUri(if (android.os.Build.VERSION.SDK_INT >= 29) Uri.parse(uri) else albumArtUri(albumId))
            .setTrackNumber(track % 1000)
            .setIsPlayable(true)
            .setIsBrowsable(false)
            .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
            .setExtras(Bundle().apply {
                putLong(MediaKeys.EXTRA_ALBUM_ID, albumId)
                putLong(MediaKeys.EXTRA_DURATION, duration)
                putString(MediaKeys.EXTRA_SOURCE, "Local")
                putString(MediaKeys.EXTRA_URI, uri)
            })
            .build()
    )
    .build()

fun OnlineTrack.toMediaItem(): MediaItem = MediaItem.Builder()
    .setMediaId(key)
    .setUri(streamUrl)
    .apply {
        // AIR / many Indian stations are HLS without a .m3u8 suffix: hint the type so ExoPlayer picks HLS.
        val u = streamUrl.lowercase()
        if (isLive && (u.contains(".m3u8") || u.contains("/hls") || u.contains("hlspb") || u.contains("chunklist"))) setMimeType(androidx.media3.common.MimeTypes.APPLICATION_M3U8)
    }
    .setMediaMetadata(
        MediaMetadata.Builder()
            .setTitle(title)
            .setArtist(artist)
            .setAlbumTitle(album)
            .setArtworkUri(artwork?.let(Uri::parse))
            .setIsPlayable(true)
            .setIsBrowsable(false)
            .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
            .setExtras(Bundle().apply {
                putBoolean(MediaKeys.EXTRA_PREVIEW, isPreview)
                putBoolean(MediaKeys.EXTRA_LIVE, isLive)
                putString(MediaKeys.EXTRA_SOURCE, source)
                putLong(MediaKeys.EXTRA_DURATION, durationMs)
            })
            .build()
    )
    .build()
