package com.rajdeep.listen.data.online

import com.rajdeep.listen.BuildConfig
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/*
 * [API-10] AI provider (OpenAI-compatible chat completions). Used ONLY by search auto-suggest and
 * the "Suggested for you" section. To change provider/model edit only the constants below.
 *
 *  1. Groq  — https://api.groq.com/openai/v1/chat/completions, model llama-3.1-8b-instant
 *             (~150–400 ms). Needs a free key: GROQ_API_KEY in secrets.properties.
 *  2. Pollinations — https://text.pollinations.ai/openai, keyless, slower (seconds). Fallback.
 */
// STATUS: AI is currently DISABLED by the owner. Call sites are commented out in SearchScreen.kt
// (auto-suggest) and Recommender.kt (suggestions); uncomment those to turn AI back on.
object AiClient {
    private const val GROQ_URL = "https://api.groq.com/openai/v1/chat/completions"
    private const val GROQ_MODEL = "llama-3.1-8b-instant"
    private const val POLLI_URL = "https://text.pollinations.ai/openai"
    private const val POLLI_MODEL = "openai"

    @Volatile var enabled = true
    val fast: Boolean get() = BuildConfig.GROQ_API_KEY.isNotBlank()

    private val cache = ConcurrentHashMap<String, List<String>>()

    /** Asks for a JSON array of strings; returns null on timeout/failure. Cached per prompt. */
    suspend fun stringArray(system: String, user: String, timeoutMs: Long, maxTokens: Int = 200): List<String>? {
        if (!enabled) return null
        val key = system.hashCode().toString() + "|" + user
        cache[key]?.let { return it }
        val (url, model, headers) = if (fast) Triple(GROQ_URL, GROQ_MODEL, mapOf("Authorization" to "Bearer ${BuildConfig.GROQ_API_KEY}"))
        else Triple(POLLI_URL, POLLI_MODEL, emptyMap())
        val body = JSONObject()
            .put("model", model).put("temperature", 0.4).put("max_tokens", maxTokens)
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
        val out = runCatching { JSONArray("[$arr]").let { a -> (0 until a.length()).map { a.get(it).toString().trim() } } }.getOrNull()
            ?.filter { it.isNotBlank() && it.length < 120 } ?: return null
        if (cache.size > 300) cache.clear()
        cache[key] = out
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
