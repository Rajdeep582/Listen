package com.rajdeep.listen.data

import android.content.Context
import androidx.compose.runtime.Immutable
import com.rajdeep.listen.data.db.LibraryDao
import com.rajdeep.listen.data.db.Song
import com.rajdeep.listen.data.online.AiClient
import com.rajdeep.listen.data.online.Audius
import com.rajdeep.listen.data.online.FreeToUse
import com.rajdeep.listen.data.online.Http
import com.rajdeep.listen.data.online.ITunes
import com.rajdeep.listen.data.online.Incompetech
import com.rajdeep.listen.data.online.Jamendo
import com.rajdeep.listen.data.online.JioSaavn
import com.rajdeep.listen.data.online.OnlineTrack
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.json.JSONArray
import org.json.JSONObject

@Immutable
sealed interface Suggestion {
    val reason: String
    data class Local(val song: Song, override val reason: String) : Suggestion
    data class Online(val track: OnlineTrack, override val reason: String) : Suggestion
}

/**
 * "Suggested for you": builds a taste profile from play history + favourites, asks the AI for
 * similar songs, then resolves each pick to something playable (your library first, then full
 * free streams on Audius/Jamendo, then iTunes previews). Falls back to Deezer artist radio when
 * the AI is unavailable. Results are cached for 6 h.
 */
class Recommender(private val context: Context, private val dao: LibraryDao) {
    private val prefs = context.getSharedPreferences("for_you", Context.MODE_PRIVATE)
    private val ttl = 6 * 3_600_000L

    suspend fun suggestions(force: Boolean = false): List<Suggestion> {
        if (!force) cached()?.let { return it }
        val artists = dao.topArtistNames(8).filterNot { it.lowercase().startsWith("unknown") }
        val taste = dao.tasteSongs(15)
        // AI DISABLED (owner request) — to re-enable AI picks, replace the next line with:
        // val out = ai(artists, taste) ?: fallback(artists)
        val out = recco(taste, artists) ?: fallback(artists)
        save(out)
        return out
    }

    @Suppress("unused") // kept for when AI is re-enabled
    private suspend fun ai(artists: List<String>, taste: List<Song>): List<Suggestion>? {
        if (artists.isEmpty() && taste.isEmpty()) return null
        val profile = buildString {
            if (artists.isNotEmpty()) append("Favourite artists: ").append(artists.joinToString()).append(". ")
            if (taste.isNotEmpty()) append("Recently loved songs: ").append(taste.take(12).joinToString { "${it.title} by ${it.artist}" }).append(".")
        }
        val picks = AiClient.stringArray(
            system = "You are a music recommender. Suggest 18 real, well-known songs the listener would enjoy, similar in mood, language and era to their taste, mostly from artists NOT in their list. Each item exactly 'Song Title - Artist'.",
            user = profile, timeoutMs = if (AiClient.fast) 6000 else 20000, maxTokens = 500,
        ) ?: return null
        val pairs = picks.mapNotNull { p -> p.split(" - ", " – ", limit = 2).takeIf { it.size == 2 }?.let { it[0].trim() to it[1].trim() } }
        if (pairs.isEmpty()) return null
        val gate = Semaphore(4)
        val because = artists.firstOrNull()?.let { "Because you play $it" } ?: "Picked for you"
        return coroutineScope {
            pairs.take(18).map { (title, artist) -> async { gate.withPermit { resolve(title, artist, because) } } }.awaitAll().filterNotNull()
        }.distinctBy { it.key() }.takeIf { it.isNotEmpty() }
    }

    private fun Suggestion.key() = when (this) { is Suggestion.Local -> "l" + song.id; is Suggestion.Online -> track.key }

    /** Library first → full free stream → preview. */
    private suspend fun resolve(title: String, artist: String, reason: String): Suggestion? {
        val q = "$title $artist"
        dao.searchSongs(title).firstOrNull { SearchEngine.score(q, listOf(it.title to 4, it.artist to 3)) > 0 && it.artist.contains(artist.substringBefore(' '), true) }
            ?.let { return Suggestion.Local(it, "In your library") }
        val saavn = runCatching { JioSaavn.search(q) }.getOrDefault(emptyList())
        saavn.firstOrNull { SearchEngine.score(title, listOf(it.title to 1)) > 0 }?.let { return Suggestion.Online(it, reason) }
        val full = runCatching { Audius.search(q) }.getOrDefault(emptyList()) +
                   runCatching { FreeToUse.search(q) }.getOrDefault(emptyList()) +
                   runCatching { Incompetech.search(q) }.getOrDefault(emptyList()) +
                   runCatching { Jamendo.search(q) }.getOrDefault(emptyList())
        full.firstOrNull { SearchEngine.score(title, listOf(it.title to 1)) > 0 }?.let { return Suggestion.Online(it, reason) }
        return runCatching { ITunes.search(q) }.getOrDefault(emptyList()).firstOrNull()?.let { Suggestion.Online(it, reason) }
    }

