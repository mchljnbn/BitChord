package com.music.bitchord.data.remote.spotify

import android.content.Context
import android.net.Uri
import android.util.Base64
import com.music.bitchord.BuildConfig
import com.music.bitchord.auth.EncryptedPrefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom

/** Spotify Authorization Code + PKCE client. No client secret is used. */
class SpotifyAuthRepository(context: Context) {
    private val prefs = EncryptedPrefs.open(context.applicationContext, "bitchord_spotify_oauth", "bitchord_spotify_oauth_plain")
    private val client = OkHttpClient()
    private val refreshMutex = Mutex()
    private val clientId get() = BuildConfig.SPOTIFY_CLIENT_ID.takeIf(String::isNotBlank)
        ?: error("Set SPOTIFY_CLIENT_ID in local.properties or the environment.")
    private val redirectUri get() = BuildConfig.SPOTIFY_REDIRECT_URI.takeIf(String::isNotBlank)
        ?: error("Set SPOTIFY_REDIRECT_URI in local.properties or the environment.")

    /** Call once per login attempt, then open the returned URI in a browser/custom tab. */
    fun authorizationUri(): Uri {
        val verifier = randomUrlSafe(64)
        val state = randomUrlSafe(32)
        val challenge = Base64.encodeToString(
            MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)),
            Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP,
        )
        prefs.edit().putString(KEY_VERIFIER, verifier).putString(KEY_STATE, state).apply()
        return Uri.parse(AUTHORIZE_ENDPOINT).buildUpon()
            .appendQueryParameter("response_type", "code")
            .appendQueryParameter("client_id", clientId)
            .appendQueryParameter("redirect_uri", redirectUri)
            .appendQueryParameter("scope", SCOPES.joinToString(" "))
            .appendQueryParameter("code_challenge_method", "S256")
            .appendQueryParameter("code_challenge", challenge)
            .appendQueryParameter("state", state)
            .build()
    }

    /** Validates state and exchanges the callback's one-time authorization code. */
    suspend fun completeAuthorization(callback: Uri): SpotifyToken = withContext(Dispatchers.IO) {
        callback.getQueryParameter("error")?.let { clearPendingAuth(); error("Spotify authorization was denied: $it") }
        val normalizedCallback = callback.buildUpon().clearQuery().clearFragment().build().toString()
        require(normalizedCallback == redirectUri) { "Spotify callback URI did not match the configured redirect URI." }
        val code = callback.getQueryParameter("code") ?: error("Spotify callback contained no authorization code.")
        val returnedState = callback.getQueryParameter("state")
        val expectedState = prefs.getString(KEY_STATE, null)
        val verifier = prefs.getString(KEY_VERIFIER, null)
        clearPendingAuth()
        require(!returnedState.isNullOrBlank() && returnedState == expectedState && !verifier.isNullOrBlank()) {
            "Spotify OAuth state validation failed. Please sign in again."
        }
        val form = FormBody.Builder()
            .add("grant_type", "authorization_code")
            .add("code", code)
            .add("redirect_uri", redirectUri)
            .add("client_id", clientId)
            .add("code_verifier", verifier)
            .build()
        storeTokenResponse(postToken(form))
    }

    /** Refreshes shortly before expiry and serializes concurrent refresh requests. */
    suspend fun accessToken(): String = refreshMutex.withLock {
        val current = prefs.getString(KEY_ACCESS_TOKEN, null)
        val expiresAt = prefs.getLong(KEY_EXPIRES_AT, 0L)
        if (!current.isNullOrBlank() && expiresAt > System.currentTimeMillis() + EXPIRY_SKEW_MS) return@withLock current
        val refresh = prefs.getString(KEY_REFRESH_TOKEN, null) ?: error("Spotify sign-in is required.")
        try {
            storeTokenResponse(postToken(FormBody.Builder()
                .add("grant_type", "refresh_token")
                .add("refresh_token", refresh)
                .add("client_id", clientId)
                .build()), refresh).accessToken
        } catch (error: Exception) {
            if (error.message?.contains("invalid_grant", ignoreCase = true) == true) clearTokens()
            throw error
        }
    }

    fun isSignedIn() = !prefs.getString(KEY_REFRESH_TOKEN, null).isNullOrBlank()
    fun signOut() { clearTokens(); clearPendingAuth() }

    private fun postToken(form: FormBody): JSONObject {
        val request = Request.Builder().url(TOKEN_ENDPOINT).header("Cache-Control", "no-store").post(form).build()
        client.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            val json = runCatching { JSONObject(text) }.getOrElse { JSONObject() }
            if (!response.isSuccessful) error("Spotify token request failed: " + json.optString("error_description", json.optString("error", "HTTP ${response.code}")))
            return json
        }
    }

    private fun storeTokenResponse(json: JSONObject, previousRefresh: String? = null): SpotifyToken {
        val access = json.optString("access_token").takeIf(String::isNotBlank) ?: error("Spotify returned no access token.")
        val refresh = json.optString("refresh_token").takeIf(String::isNotBlank) ?: previousRefresh
        val expiry = json.optLong("expires_in", 3600L)
        prefs.edit().putString(KEY_ACCESS_TOKEN, access).putString(KEY_REFRESH_TOKEN, refresh)
            .putLong(KEY_EXPIRES_AT, System.currentTimeMillis() + expiry * 1000L).apply()
        return SpotifyToken(access, expiry)
    }

    private fun clearPendingAuth() { prefs.edit().remove(KEY_VERIFIER).remove(KEY_STATE).apply() }
    private fun clearTokens() { prefs.edit().remove(KEY_ACCESS_TOKEN).remove(KEY_REFRESH_TOKEN).remove(KEY_EXPIRES_AT).apply() }
    private fun randomUrlSafe(size: Int): String {
        val bytes = ByteArray(size); SecureRandom().nextBytes(bytes)
        return Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
    }

    companion object {
        private const val AUTHORIZE_ENDPOINT = "https://accounts.spotify.com/authorize"
        private const val TOKEN_ENDPOINT = "https://accounts.spotify.com/api/token"
        private const val KEY_VERIFIER = "pkce_verifier"
        private const val KEY_STATE = "oauth_state"
        private const val KEY_ACCESS_TOKEN = "access_token"
        private const val KEY_REFRESH_TOKEN = "refresh_token"
        private const val KEY_EXPIRES_AT = "expires_at"
        private const val EXPIRY_SKEW_MS = 60_000L
        private val SCOPES = listOf("user-read-private", "user-read-email", "playlist-read-private", "user-read-playback-state", "user-modify-playback-state", "user-read-currently-playing")
    }
}

data class SpotifyToken(val accessToken: String, val expiresInSeconds: Long)
