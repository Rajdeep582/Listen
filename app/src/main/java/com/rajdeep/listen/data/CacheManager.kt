package com.rajdeep.listen.data

import android.content.Context
import coil3.SingletonImageLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Cache housekeeping. What Listen caches:
 *  - Image disk cache (Coil, cacheDir/art, self-limited to 400 MB, LRU)
 *  - Image memory cache (Coil, 25% of app heap, LRU)
 *  - Artwork lookup results (prefs "art_cache"), recommendations (prefs "for_you"), search history.
 * No cookies are stored: the HTTP client has no CookieHandler, so nothing persists between requests.
 */
object CacheManager {
    private const val TRIM_ABOVE = 600L * 1024 * 1024

    private fun dirSize(f: File): Long = if (f.isFile) f.length() else f.listFiles()?.sumOf { dirSize(it) } ?: 0L

    suspend fun cacheBytes(ctx: Context): Long = withContext(Dispatchers.IO) { dirSize(ctx.cacheDir) + (ctx.externalCacheDir?.let(::dirSize) ?: 0L) }

    /** Called once per launch in the background: drops stray cache files if something grew too big. */
    suspend fun autoTrim(ctx: Context) = withContext(Dispatchers.IO) {
        if (dirSize(ctx.cacheDir) > TRIM_ABOVE) clear(ctx, keepArtLookups = true)
        // old external-subtitle copies (VLC can't read SAF uris, so they are copied here)
        File(ctx.filesDir, "subs").listFiles()?.filter { System.currentTimeMillis() - it.lastModified() > 30L * 86_400_000 }?.forEach { it.delete() }
    }

    suspend fun clear(ctx: Context, keepArtLookups: Boolean = false) = withContext(Dispatchers.IO) {
        val loader = SingletonImageLoader.get(ctx)
        loader.memoryCache?.clear()
        loader.diskCache?.clear()
        ctx.cacheDir.listFiles()?.forEach { it.deleteRecursively() }
        ctx.externalCacheDir?.listFiles()?.forEach { it.deleteRecursively() }
        if (!keepArtLookups) ctx.getSharedPreferences("art_cache", Context.MODE_PRIVATE).edit().clear().apply()
        ctx.getSharedPreferences("for_you", Context.MODE_PRIVATE).edit().clear().apply()
    }
}
