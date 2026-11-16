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
import com.atlanyuey.aenht.shared.nh.TokenSnapshot
import com.atlanyuey.aenht.shared.nh.TokenStorage
import kotlinx.serialization.json.Json
import kotlinx.coroutines.launch
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
                val request = chain.request()
                val builder = request.newBuilder().header("User-Agent", userAgent)
                // Covers and thumbnails on ExHentai's servers need the account's cookies.
                if (request.header("Cookie") == null && request.url.host.endsWith("hentai.org")) {
                    EhClient.api.imageCookieHeader(request.url.toString())?.let { builder.header("Cookie", it) }
                }
                chain.proceed(builder.build())
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
        NhApi(Auth).also {
            it.onNeedsVerification = { Session.needsVerification = true }
            RateNotice.watch(it.limiter)
        }
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
        is NhException.LoginChanged -> L10n.string("Please log in first")
        is NhException.NotSaved -> L10n.string("The login couldn't be saved on this device.")
    }

    suspend fun galleries(query: String, sort: SearchSort, page: Int) = call { galleries(query, sort.raw, page) }
    suspend fun popular() = call { popular() }
    suspend fun randomId() = call { randomGalleryId() }
    suspend fun gallery(id: Int) = call { gallery(id) }
    suspend fun galleryJson(id: Int): JsonObject = call { galleryJson(id) }
    /** The newest comments (the first page). */
    suspend fun comments(id: Int) = call { comments(id).result }
    suspend fun commentsPage(id: Int, page: Int) = call { comments(id, page) }
    suspend fun favorites(page: Int, query: String = "") = call { favorites(page, query) }
    suspend fun setFavorite(id: Int, favorite: Boolean) = call { setFavorite(id, favorite) }
    suspend fun currentUser() = call { currentUser() }
    suspend fun postComment(id: Int, body: String) = call { postComment(id, body) }
    suspend fun suggestTags(text: String) = call { suggestTags(text) }
}

/**
 * Small secrets (tokens, login cookies) encrypted with a key that never
 * leaves the Android Keystore; app-private and excluded from backups.
 */
class SecureStore(private val keyAlias: String) {
    private lateinit var prefs: SharedPreferences

    fun init(context: Context, name: String) {
        prefs = context.getSharedPreferences(name, Context.MODE_PRIVATE)
    }

    operator fun get(name: String): String? =
        prefs.getString(name, null)?.let { decrypt(it) }?.takeIf { it.isNotEmpty() }

    operator fun set(name: String, value: String?) {
        putAll(mapOf(name to value))
    }

    private val lock = Any()

    /** Runs [block] with no other change to this store in between (read, compare, write). */
    fun <T> locked(block: () -> T): T = synchronized(lock, block)

    /**
     * Saves [values] (null or empty removes one), all of them or none: false
     * when the Keystore or the disk refused, and what was saved stays (also
     * in memory: a write the disk refused is undone). When even that fails,
     * the values are removed, so no mix of two logins is ever left.
     */
    fun putAll(values: Map<String, String?>): Boolean = locked {
        val encrypted = values.mapValues { (name, value) ->
            if (value.isNullOrEmpty()) null else encrypt(value) ?: run {
                com.atlanyuey.aenht.shared.app.Diagnostics.log("keystore", "Couldn't save \"$name\"")
                return@locked false
            }
        }
        val before = values.keys.associateWith { prefs.getString(it, null) }
        if (write(encrypted)) return@locked true
        com.atlanyuey.aenht.shared.app.Diagnostics.log("keystore", "Couldn't write ${values.keys}")
        // SharedPreferences changed in memory before the disk refused: put the old values back.
        if (!write(before)) {
            com.atlanyuey.aenht.shared.app.Diagnostics.log("keystore", "Couldn't restore ${values.keys}: removed")
            write(values.keys.associateWith { null })
        }
        false
    }

    private fun write(values: Map<String, String?>): Boolean {
        val edit = prefs.edit()
        values.forEach { (name, value) -> if (value == null) edit.remove(name) else edit.putString(name, value) }
        return edit.commit()
    }