    /** ReccoBeats: seeds = your recent/favourite songs → similar tracks → resolved to playable. */
    private suspend fun recco(taste: List<Song>, artists: List<String>): List<Suggestion>? = coroutineScope {
        if (taste.isEmpty()) return@coroutineScope null
        val seeds = taste.take(8).map { s -> async { runCatching { com.rajdeep.listen.data.online.ReccoBeats.seedFor(s.title, s.artist) }.getOrNull() } }
            .awaitAll().filterNotNull().distinct().take(5)
        val pairs = runCatching { com.rajdeep.listen.data.online.ReccoBeats.recommend(seeds, 30) }.getOrDefault(emptyList())
        if (pairs.isEmpty()) return@coroutineScope null
        val gate = Semaphore(4)
        val because = artists.firstOrNull()?.let { "Because you play $it" } ?: "Similar to your favourites"
        pairs.take(24).map { (t, a) -> async { gate.withPermit { resolve(t, a, because) } } }.awaitAll()
            .filterNotNull().distinctBy { it.key() }.takeIf { it.size >= 4 }
    }

    /** No AI: Deezer "artist radio" (similar-artist mix, 30 s previews) for top artists. */
    private suspend fun fallback(artists: List<String>): List<Suggestion> = coroutineScope {
        val seeds = artists.take(3).ifEmpty { listOf("Arijit Singh", "The Weeknd", "Pritam") }
        seeds.map { a ->
            async {
                runCatching {
                    val id = JSONObject(Http.get("https://api.deezer.com/search/artist?q=${Http.enc(a)}&limit=1") ?: "{}")
                        .optJSONArray("data")?.optJSONObject(0)?.optLong("id") ?: return@runCatching emptyList()
                    val arr = JSONObject(Http.get("https://api.deezer.com/artist/$id/radio?limit=10") ?: "{}").optJSONArray("data") ?: JSONArray()
                    (0 until arr.length()).mapNotNull { i ->
                        val o = arr.optJSONObject(i) ?: return@mapNotNull null
                        val prev = o.optString("preview").ifEmpty { return@mapNotNull null }
                        val al = o.optJSONObject("album")
                        Suggestion.Online(
                            OnlineTrack("deezer:${o.optLong("id")}", o.optString("title"), o.optJSONObject("artist")?.optString("name").orEmpty(),
                                al?.optString("title").orEmpty(), al?.optString("cover_xl"), 30_000, prev, isPreview = true, source = "Deezer"),
                            "Similar to $a",
                        )
                    }
                }.getOrDefault(emptyList())
            }
        }.awaitAll().flatten().distinctBy { it.track.key }.shuffled().take(24)
    }

    // ---------- cache ----------
    private fun save(list: List<Suggestion>) {
        val arr = JSONArray()
        list.forEach { s ->
            when (s) {
                is Suggestion.Local -> arr.put(JSONObject().put("t", "l").put("id", s.song.id).put("r", s.reason))
                is Suggestion.Online -> arr.put(JSONObject().put("t", "o").put("r", s.reason).put("k", s.track.key).put("ti", s.track.title)
                    .put("a", s.track.artist).put("al", s.track.album).put("i", s.track.artwork ?: "").put("d", s.track.durationMs)
                    .put("u", s.track.streamUrl).put("p", s.track.isPreview).put("s", s.track.source))
            }
        }
        prefs.edit().putString("list", arr.toString()).putLong("at", System.currentTimeMillis()).apply()
    }

    private suspend fun cached(): List<Suggestion>? {
        if (System.currentTimeMillis() - prefs.getLong("at", 0) > ttl) return null
        val arr = runCatching { JSONArray(prefs.getString("list", "[]")) }.getOrNull() ?: return null
        val localIds = (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.takeIf { o -> o.optString("t") == "l" }?.optLong("id") }
        val songs = if (localIds.isEmpty()) emptyMap() else dao.songsByIds(localIds).associateBy { it.id }
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            if (o.optString("t") == "l") songs[o.optLong("id")]?.let { Suggestion.Local(it, o.optString("r")) }
            else Suggestion.Online(OnlineTrack(o.optString("k"), o.optString("ti"), o.optString("a"), o.optString("al"), o.optString("i").ifEmpty { null },
                o.optLong("d"), o.optString("u"), o.optBoolean("p"), o.optString("s")), o.optString("r"))
        }.takeIf { it.isNotEmpty() }
    }

    fun clear() = prefs.edit().clear().apply()
}
