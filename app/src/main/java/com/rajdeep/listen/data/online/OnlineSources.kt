/*
 * ============================================================================================
 *  LISTEN — ONLINE API MAP  (read this before changing any network source)
 * ============================================================================================
 *  Every external service lives in its own `object` below. To swap or upgrade a source, edit
 *  ONLY that object's BASE / endpoint strings and its `parse()` function. Everything else in the
 *  app consumes the neutral [OnlineTrack] model, so UI/playback code never needs to change.
 *
 *  [API-1] Audius        BASE https://api.audius.co/v1     auth: none (app_name query param)
 *          /tracks/trending, /tracks/search, /tracks/{id}/stream      → full-length streams
 *  [API-2] Deezer        BASE https://api.deezer.com       auth: none
 *          /chart/0/tracks, /search                                    → 30 s previews only
 *  [API-3] Jamendo       BASE https://api.jamendo.com/v3.0 auth: client_id (secrets.properties
 *          JAMENDO_CLIENT_ID → BuildConfig) /tracks                    → full CC streams
 *  [API-4] RadioBrowser  BASE https://all.api.radio-browser.info/json  auth: none
 *          /stations/search                                            → live radio streams
 *  [API-5] iTunes Search https://itunes.apple.com/search   auth: none  → previews + artwork
 *  [API-6] DeezerArt     /search/artist, /search/album                 → artist photos, covers
 *  [API-7] InternetArchive advancedsearch.php + /metadata/{id}         → free recordings
 *  [API-8] LRCLIB        https://lrclib.net/api/get|search             → synced lyrics
 *  [API-9] Query suggest  (QuerySuggest.kt) Google suggest, ds=yt      → typing completions
 *  [API-10] AI           (AiClient.kt) Groq (key) or Pollinations (keyless), OpenAI-compatible
 *  Shared HTTP client: [Http] below (timeouts, User-Agent). Artwork resolution: ArtResolver.kt.
 * ============================================================================================
 */
package com.rajdeep.listen.data.online

import androidx.compose.runtime.Immutable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

@Immutable
data class OnlineTrack(
    val key: String,
    val title: String,
    val artist: String,
    val album: String,
    val artwork: String?,
    val durationMs: Long,
    val streamUrl: String,
    /** true = 30 s preview only (Deezer). Shown with a "Preview" badge in UI. */
    val isPreview: Boolean,
    val source: String,
    /** Live radio stream: no duration, not seekable. */
    val isLive: Boolean = false,
    /** Free-form detail (radio: codec · bitrate). */
    val extra: String = "",
)

internal object Http {
    private const val UA = "Listen/1.0 (personal Android player)"

    /** Returns body or null for 404. Throws IOException on other failures. */
    suspend fun get(url: String): String? = withContext(Dispatchers.IO) {
        val c = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 8000; readTimeout = 12000
            setRequestProperty("User-Agent", UA); setRequestProperty("Accept", "application/json")
        }
        try {
            when (val code = c.responseCode) {
                in 200..299 -> c.inputStream.bufferedReader().use { it.readText() }
                404 -> null
                else -> throw IOException("HTTP $code")
            }
        } finally { c.disconnect() }
    }

    /** JSON POST (used by AiClient). Returns body or throws. */
    suspend fun postJson(url: String, json: String, headers: Map<String, String> = emptyMap(), timeoutMs: Int = 8000): String = withContext(Dispatchers.IO) {
        val c = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"; doOutput = true
            connectTimeout = timeoutMs; readTimeout = timeoutMs
            setRequestProperty("User-Agent", UA); setRequestProperty("Content-Type", "application/json")
            headers.forEach { (k, v) -> setRequestProperty(k, v) }
        }
        try {
            c.outputStream.use { it.write(json.toByteArray()) }
            val code = c.responseCode
            if (code !in 200..299) throw IOException("HTTP $code")
            c.inputStream.bufferedReader().use { it.readText() }
        } finally { c.disconnect() }
    }

    fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")
}

private inline fun <T> JSONArray.mapObj(f: (JSONObject) -> T?): List<T> =
    (0 until length()).mapNotNull { optJSONObject(it)?.let(f) }

/** [API-1] Audius: open, free, full-length streaming. https://docs.audius.org */
object Audius {
    private const val BASE = "https://api.audius.co/v1"
    private const val APP = "app_name=Listen"

