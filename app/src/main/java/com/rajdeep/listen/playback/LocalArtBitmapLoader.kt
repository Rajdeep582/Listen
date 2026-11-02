package com.rajdeep.listen.playback

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.util.Size
import androidx.media3.common.util.BitmapLoader
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSourceBitmapLoader
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import java.util.concurrent.Executors

/** Notification / lock-screen artwork: embedded covers for local audio, normal loading for http art. */
@UnstableApi
class LocalArtBitmapLoader(private val context: Context) : BitmapLoader {
    private val executor = MoreExecutors.listeningDecorator(Executors.newSingleThreadExecutor())
    private val fallback = DataSourceBitmapLoader(context)

    override fun supportsMimeType(mimeType: String): Boolean = fallback.supportsMimeType(mimeType)
    override fun decodeBitmap(data: ByteArray): ListenableFuture<Bitmap> = fallback.decodeBitmap(data)

    override fun loadBitmap(uri: Uri): ListenableFuture<Bitmap> {
        val isLocalAudio = uri.scheme == "content" && uri.toString().contains("/audio/media/")
        if (!isLocalAudio || Build.VERSION.SDK_INT < 29) return fallback.loadBitmap(uri)
        return executor.submit<Bitmap> { context.contentResolver.loadThumbnail(uri, Size(512, 512), null) }
    }
}
