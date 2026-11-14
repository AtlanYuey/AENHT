package com.atlanyuey.aenht.core

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.atlanyuey.aenht.shared.sync.WebDavException
import com.atlanyuey.aenht.shared.sync.WebDavSync
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Sync through the user's own WebDAV server: settings, reading history and
 * the favorites copy (see the shared WebDavSync). The address and user are
 * kept on this device; the password in the Android Keystore. Neither is
 * part of the settings that are exported or synced.
 */
object WebDavService {
    private lateinit var prefs: SharedPreferences
    private lateinit var appContext: Context
    private val secrets = SecureStore("aenht.webdav")
    private val engine: WebDavSync by lazy { WebDavSync(AppDb.get(appContext)) }

    var url by mutableStateOf("")
        private set
    var user by mutableStateOf("")
        private set
    var auto by mutableStateOf(true)
        private set
    var lastSync by mutableStateOf<Long?>(null)
        private set
    var syncing by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    private var job: Job? = null

    fun init(context: Context) {
        appContext = context.applicationContext
        prefs = context.getSharedPreferences("webdav", Context.MODE_PRIVATE)
        secrets.init(context, "webdav_keys")
        url = prefs.getString("url", "") ?: ""
        user = prefs.getString("user", "") ?: ""
        auto = prefs.getBoolean("auto", true)
        lastSync = prefs.getLong("lastSync", 0L).takeIf { it > 0 }
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) = syncIfDue()
            override fun onStop(owner: LifecycleOwner) = syncIfDue(minutes = 1)
        })
    }

    private val password: String? get() = secrets["password"]

    val isConfigured: Boolean get() = url.isNotBlank() && user.isNotBlank() && !password.isNullOrEmpty()

    fun save(url: String, user: String, password: String) {
        this.url = url.trim()
        this.user = user.trim()
        prefs.edit().putString("url", this.url).putString("user", this.user).apply()
        if (password.isNotEmpty()) secrets["password"] = password
    }

    fun changeAuto(value: Boolean) {
        auto = value
        prefs.edit().putBoolean("auto", value).apply()
    }

    /** Forgets the server (the files on it stay). */
    fun disconnect() {
        url = ""; user = ""; lastSync = null; error = null
        secrets["password"] = null
        prefs.edit().clear().apply()
    }

    private fun message(e: Throwable): String = when {
        e is WebDavException && e.unauthorized -> L10n.string("The server refused the user name or password.")
        else -> e.message ?: L10n.string("Couldn't connect to the server.")
    }

    /** Checks the address and sign-in; null when they work, else what went wrong. */
    suspend fun test(url: String, user: String, password: String): String? =
        runCatching { engine.test(url.trim(), user.trim(), password.ifEmpty { this.password ?: "" }) }.exceptionOrNull()?.let(::message)

    fun syncNow(): Job? {
        if (!isConfigured || job?.isActive == true) return job
        val password = password ?: return null
        job = AppScope.launch {
            syncing = true
            error = null
            try {
                val result = engine.sync(url, user, password, Build.MODEL ?: "Android", AppSettings.toJson(), AppSettings.changedAt)
                result.settings?.let { runCatching { AppSettings.applyJson(it, changedAt = result.settingsChangedAt) } }
                if (result.historyChanged > 0) HistoryStore.reloadFromDatabase()
                if (result.favoritesAdded > 0) FavoritesStore.reloadFromDatabase()
                val now = System.currentTimeMillis()
                lastSync = now
                prefs.edit().putLong("lastSync", now).apply()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                error = message(e)
            } finally {
                syncing = false
            }
        }
        return job
    }

    /** Automatic sync, when the app opens or goes to the background, at most every few [minutes]. */
    fun syncIfDue(minutes: Int = 5) {
        if (!auto || !isConfigured) return
        val last = lastSync ?: 0L
        if (System.currentTimeMillis() - last >= minutes * 60_000L) syncNow()
    }
}
