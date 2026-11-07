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
 *  [API-11] ccMixter     https://ccmixter.org/api/query  auth: none  → full CC remixes/originals
 *  [API-12] ReccoBeats   https://api.reccobeats.com/v1   auth: none  → recommendations (metadata
 *          only; Spotify-ID based). Used by Recommender.kt, resolved to playable via other APIs.
 *  [API-13] JioSaavn     https://www.jiosaavn.com/api.php auth: none → Bollywood/Hindi/Indian 320 kbps songs
 *  [API-14] FreeToUse    https://api.freetouse.com/v3    auth: none  → Lo-Fi, Chill, Beats full MP3 streams
 *  [API-15] Incompetech  https://incompetech.com         auth: none  → Kevin MacLeod CC-BY 1,440+ tracks
 *  [API-16] LyricsOvh    https://api.lyrics.ovh/v1       auth: none  → Plain text lyrics fallback
 *  [API-17] Openverse    https://api.openverse.org/v1    auth: none  → Creative Commons & public domain full tracks
 *  Shared HTTP client: [Http] below (timeouts, User-Agent). Artwork resolution: ArtResolver.kt.
 * ============================================================================================
 */
package com.rajdeep.listen.data.online

import android.util.Base64
import androidx.compose.runtime.Immutable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

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
    suspend fun search(q: String) = tracks("fuzzysearch=${Http.enc(q)}&order=relevance")
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

/** [API-11] ccMixter: free Creative Commons music, full-length MP3 streams, keyless. */
object CcMixter {
    private const val BASE = "https://ccmixter.org/api/query"

    private fun secs(ps: String): Long = ps.split(':').fold(0L) { a, p -> a * 60 + (p.toLongOrNull() ?: 0) }

    private fun parse(o: JSONObject): OnlineTrack? {
        val files = o.optJSONArray("files") ?: return null
        val f = (0 until files.length()).mapNotNull { files.optJSONObject(it) }
            .firstOrNull { it.optString("download_url").endsWith(".mp3", true) } ?: return null
        return OnlineTrack(
            key = "ccmixter:${o.optLong("upload_id")}",
            title = o.optString("upload_name"),
            artist = o.optString("user_real_name").ifEmpty { o.optString("user_name") },
            album = "ccMixter",
            artwork = null, // no cover art: ArtResolver / placeholder handles it
            durationMs = secs(f.optJSONObject("file_format_info")?.optString("ps").orEmpty()) * 1000,
            streamUrl = f.optString("download_url"),
            isPreview = false,
            source = "ccMixter",
        )
    }

    suspend fun search(q: String): List<OnlineTrack> {
        val body = Http.get("$BASE?f=json&sort=rank&limit=20&search=${Http.enc(q)}") ?: return emptyList()
        return JSONArray(body).mapObj(::parse)
    }
}

/** [API-12] ReccoBeats: free, keyless track recommendations (metadata only, Spotify IDs as seeds). */
object ReccoBeats {
    private const val BASE = "https://api.reccobeats.com/v1"

    private fun spotifyId(href: String) = href.substringAfterLast('/').substringBefore('?').ifEmpty { null }

    /** Finds a Spotify track ID for a song, to use as a recommendation seed. */
    suspend fun seedFor(title: String, artist: String): String? {
        val q1 = Http.get("$BASE/track/search?searchText=${Http.enc("$title $artist")}")
        val arr1 = q1?.let { runCatching { JSONObject(it).optJSONArray("content") }.getOrNull() }
        val arr = if (arr1 != null && arr1.length() > 0) arr1 else {
            val q2 = Http.get("$BASE/track/search?searchText=${Http.enc(title)}")
            q2?.let { runCatching { JSONObject(it).optJSONArray("content") }.getOrNull() } ?: return null
        }
        val a = artist.lowercase().substringBefore(',').trim()
        val hit = arr.mapObj { it }.firstOrNull { o -> o.optJSONArray("artists")?.optJSONObject(0)?.optString("name").orEmpty().lowercase().contains(a.take(10)) }
            ?: arr.optJSONObject(0)
        return hit?.optString("href")?.let(::spotifyId)
    }