    fun clear() {
        locked { prefs.edit().clear().commit() }
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(keyAlias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(
                KeyGenParameterSpec.Builder(keyAlias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
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

/** The nhentai API tokens. */
object Auth : TokenStorage {
    private val store = SecureStore("aenht.tokens")

    fun init(context: Context) = store.init(context, "auth")

    override val accessToken: String? get() = store["access"]
    override val refreshToken: String? get() = store["refresh"]
    val isLoggedIn: Boolean get() = accessToken != null

    private var revision = 0L
    override fun snapshot(): TokenSnapshot = store.locked { TokenSnapshot(accessToken, refreshToken, revision) }

    override fun renew(expected: TokenSnapshot, access: String, refresh: String?): Boolean {
        val saved = store.locked {
            if (snapshot() != expected) return@locked false
            store.putAll(buildMap {
                put("access", access)
                if (!refresh.isNullOrEmpty()) put("refresh", refresh)
            })
        }
        val renewedRefresh = refresh?.takeIf { it.isNotEmpty() } ?: expected.refreshToken
        if (saved && expected.accessToken != access) AccountBook.tokensRenewed(expected.accessToken.orEmpty(), access, renewedRefresh)
        if (!saved) reconcileFailedWrite()
        return saved
    }

    override fun replace(expected: TokenSnapshot, access: String, refresh: String?): Boolean {
        val saved = store.locked {
            if (snapshot() != expected) return@locked false
            if (!store.putAll(mapOf("access" to access, "refresh" to refresh))) return@locked false
            revision++
            true
        }
        if (!saved) reconcileFailedWrite()
        return saved
    }

    fun replace(access: String, refresh: String?): Boolean {
        val saved = store.locked {
            if (!store.putAll(mapOf("access" to access, "refresh" to refresh))) return@locked false
            revision++
            true
        }
        if (!saved) reconcileFailedWrite()
        return saved
    }

    private fun reconcileFailedWrite() {
        AppScope.launch { Session.reconcileCredentialFailure() }
    }

    override fun clear() = store.locked { revision++; store.clear() }

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

    /**
     * Counts the changes of login (log in, log out, switch): an answer to a
     * request of an earlier login (its user, its blacklist) isn't kept.
     */
    @Volatile var generation = 0
        private set

    suspend fun reload() {
        isLoggedIn = Auth.isLoggedIn
        if (!isLoggedIn) {
            user = null
            return
        }
        val mine = generation
        val token = Auth.accessToken
        try {
            val current = NHClient.currentUser()
            // Another login meanwhile: this user isn't its user.
            if (mine != generation || Auth.accessToken != token) return
            user = current
            AccountBook.rememberNh()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
        }
    }

    /** Logs in with an API key (nhentai.net › Settings › API Keys). */
    suspend fun loginWithApiKey(key: String) {
        val mine = ++generation
        val current = try {
            // Checked on its own and saved only if the login is still the one there was.
            NHClient.api.loginWithApiKey(key)
        } catch (_: com.atlanyuey.aenht.shared.nh.NhException.LoginChanged) {
            return // Logged out or into another account meanwhile: that one stays.
        } catch (_: com.atlanyuey.aenht.shared.nh.NhException.NotSaved) {
            throw NHException(L10n.string("The login couldn't be saved on this device."))
        } catch (e: com.atlanyuey.aenht.shared.nh.NhException) {
            throw NHException(e.message ?: L10n.string("Couldn't Log In"))
        }
        if (mine != generation) return
        user = current
        isLoggedIn = true
        needsVerification = false
        AccountBook.rememberNh()
        accountChanged()
    }

    /** What belonged to the previous login (favorites copy, blacklist) follows the new one. */
    private fun accountChanged() {
        Filters.accountChanged()
        FavoritesStore.accountChanged(com.atlanyuey.aenht.shared.site.Site.NHENTAI)
    }

    /** Logs in with tokens; false when they couldn't be saved on this device (the login there was stays). */
    suspend fun login(access: String, refresh: String?): Boolean {
        // Nothing of the login before stays: not its refresh token, not its user.
        if (!Auth.replace(access, refresh)) return false
        generation++
        user = null
        needsVerification = false
        reload()
        accountChanged()
        return true
    }

    internal fun reconcileCredentialFailure() {
        if (Auth.isLoggedIn) return
        generation++
        user = null
        isLoggedIn = false
        needsVerification = false
        accountChanged()
    }

    fun logout() {
        generation++
        AccountBook.forgetActive(com.atlanyuey.aenht.shared.site.Site.NHENTAI)
        Auth.clear()
        user = null
        isLoggedIn = false
        needsVerification = false
        accountChanged()
    }
}
