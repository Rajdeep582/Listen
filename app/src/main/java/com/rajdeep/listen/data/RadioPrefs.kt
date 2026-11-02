package com.rajdeep.listen.data

import android.content.Context
import com.rajdeep.listen.data.online.OnlineTrack
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

/** Saved radio presets (6 buttons), favourite stations and the last tuned station. */
class RadioPrefs(context: Context) {
    private val prefs = context.getSharedPreferences("radio", Context.MODE_PRIVATE)

    private val _presets = MutableStateFlow(readList("presets", 6))
    val presets: StateFlow<List<OnlineTrack?>> = _presets.asStateFlow()
    private val _favourites = MutableStateFlow(readList("favs", -1).filterNotNull())
    val favourites: StateFlow<List<OnlineTrack>> = _favourites.asStateFlow()

    var lastBand: String
        get() = prefs.getString("band", "air") ?: "air"
        set(v) = prefs.edit().putString("band", v).apply()

    var lastStationKey: String?
        get() = prefs.getString("last", null)
        set(v) = prefs.edit().putString("last", v).apply()

    fun setPreset(slot: Int, st: OnlineTrack?) {
        val l = _presets.value.toMutableList(); l[slot] = st; _presets.value = l; write("presets", l)
    }

    fun toggleFavourite(st: OnlineTrack) {
        val l = _favourites.value.toMutableList()
        if (l.removeAll { it.key == st.key }.not()) l.add(0, st)
        _favourites.value = l; write("favs", l)
    }

    fun isFavourite(key: String) = _favourites.value.any { it.key == key }

    private fun toJson(t: OnlineTrack?) = t?.let {
        JSONObject().put("k", it.key).put("t", it.title).put("a", it.artist).put("i", it.artwork ?: "").put("u", it.streamUrl).put("x", it.extra)
    }

    private fun fromJson(o: JSONObject?) = o?.let {
        OnlineTrack(it.optString("k"), it.optString("t"), it.optString("a"), "Live radio", it.optString("i").ifEmpty { null },
            0, it.optString("u"), isPreview = false, source = "Radio", isLive = true, extra = it.optString("x"))
    }

    private fun write(key: String, l: List<OnlineTrack?>) {
        val arr = JSONArray(); l.forEach { arr.put(toJson(it) ?: JSONObject.NULL) }
        prefs.edit().putString(key, arr.toString()).apply()
    }

    private fun readList(key: String, size: Int): List<OnlineTrack?> {
        val arr = runCatching { JSONArray(prefs.getString(key, "[]")) }.getOrDefault(JSONArray())
        val l = (0 until arr.length()).map { fromJson(arr.optJSONObject(it)) }
        return if (size < 0) l else List(size) { l.getOrNull(it) }
    }
}