    private fun parse(o: JSONObject): OnlineTrack? {
        val id = o.optString("id").ifEmpty { return null }
        if (o.optBoolean("is_streamable", true).not()) return null
        val art = o.optJSONObject("artwork")
        return OnlineTrack(
            key = "audius:$id",
            title = o.optString("title"),
            artist = o.optJSONObject("user")?.optString("name").orEmpty(),
            album = o.optString("genre").ifEmpty { "Audius" },
            artwork = art?.optString("480x480")?.ifEmpty { null } ?: art?.optString("150x150")?.ifEmpty { null },
            durationMs = o.optLong("duration") * 1000,
            streamUrl = "$BASE/tracks/$id/stream?$APP",
            isPreview = false,
            source = "Audius",
        )
    }

    suspend fun trending(genre: String? = null): List<OnlineTrack> {
        val g = genre?.let { "&genre=${Http.enc(it)}" }.orEmpty()
        val body = Http.get("$BASE/tracks/trending?time=week&$APP$g") ?: return emptyList()
        return JSONObject(body).optJSONArray("data")?.mapObj(::parse).orEmpty()
    }

    suspend fun search(q: String): List<OnlineTrack> {
        val body = Http.get("$BASE/tracks/search?query=${Http.enc(q)}&$APP") ?: return emptyList()
        return JSONObject(body).optJSONArray("data")?.mapObj(::parse).orEmpty()
    }
}

/** [API-2] Deezer public API: free charts + metadata + 30 s previews only. */
object Deezer {
    private const val BASE = "https://api.deezer.com"

    private fun parse(o: JSONObject): OnlineTrack? {
        val preview = o.optString("preview").ifEmpty { return null }
        val album = o.optJSONObject("album")
        return OnlineTrack(
            key = "deezer:${o.optLong("id")}",
            title = o.optString("title"),
            artist = o.optJSONObject("artist")?.optString("name").orEmpty(),
            album = album?.optString("title").orEmpty(),
            artwork = album?.optString("cover_xl")?.ifEmpty { null } ?: album?.optString("cover_medium"),
            durationMs = 30_000,
            streamUrl = preview,
            isPreview = true,
            source = "Deezer",
        )
    }

    suspend fun chart(): List<OnlineTrack> {
        val body = Http.get("$BASE/chart/0/tracks?limit=40") ?: return emptyList()
        return JSONObject(body).optJSONArray("data")?.mapObj(::parse).orEmpty()
    }

    suspend fun search(q: String): List<OnlineTrack> {
        val body = Http.get("$BASE/search?q=${Http.enc(q)}&limit=25") ?: return emptyList()
        return JSONObject(body).optJSONArray("data")?.mapObj(::parse).orEmpty()
    }
}

/** [API-3] Jamendo: ~600k full Creative Commons tracks. Free for non-commercial use; credit artist + Jamendo. */
object Jamendo {
    private const val BASE = "https://api.jamendo.com/v3.0"
    private val id get() = com.rajdeep.listen.BuildConfig.JAMENDO_CLIENT_ID
    val enabled get() = id.isNotBlank()

    private fun parse(o: JSONObject): OnlineTrack? {
        val audio = o.optString("audio").ifEmpty { return null }
        return OnlineTrack(
            key = "jamendo:${o.optString("id")}",
            title = o.optString("name"),
            artist = o.optString("artist_name"),
            album = o.optString("album_name").ifEmpty { "Jamendo" },
            artwork = o.optString("album_image").ifEmpty { o.optString("image") }.ifEmpty { null },
            durationMs = o.optLong("duration") * 1000,
            streamUrl = audio,
            isPreview = false,
            source = "Jamendo",
        )
    }

    private suspend fun tracks(query: String): List<OnlineTrack> {
        if (!enabled) return emptyList()
        val body = Http.get("$BASE/tracks/?client_id=$id&format=json&limit=40&audioformat=mp32&imagesize=500&$query") ?: return emptyList()
        return JSONObject(body).optJSONArray("results")?.mapObj(::parse).orEmpty()
    }

    suspend fun popular(tag: String? = null) = tracks("order=popularity_week" + (tag?.let { "&tags=${Http.enc(it)}" } ?: ""))
    suspend fun search(q: String) = tracks("search=${Http.enc(q)}&order=relevance")
}

/** [API-4] Radio Browser: open directory of 40k+ live internet radio stations. https://api.radio-browser.info */
object RadioBrowser {
    private const val BASE = "https://all.api.radio-browser.info/json"

