package com.music.bitchord.data.remote.spotify

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

data class SpotifyTrack(val id: String, val uri: String, val name: String, val artists: List<String>, val albumName: String?, val artworkUrl: String?, val durationMs: Long, val spotifyUrl: String?)
data class SpotifyPlaylist(val id: String, val uri: String, val name: String, val description: String?, val artworkUrl: String?, val ownerName: String?, val trackCount: Int, val spotifyUrl: String?)
data class SpotifyPlaybackState(val deviceId: String?, val isPlaying: Boolean, val progressMs: Long, val track: SpotifyTrack?)

/** Official Spotify catalogue and playback-control API; this is not an audio stream extractor. */
class SpotifyWebApi(context: Context, private val auth: SpotifyAuthRepository = SpotifyAuthRepository(context), private val client: OkHttpClient = OkHttpClient()) {
    suspend fun searchTracks(query: String, limit: Int = 20, offset: Int = 0): List<SpotifyTrack> {
        require(query.isNotBlank()) { "Search query cannot be blank." }
        val url = "$API_BASE/search?q=${enc(query)}&type=track&limit=${limit.coerceIn(1, 50)}&offset=${offset.coerceAtLeast(0)}"
        return get(url).optJSONObject("tracks")?.optJSONArray("items").toTracks()
    }
    suspend fun currentUserPlaylists(limit: Int = 20, offset: Int = 0): List<SpotifyPlaylist> =
        get("$API_BASE/me/playlists?limit=${limit.coerceIn(1, 50)}&offset=${offset.coerceAtLeast(0)}").optJSONArray("items").toPlaylists()
    suspend fun playbackState(): SpotifyPlaybackState? {
        val json = getOrNull("$API_BASE/me/player") ?: return null
        return SpotifyPlaybackState(json.optJSONObject("device")?.optString("id")?.takeIf(String::isNotBlank), json.optBoolean("is_playing", false), json.optLong("progress_ms", 0L), json.optJSONObject("item")?.toTrack())
    }
    suspend fun playTrack(uri: String, deviceId: String? = null) {
        require(uri.startsWith("spotify:track:")) { "Expected a Spotify track URI." }
        put("/play", JSONObject().put("uris", JSONArray().put(uri)), deviceId)
    }
    suspend fun playPlaylist(uri: String, deviceId: String? = null) {
        require(uri.startsWith("spotify:playlist:")) { "Expected a Spotify playlist URI." }
        put("/play", JSONObject().put("context_uri", uri), deviceId)
    }
    suspend fun resume(deviceId: String? = null) = put("/play", JSONObject(), deviceId)
    suspend fun pause(deviceId: String? = null) = request("PUT", playerUrl("/pause", deviceId), null)
    suspend fun skipNext(deviceId: String? = null) = request("POST", playerUrl("/next", deviceId), null)
    suspend fun skipPrevious(deviceId: String? = null) = request("POST", playerUrl("/previous", deviceId), null)
    suspend fun seekTo(positionMs: Long, deviceId: String? = null) {
        require(positionMs >= 0L) { "Playback position must be non-negative." }
        request("PUT", playerUrl("/seek?position_ms=$positionMs", deviceId), null)
    }
    private suspend fun put(path: String, json: JSONObject, deviceId: String?) = request("PUT", playerUrl(path, deviceId), json.toString())
    private fun playerUrl(path: String, deviceId: String?): String {
        val base = "$API_BASE/me/player$path"
        return if (deviceId.isNullOrBlank()) base else base + (if ('?' in base) "&" else "?") + "device_id=" + enc(deviceId)
    }
    private suspend fun get(url: String): JSONObject = getOrNull(url) ?: error("Spotify returned an empty response.")
    private suspend fun getOrNull(url: String): JSONObject? = request("GET", url, null)
    private suspend fun request(method: String, url: String, body: String?): JSONObject? = withContext(Dispatchers.IO) {
        val token = auth.accessToken()
        val requestBody = body?.toRequestBody(JSON_MEDIA_TYPE)
        val builder = Request.Builder().url(url).header("Authorization", "Bearer $token").header("Accept", "application/json")
        when (method) {
            "GET" -> builder.get()
            "POST" -> builder.post(requestBody ?: ByteArray(0).toRequestBody(null))
            "PUT" -> builder.put(requestBody ?: ByteArray(0).toRequestBody(null))
            else -> error("Unsupported Spotify HTTP method: $method")
        }
        client.newCall(builder.build()).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful && response.code != 204) {
                val detail = runCatching { JSONObject(text).optJSONObject("error")?.optString("message") }.getOrNull()
                error("Spotify API request failed (${response.code})${detail?.let { ": $it" }.orEmpty()}")
            }
            if (response.code == 204 || text.isBlank()) null else JSONObject(text)
        }
    }
    private fun JSONArray?.toTracks(): List<SpotifyTrack> {
        if (this == null) return emptyList()
        return buildList { for (i in 0 until length()) optJSONObject(i)?.toTrack()?.let(::add) }
    }
    private fun JSONArray?.toPlaylists(): List<SpotifyPlaylist> {
        if (this == null) return emptyList()
        return buildList {
            for (i in 0 until length()) {
                val item = optJSONObject(i) ?: continue
                val owner = item.optJSONObject("owner")
                val images = item.optJSONArray("images")
                val tracks = item.optJSONObject("tracks") ?: item.optJSONObject("items")
                add(SpotifyPlaylist(item.optString("id"), item.optString("uri"), item.optString("name", "Untitled playlist"), item.optString("description").takeIf(String::isNotBlank), images?.optJSONObject(0)?.optString("url")?.takeIf(String::isNotBlank), owner?.optString("display_name")?.takeIf(String::isNotBlank), tracks?.optInt("total", 0) ?: 0, item.optJSONObject("external_urls")?.optString("spotify")?.takeIf(String::isNotBlank)))
            }
        }
    }
    private fun JSONObject.toTrack(): SpotifyTrack {
        val artistArray = optJSONArray("artists") ?: JSONArray()
        val artists = buildList { for (i in 0 until artistArray.length()) artistArray.optJSONObject(i)?.optString("name")?.takeIf(String::isNotBlank)?.let(::add) }
        val album = optJSONObject("album")
        val images = album?.optJSONArray("images")
        return SpotifyTrack(optString("id"), optString("uri"), optString("name", "Unknown track"), artists, album?.optString("name")?.takeIf(String::isNotBlank), images?.optJSONObject(0)?.optString("url")?.takeIf(String::isNotBlank), optLong("duration_ms", 0L), optJSONObject("external_urls")?.optString("spotify")?.takeIf(String::isNotBlank))
    }
    private fun enc(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8.name())
    companion object {
        private const val API_BASE = "https://api.spotify.com/v1"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}
