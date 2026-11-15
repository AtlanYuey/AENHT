package com.atlanyuey.aenht.core

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.atlanyuey.aenht.shared.site.Site
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** A saved login of a site; [values] are its credentials (kept in the Keystore). */
@Serializable
data class SavedAccount(
    val id: String,
    val site: String,
    val name: String,
    val avatar: String? = null,
    val values: Map<String, String>,
)

/**
 * Several accounts per site: each login is saved here, and switching puts
 * an account's credentials back as the active login.
 */
object AccountBook {
    private val store = SecureStore("aenht.accounts")
    private val json = Json { ignoreUnknownKeys = true }

    var accounts by mutableStateOf(emptyList<SavedAccount>())
        private set

    fun init(context: Context) {
        store.init(context, "accounts")
        accounts = store["list"]?.let { runCatching { json.decodeFromString(ListSerializer(SavedAccount.serializer()), it) }.getOrNull() } ?: emptyList()
    }

    private fun save(list: List<SavedAccount>) {
        accounts = list
        store["list"] = json.encodeToString(ListSerializer(SavedAccount.serializer()), list)
    }

    fun accounts(site: Site) = accounts.filter { it.site == site.key }

    fun isActive(account: SavedAccount): Boolean = when (account.site) {
        Site.NHENTAI.key -> account.values["access"] == Auth.accessToken
        else -> account.values["member"] == EhAuth.memberId
    }

    /** The nhentai login was renewed: its saved account keeps up, or switching back to it would use dead tokens. */
    fun tokensRenewed(previousAccess: String, access: String, refresh: String?) {
        if (accounts.none { it.site == Site.NHENTAI.key && it.values["access"] == previousAccess }) return
        save(accounts.map { account ->
            if (account.site == Site.NHENTAI.key && account.values["access"] == previousAccess) {
                account.copy(values = buildMap { put("access", access); refresh?.let { put("refresh", it) } })
            } else {
                account
            }
        })
    }

    private fun upsert(account: SavedAccount) {
        save(accounts.filter { it.id != account.id } + account)
    }

    /** Saves the active nhentai login (after logging in or reloading the user). */
    fun rememberNh() {
        val access = Auth.accessToken ?: return
        val user = Session.user
        val id = "nh:" + (user?.id?.toString() ?: access.hashCode().toString())
        val values = buildMap {
            put("access", access)
            Auth.refreshToken?.let { put("refresh", it) }
        }
        upsert(SavedAccount(id, Site.NHENTAI.key, user?.username ?: "nhentai", user?.avatar, values))
    }

    /** Saves the active E-Hentai login. */
    fun rememberEh() {
        val member = EhAuth.memberId ?: return
        val hash = EhAuth.passHash ?: return
        val values = buildMap {
            put("member", member)
            put("hash", hash)
            EhAuth.igneous?.let { put("igneous", it) }
        }
        upsert(SavedAccount("eh:$member", Site.EHENTAI.key, "ID $member", null, values))
    }

    /** Makes [account] the active login of its site. */
    suspend fun switchTo(account: SavedAccount) {
        when (account.site) {
            Site.NHENTAI.key -> {
                Auth.clear()
                Session.login(account.values["access"] ?: return, account.values["refresh"])
            }
            else -> {
                EhAuth.clear()
                EhAuth.save(account.values["member"] ?: return, account.values["hash"] ?: return, account.values["igneous"])
                EhSession.refresh()
                com.atlanyuey.aenht.screens.EhFolders.reset()
                com.atlanyuey.aenht.screens.EhFolders.load()
            }
        }
        // nhentai's login above already told the favorites copy.
        if (account.site != Site.NHENTAI.key) FavoritesStore.accountChanged(Site.EHENTAI)
    }

    fun forget(account: SavedAccount) = save(accounts.filter { it.id != account.id })

    /** Forgets the active account of [site] (logging out). */
    fun forgetActive(site: Site) = save(accounts.filter { !(it.site == site.key && isActive(it)) })
}