    private fun parse(o: JSONObject): OnlineTrack? {
        val url = o.optString("url_resolved").ifEmpty { o.optString("url") }.ifEmpty { return null }
        val tags = o.optString("tags").split(',').filter { it.isNotBlank() }.take(2).joinToString(" · ")
        return OnlineTrack(
            key = "radio:${o.optString("stationuuid")}",
            title = o.optString("name").trim(),
            artist = listOf(o.optString("country"), tags).filter { it.isNotBlank() }.joinToString(" · ").ifEmpty { "Live radio" },
            album = "Live radio",
            artwork = o.optString("favicon").ifEmpty { null },
            durationMs = 0,
            streamUrl = url,
            isPreview = false,
            source = "Radio",
            isLive = true,
            extra = listOfNotNull(o.optString("codec").takeIf { it.isNotBlank() && it != "UNKNOWN" }, o.optInt("bitrate").takeIf { it > 0 }?.let { "$it kbps" }).joinToString(" · "),
        )
    }

    private suspend fun stations(query: String): List<OnlineTrack> {
        val body = Http.get("$BASE/stations/search?hidebroken=true&order=clickcount&reverse=true&limit=40&$query") ?: return emptyList()
        return JSONArray(body).mapObj(::parse)
    }

    suspend fun top(countryCode: String? = null) = stations(countryCode?.let { "countrycode=$it" } ?: "")
    suspend fun byTag(tag: String) = stations("tag=${Http.enc(tag)}")
    suspend fun search(q: String) = stations("name=${Http.enc(q)}")

    /** All India Radio / Akashvani / Vividh Bharati, merged and de-duplicated. */
    suspend fun allIndiaRadio(): List<OnlineTrack> = coroutineScope {
        listOf("all india radio", "akashvani", "vividh bharati", "AIR FM")
            .map { q -> async { runCatching { stations("name=${Http.enc(q)}") }.getOrDefault(emptyList()) } }
            .flatMap { it.await() }
            .distinctBy { it.streamUrl.substringBefore('?').lowercase() }
    }
}

/** [API-5] iTunes Search: free, keyless. Huge catalogue incl. Bollywood; 30 s previews + high-res artwork. */
object ITunes {
    private fun art(o: JSONObject, size: Int = 600) = o.optString("artworkUrl100").ifEmpty { null }?.replace("100x100bb", "${size}x${size}bb")

    suspend fun search(q: String): List<OnlineTrack> {
        val body = Http.get("https://itunes.apple.com/search?term=${Http.enc(q)}&entity=song&limit=25&country=IN") ?: return emptyList()
        return JSONObject(body).optJSONArray("results")?.mapObj { o ->
            val preview = o.optString("previewUrl").ifEmpty { return@mapObj null }
            OnlineTrack(
                key = "itunes:${o.optLong("trackId")}",
                title = o.optString("trackName"),
                artist = o.optString("artistName"),
                album = o.optString("collectionName"),
                artwork = art(o),
                durationMs = 30_000,
                streamUrl = preview,
                isPreview = true,
                source = "iTunes",
            )
        }.orEmpty()
    }

    /** Best artwork for an album or track; null if nothing plausible. */
    suspend fun artwork(artist: String, title: String, entity: String): String? {
        val body = Http.get("https://itunes.apple.com/search?term=${Http.enc("$artist $title")}&entity=$entity&limit=5&country=IN") ?: return null
        val arr = JSONObject(body).optJSONArray("results") ?: return null
        val a = artist.lowercase().substringBefore(',').substringBefore('&').trim()
        val hit = arr.mapObj { it }.firstOrNull { o -> a.isEmpty() || a.startsWith("unknown") || o.optString("artistName").lowercase().contains(a.take(12)) }
            ?: arr.mapObj { it }.firstOrNull()
        return hit?.let { art(it) }
    }
}

/** [API-6] Deezer artist photos (free, keyless). */
object DeezerArt {
    suspend fun artistPicture(name: String): String? {
        val body = Http.get("https://api.deezer.com/search/artist?q=${Http.enc(name)}&limit=3") ?: return null
        val arr = JSONObject(body).optJSONArray("data") ?: return null
        val n = name.lowercase().trim()
        val best = arr.mapObj { it }.firstOrNull { it.optString("name").lowercase().trim() == n } ?: arr.optJSONObject(0)
        return best?.optString("picture_xl")?.ifEmpty { null }?.takeUnless { it.contains("/artist//") }
    }

    suspend fun albumCover(artist: String, album: String): String? {
        val body = Http.get("https://api.deezer.com/search/album?q=${Http.enc("$artist $album")}&limit=3") ?: return null
        return JSONObject(body).optJSONArray("data")?.optJSONObject(0)?.optString("cover_xl")?.ifEmpty { null }
    }
}

/** [API-7] Internet Archive: millions of free audio items (netlabels, live concerts, classical, old recordings). */
object InternetArchive {
    data class Item(val id: String, val title: String, val creator: String) {
        val artwork get() = "https://archive.org/services/img/$id"
    }

