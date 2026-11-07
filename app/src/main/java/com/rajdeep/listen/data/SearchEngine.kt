package com.rajdeep.listen.data

import android.content.Context
import com.rajdeep.listen.data.db.Album
import com.rajdeep.listen.data.db.Artist
import com.rajdeep.listen.data.db.Song
import com.rajdeep.listen.data.db.Video
import com.rajdeep.listen.data.online.OnlineTrack
import java.text.Normalizer

/**
 * In-memory relevance search. Multi-word, accent/case/punctuation-insensitive, ranks exact > prefix >
 * word-prefix > contains, tolerates one typo per word and ignores filler like "song", "mp3", "lyrics".
 */
object SearchEngine {
    private val stop = setOf("song", "songs", "mp3", "lyrics", "lyric", "video", "audio", "full", "official", "hd", "the", "by", "ft", "feat")
    private val nonAlnum = Regex("[^\\p{L}\\p{N} ]")
    private val spaces = Regex("\\s+")
    private val marks = Regex("\\p{Mn}+")

    fun norm(s: String): String =
        Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD).replace(marks, "").replace(nonAlnum, " ").replace(spaces, " ").trim()

    fun tokens(q: String): List<String> = norm(q).split(' ').filter { it.isNotEmpty() && it !in stop }.ifEmpty { norm(q).split(' ').filter { it.isNotEmpty() } }

    /** ≤1 edit (Damerau-ish) for words ≥4 chars. */
    private fun nearlyEqual(a: String, b: String): Boolean {
        if (a == b) return true
        if (a.length < 4 || kotlin.math.abs(a.length - b.length) > 1) return false
        var i = 0; var j = 0; var edits = 0
        while (i < a.length && j < b.length) {
            if (a[i] == b[j]) { i++; j++; continue }
            if (++edits > 1) return false
            when {
                a.length > b.length -> i++
                a.length < b.length -> j++
                i + 1 < a.length && j + 1 < b.length && a[i] == b[j + 1] && a[i + 1] == b[j] -> { i += 2; j += 2 }
                else -> { i++; j++ }
            }
        }
        return edits + (a.length - i) + (b.length - j) <= 1
    }

    /** Score one token against one normalised field (0 = no match). */
    private fun tokenScore(t: String, field: String, words: List<String>): Int = when {
        field == t -> 100
        field.startsWith(t) -> 80
        words.any { it.startsWith(t) } -> 60
        field.contains(t) -> 35
        words.any { nearlyEqual(t, it.take(t.length + 1)) || nearlyEqual(t, it) } -> 20
        else -> 0
    }

    /**
     * Each query token must match some field; fields are weighted (title > artist > album > other).
     * Bonus when the whole phrase appears in order.
     */
    fun score(query: String, fields: List<Pair<String, Int>>): Int {
        val toks = tokens(query); if (toks.isEmpty()) return 0
        val nf = fields.map { (f, w) -> norm(f).let { n -> Triple(n, n.split(' '), w) } }
        var total = 0
        for (t in toks) {
            val best = nf.maxOf { (n, ws, w) -> tokenScore(t, n, ws) * w }
            if (best == 0) return 0
            total += best
        }
        val phrase = toks.joinToString(" ")
        nf.firstOrNull()?.let { (n, _, w) -> if (n.contains(phrase)) total += 50 * w; if (n == phrase) total += 120 * w }
        return total
    }

    fun songs(q: String, all: List<Song>, limit: Int = 80): List<Song> =
        all.asSequence()
            .map { it to score(q, listOf(it.title to 4, it.artist to 3, it.album to 2, it.albumArtist to 2, it.folder to 1)) }
            .filter { it.second > 0 }
            .sortedWith(compareByDescending<Pair<Song, Int>> { it.second }.thenByDescending { it.first.playCount })
            .take(limit).map { it.first }.toList()

    fun albums(q: String, all: List<Album>) = all.map { it to score(q, listOf(it.album to 4, it.artist to 2)) }
        .filter { it.second > 0 }.sortedByDescending { it.second }.take(20).map { it.first }

    fun artists(q: String, all: List<Artist>) = all.map { it to score(q, listOf(it.artist to 4)) }
        .filter { it.second > 0 }.sortedByDescending { it.second * 10 + it.first.songCount.coerceAtMost(9) }.take(20).map { it.first }

    fun videos(q: String, all: List<Video>) = all.map { it to score(q, listOf(it.title to 4, it.folder to 1)) }
        .filter { it.second > 0 }.sortedByDescending { it.second }.take(60).map { it.first }

    /** Rank merged online results; prefer full tracks; drop cross-source duplicates. */
    fun online(q: String, all: List<OnlineTrack>): List<OnlineTrack> {
        val scored = all.map { t ->
            val s = score(q, listOf(t.title to 4, t.artist to 3, t.album to 1)).let { if (it == 0) 1 else it } // APIs already matched; keep but rank low
            t to s + (if (t.isPreview) 0 else 40) + (if (t.artwork != null) 5 else 0)
        }.sortedWith(
            compareByDescending<Pair<OnlineTrack, Int>> { it.first.source == "JioSaavn" }
                .thenByDescending { it.second },
        )
        val seen = HashSet<String>()
        return scored.mapNotNull { (t, _) ->
            val k = norm(t.title).take(28) + "|" + norm(t.artist).take(16)
            if (seen.add(k)) t else null
        }
    }

    /** Live suggestions while typing: matching artists, albums and song titles. */
    fun suggestions(q: String, songs: List<Song>, max: Int = 6): List<String> {
        val n = norm(q); if (n.length < 2) return emptyList()
        val pool = LinkedHashSet<String>()
        songs.forEach { s ->
            listOf(s.artist, s.title, s.album).forEach { f -> if (norm(f).let { it.startsWith(n) || it.split(' ').any { w -> w.startsWith(n) } }) pool += f }
            if (pool.size >= max * 3) return@forEach
        }
        return pool.filterNot { it.lowercase().startsWith("unknown") }.sortedBy { it.length }.take(max)
    }
}

/** Recent searches (per mode), newest first. */
class SearchHistory(context: Context) {
    private val prefs = context.getSharedPreferences("search_history", Context.MODE_PRIVATE)
    fun get(mode: String): List<String> = prefs.getString(mode, "")!!.split('\n').filter { it.isNotBlank() }
    fun add(mode: String, q: String) {
        val t = q.trim(); if (t.length < 2) return
        val l = (listOf(t) + get(mode).filterNot { it.equals(t, true) }).take(12)
        prefs.edit().putString(mode, l.joinToString("\n")).apply()
    }
    fun remove(mode: String, q: String) = prefs.edit().putString(mode, get(mode).filterNot { it == q }.joinToString("\n")).apply()
    fun clear(mode: String) = prefs.edit().remove(mode).apply()
}
