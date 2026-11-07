package com.rajdeep.listen.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.rajdeep.listen.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class AccountUser(val id: String, val username: String, val createdAt: String = "", val lastLoginAt: String = "")
data class AccountSession(val token: String, val user: AccountUser)
data class AccountFavorite(val songId: String, val title: String, val artist: String, val album: String, val artwork: String)
data class PendingFavoriteChange(val favorite: AccountFavorite, val shouldBeFavorite: Boolean)
data class AccountHistoryItem(val songId: String, val title: String, val artist: String, val album: String, val artwork: String)
private class ApiStatusException(val status: Int, message: String) : IllegalStateException(message)

/** All account traffic goes over HTTPS to the API; Mongo credentials never enter the client. */
class AccountClient(context: Context) {
    private val favoriteQueueMutex = Mutex()
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val prefs = EncryptedSharedPreferences.create(
        context, "account_session",
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    init {
        runCatching {
            context.applicationContext.getSystemService(ConnectivityManager::class.java)
                ?.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) {
                        user?.id?.let { ownerId -> ioScope.launch { flushPendingFavorites(ownerId) } }
                    }
                })
        }
    }
    val token: String? get() = prefs.getString("token", null)
    val user: AccountUser? get() = prefs.getString("user", null)?.let {
        runCatching { JSONObject(it).toUser() }.getOrNull()
    }
    fun signOut() { prefs.edit().remove("token").remove("user").apply() }

    suspend fun signUp(username: String, password: String) = auth("signup", username, password)
    suspend fun signIn(username: String, password: String) = auth("signin", username, password)

    private suspend fun auth(path: String, username: String, password: String): AccountSession = withContext(Dispatchers.IO) {
        val json = request("POST", "v1/auth/$path", JSONObject().put("username", username).put("password", password), authenticated = false)
        val session = AccountSession(json.getString("token"), json.getJSONObject("user").toUser())
        prefs.edit().putString("token", session.token).putString("user", json.getJSONObject("user").toString()).apply()
        session
    }

    suspend fun restore(): AccountUser? = withContext(Dispatchers.IO) {
        if (token == null) return@withContext null
        runCatching {
            val userJson = request("GET", "v1/me").getJSONObject("user")
            prefs.edit().putString("user", userJson.toString()).apply()
            userJson.toUser()
        }.getOrElse { if ((it as? ApiStatusException)?.status == 401) signOut(); user }
    }

    suspend fun syncFavorite(ownerId: String, songId: String, isFavorite: Boolean, title: String, artist: String, album: String = "", artwork: String = "") = withContext(Dispatchers.IO) { favoriteQueueMutex.withLock {
        val key = "pending_favorites_$ownerId"
        val queued = runCatching { JSONObject(prefs.getString(key, "{}") ?: "{}") }.getOrDefault(JSONObject())
        queued.put(songId, JSONObject().put("favorite", isFavorite).put("title", title).put("artist", artist).put("album", album).put("artwork", artwork))
        if (!prefs.edit().putString(key, queued.toString()).commit()) return@withLock false
        applyPendingFavorite(songId, key)
    } }

    /** Retries account-scoped favourite writes after connectivity returns or the app restarts. */
    suspend fun flushPendingFavorites(ownerId: String): Boolean = withContext(Dispatchers.IO) { favoriteQueueMutex.withLock {
        val key = "pending_favorites_$ownerId"
        val queued = runCatching { JSONObject(prefs.getString(key, "{}") ?: "{}") }.getOrDefault(JSONObject())
        var allSynced = true
        queued.keys().asSequence().toList().forEach { id ->
            if (!applyPendingFavorite(id, key)) allSynced = false
        }
        allSynced
    } }

    private fun applyPendingFavorite(songId: String, key: String): Boolean {
        val queued = runCatching { JSONObject(prefs.getString(key, "{}") ?: "{}") }.getOrDefault(JSONObject())
        val item = queued.optJSONObject(songId) ?: return true
        return runCatching {
            if (item.optBoolean("favorite")) perform("PUT", "v1/favorites", song(songId, item.optString("title"), item.optString("artist"), item.optString("album"), item.optString("artwork")))
            else perform("DELETE", "v1/favorites/${encode(songId)}", null)
            val latest = runCatching { JSONObject(prefs.getString(key, "{}") ?: "{}") }.getOrDefault(JSONObject())
            latest.remove(songId)
            if (latest.length() == 0) prefs.edit().remove(key).commit() else prefs.edit().putString(key, latest.toString()).commit()
            true
        }.getOrDefault(false)
    }
    suspend fun recordPlay(songId: String, title: String, artist: String, album: String = "", artwork: String = "") =
        withContext(Dispatchers.IO) { perform("POST", "v1/history", song(songId, title, artist, album, artwork)) }
    suspend fun recordSearch(query: String) = withContext(Dispatchers.IO) { perform("POST", "v1/searches", JSONObject().put("query", query)) }
    suspend fun favorites(): JSONArray = withContext(Dispatchers.IO) { requestArray("GET", "v1/favorites") }
    fun pendingFavoriteAdds(ownerId: String): List<AccountFavorite> {
        return pendingFavoriteChanges(ownerId).filter { it.shouldBeFavorite && !it.favorite.songId.startsWith("local:") }.map { it.favorite }
    }
    fun pendingFavoriteChanges(ownerId: String): List<PendingFavoriteChange> {
        val queued = runCatching { JSONObject(prefs.getString("pending_favorites_$ownerId", "{}") ?: "{}") }.getOrDefault(JSONObject())
        return queued.keys().asSequence().toList().mapNotNull { id ->
            val entry = queued.optJSONObject(id) ?: return@mapNotNull null
            PendingFavoriteChange(
                AccountFavorite(id, entry.optString("title"), entry.optString("artist"), entry.optString("album"), entry.optString("artwork")),
                entry.optBoolean("favorite"),
            )
        }
    }
    suspend fun history(): JSONArray = withContext(Dispatchers.IO) { requestArray("GET", "v1/history") }
    suspend fun preferences(): JSONObject = withContext(Dispatchers.IO) { request("GET", "v1/preferences") }
    suspend fun savePreferences(values: JSONObject) = withContext(Dispatchers.IO) { perform("PUT", "v1/preferences", values) }

    private fun song(id: String, title: String, artist: String, album: String, artwork: String) = JSONObject()
        .put("songId", id).put("title", title).put("artist", artist).put("album", album).put("artwork", artwork)

    private fun requestArray(method: String, path: String): JSONArray {
        val result = perform(method, path, null)
        return JSONArray(result)
    }
    private fun request(method: String, path: String, body: JSONObject? = null, authenticated: Boolean = true): JSONObject =
        JSONObject(perform(method, path, body, authenticated))

    private fun perform(method: String, path: String, body: JSONObject?, authenticated: Boolean = true): String {
        val base = BuildConfig.ACCOUNT_API_BASE_URL
        require(base.startsWith("https://") || BuildConfig.DEBUG && base.startsWith("http://")) { "Account API must use HTTPS." }
        val connection = URL(URL(base), path).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = method
            // Render's free service can need close to a minute to wake after idle time.
            connection.connectTimeout = 20_000; connection.readTimeout = 90_000
            connection.setRequestProperty("Accept", "application/json")
            if (authenticated) token?.let { connection.setRequestProperty("Authorization", "Bearer $it") }
            if (body != null) {
                connection.doOutput = true; connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) throw ApiStatusException(code, runCatching { JSONObject(text).optString("error") }.getOrNull().takeUnless { it.isNullOrBlank() } ?: "Request failed (HTTP $code).")
            return text
        } finally { connection.disconnect() }
    }

    private fun JSONObject.toUser() = AccountUser(getString("id"), getString("username"), optString("createdAt"), optString("lastLoginAt"))
    private fun encode(value: String) = java.net.URLEncoder.encode(value, "UTF-8")
}