    suspend fun search(q: String): List<Item> {
        val query = "mediatype:audio AND (title:(${q}) OR creator:(${q}))"
        val body = Http.get(
            "https://archive.org/advancedsearch.php?q=${Http.enc(query)}&fl[]=identifier&fl[]=title&fl[]=creator&sort[]=downloads+desc&rows=30&output=json"
        ) ?: return emptyList()
        val docs = JSONObject(body).optJSONObject("response")?.optJSONArray("docs") ?: return emptyList()
        return docs.mapObj { d ->
            val creator = d.opt("creator").let { if (it is JSONArray) it.optString(0) else it?.toString().orEmpty() }
            Item(d.optString("identifier"), d.optString("title"), creator.ifEmpty { "Internet Archive" })
        }
    }

    /** Resolves an item into its playable MP3 tracks. */
    suspend fun tracks(item: Item): List<OnlineTrack> {
        val body = Http.get("https://archive.org/metadata/${item.id}") ?: return emptyList()
        val json = JSONObject(body)
        val server = json.optString("d1").ifEmpty { "archive.org" }
        val dir = json.optString("dir")
        val files = json.optJSONArray("files") ?: return emptyList()
        return files.mapObj { f ->
            val name = f.optString("name")
            val fmt = f.optString("format")
            if (!(fmt.contains("MP3", true) || name.endsWith(".mp3", true))) return@mapObj null
            val secs = f.optString("length").let { l -> if (l.contains(':')) l.split(':').fold(0.0) { a, p -> a * 60 + (p.toDoubleOrNull() ?: 0.0) } else l.toDoubleOrNull() ?: 0.0 }
            OnlineTrack(
                key = "archive:${item.id}/$name",
                title = f.optString("title").ifEmpty { name.substringBeforeLast('.') },
                artist = f.optString("creator").ifEmpty { item.creator },
                album = item.title,
                artwork = item.artwork,
                durationMs = (secs * 1000).toLong(),
                streamUrl = if (dir.isNotEmpty()) "https://$server$dir/${Http.enc(name).replace("+", "%20")}" else "https://archive.org/download/${item.id}/${Http.enc(name).replace("+", "%20")}",
                isPreview = false,
                source = "Archive",
            )
        }.distinctBy { it.title }
    }
}

object OnlineCatalog {
    suspend fun search(q: String): List<OnlineTrack> = coroutineScope {
        val a = async { runCatching { Audius.search(q) }.getOrDefault(emptyList()) }
        val j = async { runCatching { Jamendo.search(q) }.getOrDefault(emptyList()) }
        val d = async { runCatching { Deezer.search(q) }.getOrDefault(emptyList()) }
        val i = async { runCatching { ITunes.search(q) }.getOrDefault(emptyList()) }
        // full tracks first, previews last; drop preview duplicates of the same song
        val full = a.await().take(15) + j.await().take(15)
        val previews = (i.await() + d.await()).distinctBy { (it.title.lowercase().take(24)) + "|" + it.artist.lowercase().take(16) }
        full + previews.take(30)
    }
}

/** [API-8] LRCLIB: free community lyrics database with time-synced LRC. https://lrclib.net */
object Lrclib {
    data class Result(val synced: String?, val plain: String?)

    suspend fun fetch(title: String, artist: String, album: String, durationSec: Long): Result? {
        val exact = Http.get(
            "https://lrclib.net/api/get?track_name=${Http.enc(title)}&artist_name=${Http.enc(artist)}" +
                "&album_name=${Http.enc(album)}&duration=$durationSec"
        )
        if (exact != null) return JSONObject(exact).toResult()
        val search = Http.get("https://lrclib.net/api/search?track_name=${Http.enc(title)}&artist_name=${Http.enc(artist)}")
            ?: return null
        val arr = JSONArray(search)
        if (arr.length() == 0) return null
        // prefer synced + closest duration
        val best = arr.mapObj { it }.minByOrNull {
            val d = kotlin.math.abs(it.optDouble("duration", 0.0) - durationSec)
            d + if (it.optString("syncedLyrics").isNullOrEmpty() || it.isNull("syncedLyrics")) 1000 else 0
        } ?: return null
        return best.toResult()
    }

    private fun JSONObject.toResult(): Result? {
        val s = if (isNull("syncedLyrics")) null else optString("syncedLyrics").ifBlank { null }
        val p = if (isNull("plainLyrics")) null else optString("plainLyrics").ifBlank { null }
        return if (s == null && p == null) null else Result(s, p)
    }
}
