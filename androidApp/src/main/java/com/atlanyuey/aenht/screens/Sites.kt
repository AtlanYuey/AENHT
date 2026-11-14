package com.atlanyuey.aenht.screens

import android.annotation.SuppressLint
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.atlanyuey.aenht.core.*
import com.atlanyuey.aenht.shared.site.Site
import com.atlanyuey.aenht.ui.components.*
import com.atlanyuey.aenht.ui.theme.AppTheme
import com.atlanyuey.aenht.ui.theme.Icons
import com.atlanyuey.aenht.ui.theme.Type
import kotlinx.coroutines.launch

// MARK: - Accounts

/** The accounts of both sites (Me › Accounts). */
class AccountsScreen : Screen() {
    @Composable
    override fun Content() {
        val navigator = LocalNavigator.current
        GroupedListScreen(tr("Accounts")) {
            if (AppSettings.nhentaiEnabled) {
                section("nhentai") {
                    if (Session.isLoggedIn) {
                        ListRow(Session.user?.username ?: tr("Logged In"), subtitle = tr("nhentai account"),
                            leading = { Avatar(Session.user?.avatar, 44) }, chevron = true, last = true,
                            onClick = { navigator.push(AccountScreen()) })
                    } else {
                        ListRow(tr("Log In"), subtitle = tr("Sync favorites and post comments"),
                            leading = { Avatar(null, 44) }, chevron = true, last = true,
                            onClick = { navigator.push(LoginScreen()) })
                    }
                }
            }
            if (AppSettings.ehentaiEnabled) {
                section("E-Hentai") {
                    if (EhSession.isLoggedIn) {
                        ListRow(tr("Logged In"),
                            subtitle = if (EhSession.hasExHentai) tr("E-Hentai account · ExHentai access") else tr("E-Hentai account"),
                            leading = { Avatar(null, 44) }, chevron = true, last = true,
                            onClick = { navigator.push(EhAccountScreen()) })
                    } else {
                        ListRow(tr("Log In"), subtitle = tr("Favorites, ratings, watched tags and ExHentai"),
                            leading = { Avatar(null, 44) }, chevron = true,
                            onClick = { navigator.push(EhLoginScreen()) })
                        ListRow(tr("Log In with Cookies"), chevron = true, last = true,
                            onClick = { navigator.push(EhCookieLoginScreen()) })
                    }
                }
            }
        }
    }
}

/** Subtitle of the Accounts row in Me: who is logged in where. */
@Composable
fun accountsSummary(): String {
    val parts = buildList {
        if (AppSettings.nhentaiEnabled && Session.isLoggedIn) add("nhentai")
        if (AppSettings.ehentaiEnabled && EhSession.isLoggedIn) add(if (EhSession.hasExHentai) "E-Hentai · ExHentai" else "E-Hentai")
    }
    return if (parts.isEmpty()) tr("Not logged in") else parts.joinToString(" · ")
}

class EhAccountScreen : Screen() {
    @Composable
    override fun Content() {
        val navigator = LocalNavigator.current
        val overlays = LocalOverlays.current
        val logoutTitle = tr("Log out of E-Hentai?")
        val logout = tr("Log Out")
        var checking by remember { mutableStateOf(false) }
        GroupedListScreen(tr("E-Hentai")) {
            section {
                ListRow(tr("Member ID"), value = EhAuth.memberId ?: "-")
                ListRow(tr("ExHentai"), value = if (EhSession.hasExHentai) tr("Available") else tr("Not available"), last = true)
            }
            section(footer = { FooterText(tr("ExHentai shows galleries that E-Hentai hides. It needs an account that has been given access.")) }) {
                ToggleRow(tr("Use ExHentai"), AppSettings.useExHentai && EhSession.hasExHentai, { AppSettings.useExHentai = it }, last = !checking)
                ButtonRow(tr("Check Access Again"), {
                    checking = true
                    scope.launch {
                        runCatching { EhClient.api.verifyLogin() }
                        EhSession.refresh()
                        checking = false
                    }
                }, enabled = !checking, last = true)
            }
            section {
                ButtonRow(logout, {
                    overlays.actionSheet(logoutTitle, null, listOf(DialogButton(logout, ButtonRole.Destructive) {
                        EhSession.logout()
                        AppSettings.useExHentai = false
                        navigator.pop()
                    }))
                }, destructive = true, last = true)
            }
        }
    }
}

/**
 * Logs in on the E-Hentai forums in a web view (the site's own page and
 * checks); the login cookies are read from it and checked with the site.
 */
class EhLoginScreen : Screen() {
    private var loading by mutableStateOf(true)
    private var done = false

