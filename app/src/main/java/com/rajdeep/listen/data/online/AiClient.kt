package com.rajdeep.listen.data.online

import android.util.Log
import com.rajdeep.listen.BuildConfig
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/*
 * [API-10] AI provider (OpenAI-compatible chat completions).
 * Used ONLY by search bar auto-suggest and query normalization.
 *
 *  1. Groq  — https://api.groq.com/openai/v1/chat/completions (~150–400 ms).
 *             Model: qwen/qwen3.8-27b (active fast chat model on Groq).
 *             Requires free key: GROQ_API_KEY in secrets.properties.
 *  2. Pollinations — https://text.pollinations.ai/openai, keyless fallback.
 */
object AiClient {
    private const val TAG = "AiClient"
    private const val GROQ_URL = "https://api.groq.com/openai/v1/chat/completions"
    private const val GROQ_MODEL = "qwen/qwen3.8-27b"
    private const val POLLI_URL = "https://text.pollinations.ai/openai"
    private const val POLLI_MODEL = "openai"

    @Volatile var enabled = true
    val fast: Boolean get() = BuildConfig.GROQ_API_KEY.isNotBlank()

    // Process-wide LRU cache (about 50 entries) for auto-suggest
    private val suggestCache = object : LinkedHashMap<String, List<String>>(50, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<String>>?) = size > 50
    }

    // Process-wide LRU cache (about 50 entries) for query normalization
    private val normCache = object : LinkedHashMap<String, String>(50, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?) = size > 50
    }

    @Synchronized
    private fun getCachedSuggestions(k: String): List<String>? = suggestCache[k]

    @Synchronized
    private fun putCachedSuggestions(k: String, v: List<String>) {
        suggestCache[k] = v
    }

    @Synchronized
    private fun getCachedNorm(k: String): String? = normCache[k]

    @Synchronized
    private fun putCachedNorm(k: String, v: String) {
        normCache[k] = v
    }

    /**
     * Auto-suggest: returns 4 likely complete searches (song title + artist, artist, album, mood)
     * for partial input. Handles Hindi/Bollywood names and misspellings. Cached (LRU ~50), timeout ~2s.
     */
    suspend fun suggest(query: String): List<String> {
        val q = query.trim()
        val key = q.lowercase()
        if (key.length < 2 || !enabled) return emptyList()
        getCachedSuggestions(key)?.let { return it }

        val sysPrompt = "You autocomplete a music app search box. Given partial input, return 4 likely complete searches (song title + artist, artist, album, or mood) for what the user typed. Return a JSON array of strings only. Handle Hindi/Bollywood names and misspellings (e.g. 'arjit' -> 'Arijit Singh'). No commentary, no prose."

        val out = stringArray(
            system = sysPrompt,
            user = q,
            timeoutMs = if (fast) 2000L else 4000L,
            maxTokens = 80,
            temperature = 0.2
        ).orEmpty().take(4)

        if (out.isNotEmpty()) {
            putCachedSuggestions(key, out)
        }
        return out
    }

    /**
     * Query normalization: fixes spelling and normalizes query to a clean "title artist" or "artist" string.
     * Timeout 1.5s; falls back to original query on failure. Cached (LRU ~50).
     */
    suspend fun normalize(query: String, timeoutMs: Long = 1500L): String {
        val q = query.trim()
        val key = q.lowercase()
        if (key.length < 2 || !enabled || !fast) return q
        getCachedNorm(key)?.let { return it }

        val sysPrompt = "You are a music search query normalizer. Normalize the user query to a clean title artist or artist string, correcting any misspellings, especially for Hindi/Bollywood/Indian/international music. Reply with ONLY the clean search string. No quotes, no commentary."

        val (url, model, headers) = if (fast) {
            Log.d(TAG, "Querying Groq -> $GROQ_URL (model=$GROQ_MODEL) to normalize: '$q'")
            Triple(GROQ_URL, GROQ_MODEL, mapOf("Authorization" to "Bearer ${BuildConfig.GROQ_API_KEY}"))
        } else {
            Triple(POLLI_URL, POLLI_MODEL, emptyMap())
        }

        val body = JSONObject()
            .put("model", model)
            .put("temperature", 0.2)
            .put("max_tokens", 30)
            .put("messages", JSONArray()
                .put(JSONObject().put("role", "system").put("content", sysPrompt))
                .put(JSONObject().put("role", "user").put("content", q)))
            .toString()

        val text = withTimeoutOrNull(timeoutMs) {
            runCatching { Http.postJson(url, body, headers, timeoutMs.toInt()) }.getOrNull()
        } ?: return q

        val content = runCatching {
            JSONObject(text).getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content")
        }.getOrNull()?.trim() ?: return q

        val clean = content.removeSurrounding("\"").removeSurrounding("'").trim()
        if (clean.isNotBlank() && clean.length < 100 && !clean.startsWith("{") && !clean.startsWith("[")) {
            putCachedNorm(key, clean)
            return clean
        }
        return q
    }

    /** Asks for a JSON array of strings; returns null on timeout/failure. */
    suspend fun stringArray(
        system: String,
        user: String,
        timeoutMs: Long,
        maxTokens: Int = 80,
        temperature: Double = 0.2
    ): List<String>? {
        if (!enabled) return null
        val (url, model, headers) = if (fast) {
            Log.d(TAG, "Querying Groq -> $GROQ_URL (model=$GROQ_MODEL) for '$user'")
            Triple(GROQ_URL, GROQ_MODEL, mapOf("Authorization" to "Bearer ${BuildConfig.GROQ_API_KEY}"))
        } else {
            Triple(POLLI_URL, POLLI_MODEL, emptyMap())
        }

        val body = JSONObject()
            .put("model", model)
            .put("temperature", temperature)
            .put("max_tokens", maxTokens)
            .put("messages", JSONArray()
                .put(JSONObject().put("role", "system").put("content", "$system Reply with ONLY a JSON array of strings. No prose."))
                .put(JSONObject().put("role", "user").put("content", user)))
            .toString()

        val text = withTimeoutOrNull(timeoutMs) {
            runCatching { Http.postJson(url, body, headers, timeoutMs.toInt()) }.getOrNull()
        } ?: return null

        val content = runCatching {
            JSONObject(text).getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content")
        }.getOrNull() ?: return null

        val arr = content.substringAfter('[', "").substringBeforeLast(']', "")
        val out = runCatching {
            JSONArray("[$arr]").let { a -> (0 until a.length()).map { a.get(it).toString().trim() } }
        }.getOrNull()?.filter { it.isNotBlank() && it.length < 120 } ?: return null

        return out
    }
}

/** [API-9] Instant query completions (YouTube-flavoured Google suggest). Free, keyless, ~300 ms. */
object QuerySuggest {
    private val cache = ConcurrentHashMap<String, List<String>>()
    suspend fun complete(q: String): List<String> {
        val k = q.lowercase().trim(); if (k.length < 2) return emptyList()
        cache[k]?.let { return it }
        val body = runCatching { Http.get("https://suggestqueries.google.com/complete/search?client=firefox&ds=yt&hl=en&q=${Http.enc(k)}") }.getOrNull() ?: return emptyList()
        val out = runCatching { JSONArray(body).getJSONArray(1).let { a -> (0 until a.length()).map { a.getString(it) } } }.getOrDefault(emptyList())
            .filterNot { it.contains("shorts") || it.contains("status") }.take(8)
        if (cache.size > 300) cache.clear()
        cache[k] = out
        return out
    }
}
