package com.atlanyuey.aenht.core

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.atlanyuey.aenht.shared.eh.EhApi
import com.atlanyuey.aenht.shared.eh.EhComment
import com.atlanyuey.aenht.shared.eh.EhCredentialStorage
import com.atlanyuey.aenht.shared.eh.EhException
import com.atlanyuey.aenht.shared.eh.EhGalleryDetail
import com.atlanyuey.aenht.shared.eh.EhImagePage
import com.atlanyuey.aenht.shared.eh.EhListPage
import com.atlanyuey.aenht.shared.eh.EhListSource
import com.atlanyuey.aenht.shared.eh.EhThumbnail
import com.atlanyuey.aenht.shared.net.AndroidHttp
import kotlinx.coroutines.sync.withLock

/** The E-Hentai login cookies (encrypted). */
object EhAuth : EhCredentialStorage {
    private val store = SecureStore("aenht.eh")

    fun init(context: Context) = store.init(context, "eh_auth")

    override val memberId: String? get() = store["member"]
    override val passHash: String? get() = store["hash"]
    override val igneous: String? get() = store["igneous"]

    override fun save(memberId: String, passHash: String, igneous: String?): Boolean = store(memberId, passHash, igneous)

    override fun updateIgneous(memberId: String, igneous: String): Boolean = store.locked {
        this.memberId == memberId && store.putAll(mapOf("igneous" to igneous))
    }.also { if (it) EhSession.refresh() }

    /** Saves a login (all three cookies or none); false when it couldn't be saved, and the one there was stays. */
    fun store(memberId: String, passHash: String, igneous: String?): Boolean =
        store.putAll(mapOf("member" to memberId, "hash" to passHash, "igneous" to igneous)).also { EhSession.refresh() }

    override fun clear() {
        store.clear()
        EhSession.refresh()
    }
}

/** E-Hentai account state, observed by the UI. */
object EhSession {
    var isLoggedIn by mutableStateOf(false)
        private set
    var hasExHentai by mutableStateOf(false)
        private set

    fun refresh() {
        isLoggedIn = !EhAuth.memberId.isNullOrEmpty() && !EhAuth.passHash.isNullOrEmpty()
        hasExHentai = isLoggedIn && !EhAuth.igneous.isNullOrEmpty() && EhAuth.igneous != "mystery"
    }

    /** Why the last [login] failed when the site couldn't be reached (null: the site refused the cookies). */
    var loginError by mutableStateOf<String?>(null)
        private set

    /**
     * Checks cookies from the login page or typed by the user with the site
     * and logs in with them. They are checked on their own (the saved login
     * isn't touched while the site answers) and saved only when they work:
     * when they are refused, or the site can't be reached, the login there
     * was stays.
     */
    suspend fun login(memberId: String, passHash: String, igneous: String?): Boolean = loginLock.withLock {
        // One login checked at a time; a logout or account switch meanwhile wins (see [generation]).
        val mine = ++generation
        loginError = null
        val member = memberId.trim()
        val hash = passHash.trim()
        val check = try {
            EhClient.api.checkLogin(member, hash, igneous?.trim()?.takeIf { it.isNotEmpty() })
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            if (mine == generation) loginError = EhClient.localized(e)
            return@withLock false
        }
        // The login changed while this one was checked: that one stays, this one isn't used.
        if (mine != generation || !check.ok) return@withLock false
        if (!EhAuth.store(member, hash, check.igneous)) {
            loginError = L10n.string("The login couldn't be saved on this device.")
            return@withLock false
        }
        refresh()
        AccountBook.rememberEh()
        FavoritesStore.accountChanged(com.atlanyuey.aenht.shared.site.Site.EHENTAI)
        true
    }

    private val loginLock = kotlinx.coroutines.sync.Mutex()

    /** Counts the changes of login (log in, log out, switch): a check of an earlier one doesn't decide the current one. */
    @Volatile var generation = 0
        private set

    /** Another login was put in place (switching accounts). */
    fun loginReplaced() { generation++ }

    fun logout() {
        generation++
        AccountBook.forgetActive(com.atlanyuey.aenht.shared.site.Site.EHENTAI)
        EhClient.api.logout()
        WebLogins.forget(WebLogins.EHENTAI)
        refresh()
        FavoritesStore.accountChanged(com.atlanyuey.aenht.shared.site.Site.EHENTAI)
    }
}