    @SuppressLint("SetJavaScriptEnabled")
    @Composable
    override fun Content() {
        val navigator = LocalNavigator.current
        val overlays = LocalOverlays.current
        val failedTitle = tr("Couldn't Log In")
        val failedMessage = tr("E-Hentai didn't accept the login. Try again, or log in with cookies.")
        ScreenScaffold(
            title = tr("Log In"),
            trailing = {
                if (loading) ActivityIndicator()
                TextButton(tr("Cookies"), { navigator.push(EhCookieLoginScreen()) })
            },
        ) { padding ->
            AndroidView(
                modifier = Modifier.fillMaxSize().padding(top = padding.calculateTopPadding()),
                factory = { context ->
                    WebView(context).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        CookieManager.getInstance().setAcceptCookie(true)
                        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                        webViewClient = object : WebViewClient() {
                            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                                loading = true
                                check()
                            }

                            override fun onPageFinished(view: WebView?, url: String?) {
                                loading = false
                                check()
                            }

                            private fun check() {
                                if (done) return
                                val cookies = listOf("https://forums.e-hentai.org", "https://e-hentai.org")
                                    .mapNotNull { CookieManager.getInstance().getCookie(it) }
                                    .flatMap { it.split(";") }
                                    .mapNotNull { c -> c.trim().split("=", limit = 2).takeIf { it.size == 2 }?.let { it[0] to it[1] } }
                                    .toMap()
                                val member = cookies["ipb_member_id"]?.takeIf { it.isNotEmpty() && it != "0" } ?: return
                                val hash = cookies["ipb_pass_hash"]?.takeIf { it.isNotEmpty() } ?: return
                                done = true
                                scope.launch {
                                    if (EhSession.login(member, hash, null)) {
                                        navigator.pop()
                                    } else {
                                        done = false
                                        overlays.alert(failedTitle, failedMessage)
                                    }
                                }
                            }
                        }
                        loadUrl("https://forums.e-hentai.org/index.php?act=Login&CODE=00")
                    }
                },
            )
        }
    }
}

/** Logs in with the cookies of a browser where the user is logged in. */
class EhCookieLoginScreen : Screen() {
    private var memberId by mutableStateOf("")
    private var passHash by mutableStateOf("")
    private var igneous by mutableStateOf("")
    private var working by mutableStateOf(false)

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.current
        val overlays = LocalOverlays.current
        val failedTitle = tr("Couldn't Log In")
        val failedMessage = tr("E-Hentai didn't accept these cookies.")
        GroupedListScreen(tr("Log In with Cookies")) {
            section(footer = { FooterText(tr("Copy these cookies of e-hentai.org from a browser where you are logged in. igneous is only needed for ExHentai, and is usually picked up by itself.")) }) {
                Field("ipb_member_id", memberId) { memberId = it }
                Field("ipb_pass_hash", passHash) { passHash = it }
                Field("igneous", igneous, last = true) { igneous = it }
            }
            section {
                ButtonRow(tr("Log In"), {
                    working = true
                    scope.launch {
                        val ok = EhSession.login(memberId, passHash, igneous)
                        working = false
                        if (ok) navigator.popTo { it is AccountsScreen || it is MoreScreen } else overlays.alert(failedTitle, failedMessage)
                    }
                }, enabled = !working && memberId.isNotBlank() && passHash.isNotBlank(), last = true)
            }
        }
    }

    @Composable
    private fun Field(name: String, value: String, last: Boolean = false, onChange: (String) -> Unit) {
        val colors = AppTheme.colors
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Txt(name, style = Type.footnote, color = colors.secondaryLabel)
            BasicTextField(
                value, onChange,
                singleLine = true,
                textStyle = Type.body.copy(color = colors.label),
                cursorBrush = SolidColor(AppTheme.tint),
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(colors.tertiaryFill).padding(horizontal = 12.dp, vertical = 10.dp),
            )
        }
        if (!last) RowSeparator()
    }
}

// MARK: - Sites settings