    /** Up to [size] recommended (title, artist) pairs for the given seed IDs (max 5). */
    suspend fun recommend(seeds: List<String>, size: Int = 30): List<Pair<String, String>> {
        if (seeds.isEmpty()) return emptyList()
        val body = Http.get("$BASE/track/recommendation?size=$size&seeds=${seeds.take(5).joinToString(",")}") ?: return emptyList()
        return JSONObject(body).optJSONArray("content")?.mapObj { o ->
            val artist = o.optJSONArray("artists")?.optJSONObject(0)?.optString("name").orEmpty()
            o.optString("trackTitle").takeIf { it.isNotBlank() && artist.isNotBlank() }?.let { it to artist }
        }.orEmpty()
    }
}

/**
 * [API-13] JioSaavn: official public endpoints for Bollywood/Hindi & Indian music.
 * Delivers full-length, high-fidelity 320 kbps MP4/AAC streams, metadata, and album artwork.
 */
object JioSaavn {
    private const val BASE = "https://www.jiosaavn.com/api.php"
    const val SOURCE = "JioSaavn"
    private const val CIPHER_KEY = "38346591"

    /** Decrypts JioSaavn's DES-ECB encrypted media URL and requests 320 kbps stream. */
    fun decryptMediaUrl(enc: String): String? = runCatching {
        if (enc.isBlank()) return@runCatching null
        val key = SecretKeySpec(CIPHER_KEY.toByteArray(Charsets.UTF_8), "DES")
        val cipher = Cipher.getInstance("DES/ECB/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, key)
        val decoded = Base64.decode(enc, Base64.DEFAULT)
        val decrypted = String(cipher.doFinal(decoded), Charsets.UTF_8)
        decrypted.replace("_96.mp4", "_320.mp4")
            .replace("_96_p.mp4", "_320.mp4")
            .replace("_160.mp4", "_320.mp4")
    }.getOrNull()

    private fun unescape(s: String): String =
        s.replace("&quot;", "\"")
            .replace("&#039;", "'")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .trim()

    private fun parseTrack(o: JSONObject): OnlineTrack? {
        val id = o.optString("id").ifEmpty { return null }
        val title = unescape(o.optString("song").ifEmpty { o.optString("title") })
        if (title.isBlank()) return null
        val singer = unescape(o.optString("singers").ifEmpty {
            o.optString("primary_artists").ifEmpty { o.optString("description") }
        })
        val album = unescape(o.optString("album").ifEmpty { "JioSaavn" })
        val image = o.optString("image").replace("150x150", "500x500").replace("50x50", "500x500").ifEmpty { null }
        val durationMs = o.optLong("duration") * 1000L

        val enc = o.optString("encrypted_media_url")
        val streamUrl = decryptMediaUrl(enc) ?: o.optString("media_preview_url").ifEmpty { null }
        if (streamUrl.isNullOrBlank()) return null

        return OnlineTrack(
            key = "saavn:$id",
            title = title,
            artist = singer.ifEmpty { "Various Artists" },
            album = album,
            artwork = image,
            durationMs = durationMs,
            streamUrl = streamUrl,
            isPreview = false,
            source = SOURCE,
        )
    }

    /** Search tracks via JioSaavn getResults API, with fallback to autocomplete + song details. */
    suspend fun search(q: String): List<OnlineTrack> = withContext(Dispatchers.IO) {
        val searchUrl = "$BASE?__call=search.getResults&_format=json&_marker=0&cc=in&includeMetaTags=1&p=1&n=20&q=${Http.enc(q)}"
        val body = Http.get(searchUrl)
        if (!body.isNullOrBlank()) {
            val results = runCatching { JSONObject(body).optJSONArray("results") }.getOrNull()
            if (results != null && results.length() > 0) {
                val list = (0 until results.length()).mapNotNull { results.optJSONObject(it)?.let(::parseTrack) }
                if (list.isNotEmpty()) return@withContext list
            }
        }

        // Fallback: autocomplete.get -> fetch details for found IDs
        val autoUrl = "$BASE?__call=autocomplete.get&_format=json&_marker=0&cc=in&includeMetaTags=1&query=${Http.enc(q)}"
        val autoBody = Http.get(autoUrl) ?: return@withContext emptyList()
        val songArr = runCatching {
            JSONObject(autoBody).optJSONObject("songs")?.optJSONArray("data")
        }.getOrNull() ?: return@withContext emptyList()

        val pids = (0 until songArr.length()).mapNotNull { songArr.optJSONObject(it)?.optString("id") }.filter { it.isNotBlank() }
        if (pids.isEmpty()) return@withContext emptyList()

        val detailUrl = "$BASE?__call=song.getDetails&pids=${pids.joinToString(",")}&_format=json"
        val detailBody = Http.get(detailUrl) ?: return@withContext emptyList()
        val detailObj = runCatching { JSONObject(detailBody) }.getOrNull() ?: return@withContext emptyList()

        pids.mapNotNull { pid ->
            detailObj.optJSONObject(pid)?.let(::parseTrack)
        }
    }

    /** Top Hindi hits playlist for trending recommendations. */
    suspend fun topHindi(): List<OnlineTrack> = withContext(Dispatchers.IO) {
        val url = "$BASE?__call=playlist.getDetails&listid=1134543272&_format=json"
        val body = Http.get(url) ?: return@withContext emptyList()
        val songs = runCatching { JSONObject(body).optJSONArray("songs") }.getOrNull() ?: return@withContext emptyList()
        (0 until songs.length()).mapNotNull { songs.optJSONObject(it)?.let(::parseTrack) }
    }
}

/**
 * [API-14] Free To Use Music: Open, keyless music API (OpenAPI 3.0).
 * High-quality Lo-Fi, Chill, Ambient, Beats, Cinematic, and Electronic tracks
 * under royalty-free / Creative Commons licenses. Direct MP3 files and WebP artwork.
 */
object FreeToUse {
    private const val BASE = "https://api.freetouse.com/v3/music/tracks"
    const val SOURCE = "FreeToUse"

