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

/** The E-Hentai login cookies (encrypted). */
object EhAuth : EhCredentialStorage {
    private val store = SecureStore("aenht.eh")

    fun init(context: Context) = store.init(context, "eh_auth")

    override val memberId: String? get() = store["member"]
    override val passHash: String? get() = store["hash"]
    override val igneous: String? get() = store["igneous"]

    override fun save(memberId: String, passHash: String, igneous: String?) {
        store["member"] = memberId
        store["hash"] = passHash
        store["igneous"] = igneous
        EhSession.refresh()
    }

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

    /** Saves cookies from the login page or typed by the user, then checks them with the site. */
    suspend fun login(memberId: String, passHash: String, igneous: String?): Boolean {
        EhAuth.save(memberId.trim(), passHash.trim(), igneous?.trim()?.takeIf { it.isNotEmpty() })
        val ok = runCatching { EhClient.api.verifyLogin() }.getOrDefault(false)
        if (!ok) EhAuth.clear()
        refresh()
        if (ok) AccountBook.rememberEh()
        return ok
    }

    fun logout() {
        AccountBook.forgetActive(com.atlanyuey.aenht.shared.site.Site.EHENTAI)
        EhClient.api.logout()
        refresh()
    }
}

/** E-Hentai (shared module) with the app's localized errors. */
object EhClient {
    val api: EhApi by lazy {
        AndroidHttp.client = Net.client
        EhApi(EhAuth).also { it.useExHentai = AppSettings.useExHentai }
    }

    fun localized(e: Throwable): String = when (e) {
        is EhException.Banned -> L10n.string("E-Hentai has paused access from this network for a while (too many requests). Try again later.")
        is EhException.NoAccess -> L10n.string("This account can't open ExHentai. Turn off ExHentai in Settings › E-Hentai.")
        is EhException.NotFound -> L10n.string("This gallery isn't available.")
        is EhException.NotLoggedIn -> L10n.string("Log in to your E-Hentai account first.")
        is EhException.LimitReached -> L10n.string("The image limit of this account has been reached. Try again later.")
        is EhException.Http -> L10n.string("Request failed (%lld)", e.code)
        is EhException.Parse -> L10n.string("Unexpected response from the server")
        is EhException.Network -> e.message ?: L10n.string("Unexpected response from the server")
        else -> e.localizedMessage ?: e.toString()
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