/** Me › Sites: which sites are used and what each screen opens on. */
class SitesScreen : Screen() {
    @Composable
    override fun Content() {
        val navigator = LocalNavigator.current
        val both = AppSettings.nhentaiEnabled && AppSettings.ehentaiEnabled
        GroupedListScreen(tr("Sites")) {
            section("Sites in Use", footer = { FooterText(tr("A site that is turned off disappears everywhere in the app. At least one stays on.")) }) {
                ToggleRow("nhentai", AppSettings.nhentaiEnabled, { if (it || AppSettings.ehentaiEnabled) AppSettings.nhentaiEnabled = it })
                ToggleRow("E-Hentai", AppSettings.ehentaiEnabled, { if (it || AppSettings.nhentaiEnabled) AppSettings.ehentaiEnabled = it }, last = true)
            }
            if (both) {
                section("Show When Opened", footer = { FooterText(tr("“Same as Home” shows the site the home page is on.")) }) {
                    PickerRow(tr("Home"), listOf(SiteChoice.Follow, SiteChoice.Nhentai, SiteChoice.Ehentai), AppSettings.homeSiteDefault,
                        { if (it == SiteChoice.Follow) tr("Last Used") else tr(it.title) }, { AppSettings.homeSiteDefault = it })
                    PickerRow(tr("Search"), SiteChoice.entries.filter { it != SiteChoice.All }, AppSettings.searchSite, { tr(it.title) }, { AppSettings.searchSite = it })
                    PickerRow(tr("Favorites"), SiteChoice.entries, AppSettings.favoritesSite, { tr(it.title) }, { AppSettings.favoritesSite = it })
                    PickerRow(tr("History"), SiteChoice.entries, AppSettings.historySite, { tr(it.title) }, { AppSettings.historySite = it })
                    PickerRow(tr("Downloads"), SiteChoice.entries, AppSettings.downloadsSite, { tr(it.title) }, { AppSettings.downloadsSite = it }, last = true)
                }
                section(footer = { FooterText(tr("Searches both sites at once. E-Hentai pauses networks that send too many requests, so this is off by default.")) }) {
                    ToggleRow(tr("Search Both Sites"), AppSettings.searchBothSites, { AppSettings.searchBothSites = it }, last = true)
                }
            }
            if (AppSettings.ehentaiEnabled) {
                section("E-Hentai") {
                    ListRow(tr("Account"), value = if (EhSession.isLoggedIn) tr("Logged In") else tr("Not logged in"), chevron = true,
                        onClick = { navigator.push(if (EhSession.isLoggedIn) EhAccountScreen() else EhLoginScreen()) })
                    ToggleRow(tr("Use ExHentai"), AppSettings.useExHentai && EhSession.hasExHentai, {
                        if (EhSession.hasExHentai) AppSettings.useExHentai = it
                    }, last = true, subtitle = if (EhSession.hasExHentai) null else tr("Needs an account with ExHentai access"))
                }
            }
        }
    }
}

// MARK: - Filters

/** Me › Filters: rules that hide galleries of both sites from lists. */
class FiltersScreen : Screen() {
    private var pattern by mutableStateOf("")
    private var target by mutableStateOf("tag")
    private var regex by mutableStateOf(false)

    @Composable
    override fun Content() {
        val rules = Filters.rules
        val targets = listOf("tag", "title", "uploader")
        val targetTitle: @Composable (String) -> String = { t ->
            when (t) {
                "tag" -> tr("Tag")
                "title" -> tr("Title")
                else -> tr("Uploader")
            }
        }
        GroupedListScreen(tr("Filters")) {
            section(footer = { FooterText(tr("Galleries that match a rule are left out of Home and search results on both sites. A tag rule matches the tag in any namespace (nhentai's tag: and E-Hentai's female:, male:, …).")) }) {
                PickerRow(tr("Match"), targets, target, targetTitle, { target = it })
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
                    BasicTextField(
                        pattern, { pattern = it },
                        singleLine = true,
                        textStyle = Type.body.copy(color = AppTheme.colors.label),
                        cursorBrush = SolidColor(AppTheme.tint),
                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(AppTheme.colors.tertiaryFill)
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                    )
                }
                RowSeparator()
                ToggleRow(tr("Regular Expression"), regex, { regex = it })
                ButtonRow(tr("Add Rule"), {
                    Filters.add(target, pattern, regex)
                    pattern = ""
                }, enabled = pattern.isNotBlank(), last = true)
            }
            if (rules.isNotEmpty()) {
                section("Rules") {
                    rules.forEachIndexed { i, rule ->
                        ListRow(
                            rule.pattern,
                            subtitle = targetTitle(rule.target) + if (rule.regex) " · " + tr("Regular Expression") else "",
                            last = i == rules.lastIndex,
                            trailing = {
                                GlassToggle(rule.enabled, { Filters.setEnabled(rule.id, it) })
                                Box(Modifier.size(36.dp).clickable { Filters.remove(rule.id) }, contentAlignment = Alignment.Center) {
                                    AppIcon(Icons.Trash, tr("Delete"), tint = AppTheme.colors.red, size = 18.dp)
                                }
                            },
                        )
                    }
                }
            }
            if (AppSettings.nhentaiEnabled) {
                section(footer = { FooterText(tr("Uses the tag blacklist of your nhentai account (nhentai.net › Settings › Blacklist).")) }) {
                    ToggleRow(tr("nhentai Account Blacklist"), AppSettings.useNhBlacklist, {
                        AppSettings.useNhBlacklist = it
                        Filters.refreshNhBlacklist(force = true)
                    }, last = true, subtitle = if (Session.isLoggedIn) tr("%lld tags", Filters.nhBlacklistCount) else tr("Not logged in"))
                }
            }
        }
    }
}