    private fun parseArtists(arr: JSONArray?): String {
        if (arr == null) return "Free To Use"
        val names = mutableListOf<String>()
        for (i in 0 until arr.length()) {
            val elem = arr.opt(i)
            if (elem is JSONArray && elem.length() > 1) {
                elem.optJSONObject(1)?.optString("name")?.takeIf { it.isNotBlank() }?.let { names.add(it) }
            } else if (elem is JSONObject) {
                elem.optString("name").takeIf { it.isNotBlank() }?.let { names.add(it) }
            }
        }
        return if (names.isEmpty()) "Free To Use" else names.joinToString(", ")
    }

    private fun parse(o: JSONObject): OnlineTrack? {
        val id = o.optString("id").ifEmpty { return null }
        val title = o.optString("title").ifEmpty { return null }
        val files = o.optJSONObject("files")
        val streamUrl = files?.optString("mp3")?.ifEmpty { null } ?: return null
        val thumbs = o.optJSONObject("thumbnails")
        val art = thumbs?.optString("xl")?.ifEmpty { null }
            ?: thumbs?.optString("lg")?.ifEmpty { null }
            ?: thumbs?.optString("md")?.ifEmpty { null }
        val durationMs = (o.optDouble("duration", 0.0) * 1000).toLong()
        val genre = o.optString("genre").ifEmpty { "FreeToUse" }

        return OnlineTrack(
            key = "ftu:$id",
            title = title,
            artist = parseArtists(o.optJSONArray("artists")),
            album = genre,
            artwork = art,
            durationMs = durationMs,
            streamUrl = streamUrl,
            isPreview = false,
            source = SOURCE,
        )
    }

    suspend fun search(q: String): List<OnlineTrack> = withContext(Dispatchers.IO) {
        val body = Http.get("$BASE/search?query=${Http.enc(q)}") ?: return@withContext emptyList()
        val arr = runCatching { JSONObject(body).optJSONArray("data") }.getOrNull() ?: return@withContext emptyList()
        (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.let(::parse) }
    }

    suspend fun popular(): List<OnlineTrack> = withContext(Dispatchers.IO) {
        val body = Http.get("$BASE/search?query=") ?: return@withContext emptyList()
        val arr = runCatching { JSONObject(body).optJSONArray("data") }.getOrNull() ?: return@withContext emptyList()
        (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.let(::parse) }
    }
}

/**
 * [API-15] Incompetech: 1,440+ Creative Commons (CC-BY) compositions by Kevin MacLeod.
 * Covers Classical, Orchestral, Soundtrack, Film Score, Jazz, Celtic, Ambient, and World music.
 * Direct MP3 streaming, byte-range seekable. Keyless.
 */
object Incompetech {
    private const val CATALOG_URL = "https://incompetech.com/music/royalty-free/pieces.json"
    private const val STREAM_BASE = "https://incompetech.com/music/royalty-free/mp3-royaltyfree"
    const val SOURCE = "Incompetech"

