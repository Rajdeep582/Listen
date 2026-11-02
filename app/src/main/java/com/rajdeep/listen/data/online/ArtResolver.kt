package com.rajdeep.listen.data.online

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/**
 * Finds artwork online when a file has none: album covers + track art (iTunes, Deezer) and real
 * artist photos (Deezer). Results (including misses) are cached on disk so each lookup happens once.
 */
object ArtResolver {
    @Volatile var enabled: Boolean = true
    private lateinit var prefs: android.content.SharedPreferences
    private val mem = ConcurrentHashMap<String, String>()
    private val gate = Semaphore(3) // gentle on free APIs; keeps scrolling smooth
    private const val MISS = "-"
    private const val MISS_TTL = 3L * 86_400_000

    fun init(context: Context) { prefs = context.getSharedPreferences("art_cache", Context.MODE_PRIVATE) }

    sealed interface Query { val key: String }
    data class AlbumQ(val artist: String, val album: String) : Query { override val key = "al:${artist.lowercase()}|${album.lowercase()}" }
    data class TrackQ(val artist: String, val title: String) : Query { override val key = "tr:${artist.lowercase()}|${title.lowercase()}" }
    data class ArtistQ(val name: String) : Query { override val key = "ar:${name.lowercase()}" }

    /** Cached result without network (null = unknown, "" = known miss). */
    fun peek(q: Query): String? {
        mem[q.key]?.let { return it }
        if (!::prefs.isInitialized) return null
        val v = prefs.getString(q.key, null) ?: return null
        if (v.startsWith(MISS)) {
            val at = v.removePrefix(MISS).toLongOrNull() ?: 0
            return if (System.currentTimeMillis() - at < MISS_TTL) "" else null
        }
        mem[q.key] = v; return v
    }

    suspend fun resolve(q: Query): String? {
        peek(q)?.let { return it.ifEmpty { null } }
        if (!enabled || !::prefs.isInitialized) return null
        if (!worthLooking(q)) return null
        return withContext(Dispatchers.IO) {
            gate.withPermit {
                peek(q)?.let { return@withPermit it.ifEmpty { null } }
                val url = runCatching {
                    when (q) {
                        is AlbumQ -> ITunes.artwork(q.artist, q.album, "album") ?: DeezerArt.albumCover(q.artist, q.album)
                        is TrackQ -> ITunes.artwork(q.artist, q.title, "song")
                        is ArtistQ -> DeezerArt.artistPicture(q.name)
                    }
                }.getOrElse { return@withPermit null } // network error: don't cache, retry later
                if (url != null) { mem[q.key] = url; prefs.edit().putString(q.key, url).apply() }
                else prefs.edit().putString(q.key, MISS + System.currentTimeMillis()).apply()
                url
            }
        }
    }

    private fun bad(s: String) = s.isBlank() || s.lowercase().let { it.startsWith("unknown") || it == "<unknown>" }

    private fun worthLooking(q: Query) = when (q) {
        is AlbumQ -> !bad(q.album)
        is TrackQ -> !bad(q.title)
        is ArtistQ -> !bad(q.name)
    }
}
