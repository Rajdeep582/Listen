package com.rajdeep.listen.data

import android.content.ContentUris
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Size
import coil3.ImageLoader
import coil3.asImage
import coil3.decode.DataSource
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.ImageFetchResult
import coil3.key.Keyer
import coil3.request.Options

/**
 * Artwork embedded in a local audio file or album. The legacy `albumart` URI no longer works on
 * Android 10+, so this uses ContentResolver.loadThumbnail (Q+) or MediaMetadataRetriever (older).
 */
data class LocalArt(val uri: String)

fun localAlbumArt(albumId: Long): LocalArt =
    LocalArt(ContentUris.withAppendedId(MediaStore.Audio.Albums.EXTERNAL_CONTENT_URI, albumId).toString())

class LocalArtFetcher(private val data: LocalArt, private val options: Options) : Fetcher {
    override suspend fun fetch(): FetchResult {
        val ctx = options.context
        val uri = Uri.parse(data.uri)
        val bmp = if (Build.VERSION.SDK_INT >= 29) {
            ctx.contentResolver.loadThumbnail(uri, Size(640, 640), null)
        } else if (data.uri.contains("/audio/albums/")) {
            val legacy = Uri.parse("content://media/external/audio/albumart/" + uri.lastPathSegment)
            ctx.contentResolver.openInputStream(legacy)!!.use { BitmapFactory.decodeStream(it) }!!
        } else {
            val r = MediaMetadataRetriever()
            try {
                r.setDataSource(ctx, uri)
                val bytes = r.embeddedPicture ?: error("no embedded art")
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)!!
            } finally { r.release() }
        }
        return ImageFetchResult(image = bmp.asImage(), isSampled = true, dataSource = DataSource.DISK)
    }

    class Factory : Fetcher.Factory<LocalArt> {
        override fun create(data: LocalArt, options: Options, imageLoader: ImageLoader): Fetcher = LocalArtFetcher(data, options)
    }
}

class LocalArtKeyer : Keyer<LocalArt> {
    override fun key(data: LocalArt, options: Options): String = "localart:" + data.uri
}