    @Volatile
    private var cachedPieces: List<Piece>? = null

    private data class Piece(
        val title: String,
        val filename: String,
        val lengthMs: Long,
        val genre: String,
        val feel: String,
        val instruments: String,
        val description: String,
    ) {
        fun toOnlineTrack(): OnlineTrack {
            val encodedName = Http.enc(filename).replace("+", "%20")
            return OnlineTrack(
                key = "incompetech:${Http.enc(title)}",
                title = title,
                artist = "Kevin MacLeod",
                album = feel.ifEmpty { "Incompetech" },
                artwork = null,
                durationMs = lengthMs,
                streamUrl = "$STREAM_BASE/$encodedName",
                isPreview = false,
                source = SOURCE,
                extra = listOfNotNull(genre.takeIf { it.isNotBlank() }, instruments.takeIf { it.isNotBlank() }).joinToString(" • "),
            )
        }
    }

    private fun parseLength(s: String): Long {
        val parts = s.split(':').mapNotNull { it.toLongOrNull() }
        return when (parts.size) {
            3 -> (parts[0] * 3600 + parts[1] * 60 + parts[2]) * 1000L
            2 -> (parts[0] * 60 + parts[1]) * 1000L
            1 -> parts[0] * 1000L
            else -> 0L
        }
    }

    private suspend fun getCatalog(): List<Piece> = withContext(Dispatchers.IO) {
        cachedPieces?.let { return@withContext it }
        val body = Http.get(CATALOG_URL) ?: return@withContext emptyList()
        val arr = runCatching { JSONArray(body) }.getOrNull() ?: return@withContext emptyList()
        val list = (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val title = o.optString("title").ifEmpty { return@mapNotNull null }
            val file = o.optString("filename").ifEmpty { return@mapNotNull null }
            Piece(
                title = title,
                filename = file,
                lengthMs = parseLength(o.optString("length")),
                genre = o.optString("genre"),
                feel = o.optString("feel"),
                instruments = o.optString("instruments"),
                description = o.optString("description"),
            )
        }
        cachedPieces = list
        list
    }

    suspend fun search(q: String): List<OnlineTrack> = withContext(Dispatchers.IO) {
        val query = q.trim().lowercase()
        if (query.isBlank()) return@withContext emptyList()
        val catalog = getCatalog()
        catalog.filter { p ->
            p.title.lowercase().contains(query) ||
            p.feel.lowercase().contains(query) ||
            p.instruments.lowercase().contains(query) ||
            p.description.lowercase().contains(query)
        }.take(25).map { it.toOnlineTrack() }
    }
}

/**
 * [API-16] Lyrics.ovh: Free public lyrics API. Keyless fallback for plain text lyrics.
 */
object LyricsOvh {
    suspend fun fetch(artist: String, title: String): String? = withContext(Dispatchers.IO) {
        val cleanArtist = artist.substringBefore(',').substringBefore('&').substringBefore("feat").trim()
        val cleanTitle = title.replace(Regex("""\s*[\(\[].*?[\)\]]"""), "").trim()
        if (cleanArtist.isBlank() || cleanTitle.isBlank()) return@withContext null
        val url = "https://api.lyrics.ovh/v1/${Http.enc(cleanArtist)}/${Http.enc(cleanTitle)}"
        val body = runCatching { Http.get(url) }.getOrNull() ?: return@withContext null
        runCatching {
            JSONObject(body).optString("lyrics").takeIf { it.isNotBlank() }
        }.getOrNull()
    }
}

/**
 * [API-17] Openverse: Open-source Creative Commons and public domain audio catalog
 * operated by the WordPress Foundation. Covers global indie, ambient, instrumental, and cultural recordings.
 * Full-length playable streams with artwork. Keyless.
 */
object Openverse {
    private const val BASE = "https://api.openverse.org/v1/audio"
    const val SOURCE = "Openverse"

