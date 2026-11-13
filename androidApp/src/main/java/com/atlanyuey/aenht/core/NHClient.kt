package com.atlanyuey.aenht.core

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.atlanyuey.aenht.BuildConfig
import com.atlanyuey.aenht.shared.net.AndroidHttp
import com.atlanyuey.aenht.shared.net.AppInfo
import com.atlanyuey.aenht.shared.nh.NhApi
import com.atlanyuey.aenht.shared.nh.NhException
import com.atlanyuey.aenht.shared.nh.TokenStorage
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import okhttp3.OkHttpClient
import java.security.KeyStore
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class NHException(message: String) : Exception(message)

/** Shared HTTP clients. */
object Net {
    val userAgent: String get() = AppInfo.userAgent

    /** Extra interceptors (tests answer requests with sample data). */
    val interceptors = mutableListOf<okhttp3.Interceptor>()

    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .apply { interceptors.forEach { addInterceptor(it) } }
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .addInterceptor { chain ->
                chain.proceed(chain.request().newBuilder().header("User-Agent", userAgent).build())
            }
            .build()
    }

    val json = Json { ignoreUnknownKeys = true; isLenient = true; explicitNulls = false }
}

/**
 * The nhentai API (shared module) with the app's localized errors. The
 * shared client waits for the official rate limits before each request.
 */
object NHClient {
    val api: NhApi by lazy {
        AppInfo.version = BuildConfig.VERSION_NAME
        AndroidHttp.client = Net.client
        NhApi(Auth).also { it.onNeedsVerification = { Session.needsVerification = true } }
    }

    private suspend fun <T> call(block: suspend NhApi.() -> T): T = try {
        api.block()
    } catch (e: NhException) {
        throw NHException(localized(e))
    }

    private fun localized(e: NhException): String = when (e) {
        is NhException.Network -> e.message ?: L10n.string("Unexpected response from the server")
        is NhException.Http -> e.serverMessage ?: L10n.string("Request failed (%lld)", e.code)
        is NhException.InvalidResponse -> L10n.string("Unexpected response from the server")
        is NhException.NotLoggedIn -> L10n.string("Please log in first")
        is NhException.NeedsVerification -> L10n.string("Please log in first")
    }

    suspend fun galleries(query: String, sort: SearchSort, page: Int) = call { galleries(query, sort.raw, page) }
    suspend fun popular() = call { popular() }
    suspend fun gallery(id: Int) = call { gallery(id) }
    suspend fun galleryJson(id: Int): JsonObject = call { galleryJson(id) }
    suspend fun comments(id: Int) = call { comments(id) }
    suspend fun favorites(page: Int) = call { favorites(page) }
    suspend fun setFavorite(id: Int, favorite: Boolean) = call { setFavorite(id, favorite) }
    suspend fun currentUser() = call { currentUser() }
    suspend fun postComment(id: Int, body: String) = call { postComment(id, body) }
    suspend fun suggestTags(text: String) = call { suggestTags(text) }
}

/**
 * The API tokens, encrypted with a key that never leaves the Android
 * Keystore (app-private, excluded from backups).
 */
object Auth : TokenStorage {
    private const val KEY_ALIAS = "aenht.tokens"
    private lateinit var prefs: SharedPreferences

    fun init(context: Context) {
        prefs = context.getSharedPreferences("auth", Context.MODE_PRIVATE)
    }

    override val accessToken: String? get() = read("access")
    override val refreshToken: String? get() = read("refresh")
    val isLoggedIn: Boolean get() = accessToken != null

    override fun save(access: String, refresh: String?) {
        prefs.edit().apply {
            putString("access", encrypt(access))
            if (!refresh.isNullOrEmpty()) putString("refresh", encrypt(refresh))
        }.apply()
    }

    override fun clear() {
        prefs.edit().clear().apply()
    }

    private fun read(name: String): String? =
        prefs.getString(name, null)?.let { decrypt(it) }?.takeIf { it.isNotEmpty() }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(
                KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build(),
            )
        }.generateKey()
    }

    private fun encrypt(value: String): String? = try {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        Base64.encodeToString(cipher.iv + cipher.doFinal(value.toByteArray()), Base64.NO_WRAP)
    } catch (_: Exception) {
        null
    }

    private fun decrypt(stored: String): String? = try {
        val data = Base64.decode(stored, Base64.NO_WRAP)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, data, 0, 12))
        }
        String(cipher.doFinal(data, 12, data.size - 12))
    } catch (_: Exception) {
        null
    }
}

/** The signed-in user, observed by the UI. */
object Session {
    var user by mutableStateOf<CurrentUser?>(null)
        private set
    var isLoggedIn by mutableStateOf(false)
        private set

    /** The saved login could not be renewed; the account is kept until the user logs in again. */
    var needsVerification by mutableStateOf(false)
        internal set

    suspend fun reload() {
        isLoggedIn = Auth.isLoggedIn
        if (!isLoggedIn) {
            user = null
            return
        }
        try {
            user = NHClient.currentUser()
        } catch (_: Exception) {
        }
    }

    suspend fun login(access: String, refresh: String?) {
        Auth.save(access, refresh)
        needsVerification = false
        reload()
    }

    fun logout() {
        Auth.clear()
        user = null
        isLoggedIn = false
        needsVerification = false
    }
}
