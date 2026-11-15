package com.atlanyuey.aenht.core

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.atlanyuey.aenht.shared.db.AenhtDatabase
import com.atlanyuey.aenht.shared.db.FavoriteRecord
import com.atlanyuey.aenht.shared.db.FavoritesMirror
import com.atlanyuey.aenht.shared.db.openDatabase
import com.atlanyuey.aenht.shared.site.GalleryKey
import com.atlanyuey.aenht.shared.site.GallerySummary
import com.atlanyuey.aenht.shared.site.Site
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch

/** The app's one database (shared module). */
object AppDb {
    @Volatile private var db: AenhtDatabase? = null

    fun get(context: Context): AenhtDatabase =
        db ?: synchronized(this) { db ?: openDatabase(context).also { db = it } }
}

/**
 * A local copy of both sites' account favorites, for Favorites › All (both
 * sites in one list by time) and for offline use.
 *
 * nhentai doesn't say when a gallery was favorited: on the first sync its
 * favorites keep their order and are spread just before the sync time;
 * galleries favorited in the app, or first seen by a later sync, get the
 * time they were seen.
 */
object FavoritesStore {
    private lateinit var appContext: Context
    // Created on first use: the API clients must not be built during app start-up.
    private val mirror: FavoritesMirror by lazy { FavoritesMirror(AppDb.get(appContext), NHClient.api, EhClient.api) }
    val records = mutableStateListOf<FavoriteRecord>()
    var syncing by mutableStateOf<Site?>(null)
        private set
    /** Galleries read by the running sync. */
    var syncedCount by mutableIntStateOf(0)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    private val lastSync = mutableMapOf<Site, Long>()

    /** The account each site's copy was read from (the saved account's id), kept with the copy. */
    private val owners = mutableStateMapOf<Site, String>()
    private val prefs by lazy { appContext.getSharedPreferences("favorites", Context.MODE_PRIVATE) }
    private var syncJob: Job? = null

    fun init(context: Context) {
        appContext = context.applicationContext
        for (site in Site.entries) prefs.getString("owner.${site.key}", null)?.let { owners[site] = it }
    }

    /** The account logged in on [site] (null when none, or not known yet). */
    private fun account(site: Site): String? = AccountBook.accounts(site).firstOrNull { AccountBook.isActive(it) }?.id

    /**
     * Whether [site]'s copy is the logged-in account's: another account's
     * copy (after switching, until the sync) and a logged-out site's stay hidden.
     */
    private fun showsCopy(site: Site): Boolean {
        if (!loggedIn(site)) return false
        val owner = owners[site] ?: return true
        return owner == account(site)
    }

    /** Reads the copy again (after WebDAV sync added to it). */
    suspend fun reloadFromDatabase() = reload()

    private suspend fun reload() {
        val all = runCatching { mirror.all() }.getOrDefault(emptyList())
        records.clear()
        records.addAll(all)
    }

    private var loaded = false

    fun cards(site: Site?, ehFolders: Set<Int>?): List<GallerySummary> = records
        .filter { site == null || it.site == site.key }
        .filter { record -> Site.fromKey(record.site)?.let(::showsCopy) ?: false }
        .filter { it.site != Site.EHENTAI.key || ehFolders == null || it.folder in ehFolders }
        .map(GallerySummary::from)

    /** A gallery was favorited (or moved) in the app: known exactly when. */
    fun added(card: GallerySummary, folder: Int = 0) {
        AppScope.launch {
            runCatching { mirror.added(card, folder) }
            reload()
        }
    }

    fun removed(key: GalleryKey) {
        AppScope.launch {
            runCatching { mirror.removed(key) }
            reload()
        }
    }

    /**
     * The login of [site] changed (another account, a new login, or none):
     * a sync that is running was the previous account's and stops; the new
     * account's favorites are read.
     */
    fun accountChanged(site: Site) {
        lastSync.remove(site)
        val running = syncJob
        AppScope.launch {
            running?.cancelAndJoin()
            syncing = null
            sync(listOf(site))
        }
    }

    /** Syncs when the last sync of a logged-in site is older than [maxAge]. */
    fun syncIfStale(maxAge: Long = 15 * 60 * 1000L) {
        if (!loaded) {
            loaded = true
            AppScope.launch { reload() }
        }
        val now = System.currentTimeMillis()
        val due = AppSettings.enabledSites.filter { loggedIn(it) && now - (lastSync[it] ?: 0) > maxAge }
        if (due.isNotEmpty()) sync(due)
    }

    private fun loggedIn(site: Site) = if (site == Site.NHENTAI) Session.isLoggedIn else EhSession.isLoggedIn

    fun sync(sites: List<Site> = AppSettings.enabledSites) {
        if (syncing != null) return
        syncJob = AppScope.launch {
            error = null
            for (site in sites.filter(::loggedIn)) {
                syncing = site
                syncedCount = 0
                try {
                    // The copy of another account is dropped before this account's is read
                    // (when a sync fails, nothing of the other account shows meanwhile).
                    val who = account(site)
                    if (who != null && owners[site] != who) {
                        if (owners[site] != null) mirror.clear(site)
                        owners[site] = who
                        prefs.edit().putString("owner.${site.key}", who).apply()
                        reload()
                    }
                    EhClient.api.useExHentai = AppSettings.useExHentai
                    mirror.sync(site) { count -> AppScope.launch { syncedCount = count } }
                    lastSync[site] = System.currentTimeMillis()
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: com.atlanyuey.aenht.shared.eh.EhException) {
                    error = EhClient.localized(e)
                } catch (e: Exception) {
                    error = e.localizedMessage
                }
            }
            syncing = null
            reload()
        }
    }
}