    private fun parse(o: JSONObject): OnlineTrack? {
        val id = o.optString("id").ifEmpty { return null }
        val streamUrl = o.optString("url").ifEmpty { return null }
        val title = o.optString("title").ifEmpty { "Openverse Audio" }
        val creator = o.optString("creator").ifEmpty { "Independent Artist" }
        val album = o.optJSONObject("audio_set")?.optString("title")?.ifEmpty { "Openverse" } ?: "Openverse"
        val thumb = o.optString("thumbnail").ifEmpty { null }
        val durationMs = o.optLong("duration", 0L)

        return OnlineTrack(
            key = "openverse:$id",
            title = title,
            artist = creator,
            album = album,
            artwork = thumb,
            durationMs = durationMs,
            streamUrl = streamUrl,
            isPreview = false,
            source = SOURCE,
        )
    }

    suspend fun search(q: String): List<OnlineTrack> = withContext(Dispatchers.IO) {
        val body = Http.get("$BASE/?q=${Http.enc(q)}&page_size=20&category=music") ?: return@withContext emptyList()
        val arr = runCatching { JSONObject(body).optJSONArray("results") }.getOrNull() ?: return@withContext emptyList()
        (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.let(::parse) }
    }
}

object OnlineCatalog {
    suspend fun search(q: String): List<OnlineTrack> = coroutineScope {
        val js = async { runCatching { JioSaavn.search(q) }.getOrDefault(emptyList()) }
        val ftu = async { runCatching { FreeToUse.search(q) }.getOrDefault(emptyList()) }
        val inc = async { runCatching { Incompetech.search(q) }.getOrDefault(emptyList()) }
        val opv = async { runCatching { Openverse.search(q) }.getOrDefault(emptyList()) }
        val a = async { runCatching { Audius.search(q) }.getOrDefault(emptyList()) }
        val j = async { runCatching { Jamendo.search(q) }.getOrDefault(emptyList()) }
        val cc = async { runCatching { CcMixter.search(q) }.getOrDefault(emptyList()) }
        val d = async { runCatching { Deezer.search(q) }.getOrDefault(emptyList()) }
        val i = async { runCatching { ITunes.search(q) }.getOrDefault(emptyList()) }

        // full tracks first, previews last; drop preview duplicates of the same song
        val full = js.await().take(15) +
                   ftu.await().take(15) +
                   inc.await().take(15) +
                   opv.await().take(15) +
                   a.await().take(15) +
                   j.await().take(15) +
                   cc.await().take(10)

        val previews = (i.await() + d.await()).distinctBy {
            (it.title.lowercase().take(24)) + "|" + it.artist.lowercase().take(16)
        }

        full + previews.take(30)
    }
}

/** [API-8] LRCLIB: free community lyrics database with time-synced LRC. https://lrclib.net */
object Lrclib {
    data class Result(val synced: String?, val plain: String?)

    suspend fun fetch(title: String, artist: String, album: String, durationSec: Long): Result? {
        val exact = runCatching {
            Http.get(
                "https://lrclib.net/api/get?track_name=${Http.enc(title)}&artist_name=${Http.enc(artist)}" +
                    "&album_name=${Http.enc(album)}&duration=$durationSec"
            )
        }.getOrNull()
        if (exact != null) {
            val res = JSONObject(exact).toResult()
            if (res != null) return res
        }

        val search = runCatching {
            Http.get("https://lrclib.net/api/search?track_name=${Http.enc(title)}&artist_name=${Http.enc(artist)}")
        }.getOrNull()
        if (!search.isNullOrBlank()) {
            val arr = runCatching { JSONArray(search) }.getOrNull()
            if (arr != null && arr.length() > 0) {
                // prefer synced + closest duration
                val best = arr.mapObj { it }.minByOrNull {
                    val d = kotlin.math.abs(it.optDouble("duration", 0.0) - durationSec)
                    d + if (it.optString("syncedLyrics").isNullOrEmpty() || it.isNull("syncedLyrics")) 1000 else 0
                }
                val res = best?.toResult()
                if (res != null) return res
            }
        }

        // Fallback to Lyrics.ovh for plain text lyrics
        val ovh = LyricsOvh.fetch(artist, title)
        if (!ovh.isNullOrBlank()) {
            return Result(synced = null, plain = ovh)
        }

        return null
    }

    private fun JSONObject.toResult(): Result? {
        val s = if (isNull("syncedLyrics")) null else optString("syncedLyrics").ifBlank { null }
        val p = if (isNull("plainLyrics")) null else optString("plainLyrics").ifBlank { null }
        return if (s == null && p == null) null else Result(s, p)
    }
}