/** E-Hentai (shared module) with the app's localized errors. */
object EhClient {
    val api: EhApi by lazy {
        AndroidHttp.client = Net.client
        EhApi(EhAuth).also {
            it.useExHentai = AppSettings.useExHentai
            RateNotice.watch(it.limiter)
        }
    }

    fun localized(e: Throwable): String = when (e) {
        is EhException.Banned -> L10n.string("E-Hentai has paused access from this network for a while (too many requests). Try again later.")
        is EhException.NoAccess -> L10n.string("This account can't open ExHentai. Turn off ExHentai in Settings › E-Hentai.")
        is EhException.NotFound -> L10n.string("This gallery isn't available.")
        is EhException.NotLoggedIn -> L10n.string("Log in to your E-Hentai account first.")
        is EhException.LimitReached -> L10n.string("The image limit of this account has been reached. Try again later.")
        is EhException.Http -> L10n.string("Request failed (%lld)", e.code)
        is EhException.Parse -> L10n.string("Unexpected response from the server")
        is EhException.Network -> L10n.string(e.message ?: com.atlanyuey.aenht.shared.net.NetFailure.OTHER)
        else -> com.atlanyuey.aenht.core.errorText(e)
    }

    private suspend fun <T> call(block: suspend EhApi.() -> T): T = try {
        api.useExHentai = AppSettings.useExHentai
        api.block()
    } catch (e: EhException) {
        throw NHException(localized(e))
    }

    suspend fun list(source: EhListSource, cursor: String? = null): EhListPage = call { list(source, cursor) }
    suspend fun gallery(gid: Long, token: String): EhGalleryDetail = call { gallery(gid, token) }
    suspend fun thumbnails(gid: Long, token: String, page: Int): List<EhThumbnail> = call { thumbnails(gid, token, page) }
    suspend fun imagePage(pageUrl: String, reloadKey: String? = null): EhImagePage = call { imagePage(pageUrl, reloadKey) }
    suspend fun setFavorite(gid: Long, token: String, folder: Int?, note: String = "") = call { setFavorite(gid, token, folder, note) }
    suspend fun favoriteFolderNames(): List<String> = call { favoriteFolderNames() }
    suspend fun rate(d: EhGalleryDetail, stars: Double): Double = call { rate(d.gid, d.token, d.apiUid, d.apiKey, stars) }
    suspend fun postComment(gid: Long, token: String, text: String): List<EhComment> = call { postComment(gid, token, text) }
    suspend fun torrents(gid: Long, token: String) = call { torrents(gid, token) }
    suspend fun archiveInfo(d: EhGalleryDetail) = call { archiveInfo(d.gid, d.token, d.archiverKey ?: "") }
    suspend fun requestArchive(d: EhGalleryDetail, option: com.atlanyuey.aenht.shared.eh.EhArchiveOption) =
        call { requestArchive(d.gid, d.token, d.archiverKey ?: "", option) }
    suspend fun voteTags(d: EhGalleryDetail, tags: String, up: Boolean) = call { voteTags(d.gid, d.token, d.apiUid, d.apiKey, tags, up) }
    suspend fun imageLimit() = call { imageLimit() }
    suspend fun favoriteNote(gid: Long, token: String) = call { favoriteNote(gid, token) }
    suspend fun voteComment(d: EhGalleryDetail, commentId: Long, up: Boolean) = call { voteComment(d.gid, d.token, d.apiUid, d.apiKey, commentId, up) }
    suspend fun galleryToken(gid: Long, pageToken: String, page: Int): String = call { galleryToken(gid, pageToken, page) }
}

/** ExHentai is in use: E-Hentai shows under ExHentai's name everywhere. */
val exHentaiActive: Boolean get() = AppSettings.useExHentai && EhSession.hasExHentai

/** The site's name as shown ("ExHentai" while it is in use). */
val com.atlanyuey.aenht.shared.site.Site.label: String
    get() = if (this == com.atlanyuey.aenht.shared.site.Site.EHENTAI && exHentaiActive) "ExHentai" else displayName

/** The site's short name on badges ("ExH" while ExHentai is in use). */
val com.atlanyuey.aenht.shared.site.Site.shortLabel: String
    get() = if (this == com.atlanyuey.aenht.shared.site.Site.EHENTAI && exHentaiActive) "ExH" else badge