// MARK: - Translation

/** Me › Translation: the language models (in order) and Microsoft Translator. */
class TranslationSettingsScreen : Screen() {
    private var name by mutableStateOf("")
    private var baseUrl by mutableStateOf("https://api.openai.com/v1")
    private var model by mutableStateOf("")
    private var key by mutableStateOf("")
    private var msKey by mutableStateOf(TranslationService.microsoftKey ?: "")
    private var msRegion by mutableStateOf(TranslationService.microsoftRegion ?: "")
    private var revision by mutableStateOf(0)

    @Composable
    private fun Field(label: String, value: String, last: Boolean = false, secret: Boolean = false, onChange: (String) -> Unit) {
        val colors = AppTheme.colors
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Txt(label, style = Type.footnote, color = colors.secondaryLabel)
            BasicTextField(
                value, onChange,
                singleLine = true,
                textStyle = Type.body.copy(color = colors.label),
                cursorBrush = SolidColor(AppTheme.tint),
                visualTransformation = if (secret) androidx.compose.ui.text.input.PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(colors.tertiaryFill).padding(horizontal = 12.dp, vertical = 10.dp),
            )
        }
        if (!last) RowSeparator()
    }

    @Composable
    override fun Content() {
        revision
        val services = TranslationService.services
        GroupedListScreen(tr("Translation")) {
            if (services.isNotEmpty()) {
                section("Language Models", footer = { FooterText(tr("Tried from top to bottom: a model that refuses or fails hands over to the next one, then to Microsoft Translator.")) }) {
                    services.forEachIndexed { i, s ->
                        ListRow(
                            s.name,
                            subtitle = s.model + if (TranslationService.hasKey(s.id)) "" else " · " + tr("No key"),
                            last = i == services.lastIndex,
                            trailing = {
                                GlassToggle(s.enabled, { TranslationService.setEnabled(s.id, it); revision++ })
                                Box(Modifier.size(32.dp).clickable { TranslationService.move(s.id, up = true); revision++ }, contentAlignment = Alignment.Center) {
                                    AppIcon(Icons.ArrowUp, tr("Move Up"), size = 16.dp)
                                }
                                Box(Modifier.size(32.dp).clickable { TranslationService.remove(s.id); revision++ }, contentAlignment = Alignment.Center) {
                                    AppIcon(Icons.Trash, tr("Delete"), tint = AppTheme.colors.red, size = 16.dp)
                                }
                            },
                        )
                    }
                }
            }
            section("Add a Language Model", footer = { FooterText(tr("Any service with an OpenAI-compatible API. Keys stay on this device (Android Keystore) and are not exported with the settings.")) }) {
                Field(tr("Name"), name) { name = it }
                Field(tr("API Address"), baseUrl) { baseUrl = it }
                Field(tr("Model"), model) { model = it }
                Field(tr("API Key"), key, secret = true) { key = it }
                ButtonRow(tr("Add"), {
                    TranslationService.add(name, baseUrl, model, key)
                    name = ""; model = ""; key = ""
                    revision++
                }, enabled = baseUrl.isNotBlank() && model.isNotBlank() && key.isNotBlank(), last = true)
            }
            section("Microsoft Translator", footer = { FooterText(tr("Used when no language model is set up or all of them fail. A key from Azure Translator has a free monthly allowance.")) }) {
                Field(tr("API Key"), msKey, secret = true) { msKey = it }
                Field(tr("Region (optional)"), msRegion) { msRegion = it }
                ButtonRow(tr("Save"), {
                    TranslationService.microsoftKey = msKey
                    TranslationService.microsoftRegion = msRegion
                    revision++
                }, last = true)
            }
            section(footer = { FooterText(tr("The last fallback, free: Google's on-device translation (ML Kit). The first time, it downloads a language model of about 30 MB for each language.")) }) {
                ToggleRow(tr("On-Device Translation"), AppSettings.deviceTranslation, { AppSettings.deviceTranslation = it }, last = true)
            }
        }
    }
}
