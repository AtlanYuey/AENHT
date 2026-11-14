@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.atlanyuey.aenht.screens

import android.annotation.SuppressLint
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import com.atlanyuey.aenht.ui.components.prompt
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
import com.atlanyuey.aenht.shared.translate.LlmService
import com.atlanyuey.aenht.shared.translate.LlmPreset
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
        val overlays = LocalOverlays.current
        val keyTitle = tr("Log In with API Key")
        val keyMessage = tr("Create a key on nhentai.net › Settings › API Keys.")
        val login = tr("Log In")
        val cancel = tr("Cancel")
        val failed = tr("Couldn't Log In")
        val forgetLabel = tr("Remove from This Device")
        GroupedListScreen(tr("Accounts")) {
            if (AppSettings.nhentaiEnabled) {
                val saved = AccountBook.accounts(Site.NHENTAI)
                section("nhentai", footer = { if (saved.size > 1) FooterText(tr("Tap an account to switch to it.")) }) {
                    saved.forEach { account ->
                        AccountRow(account, forgetLabel) {
                            if (AccountBook.isActive(account)) navigator.push(AccountScreen())
                            else scope.launch { AccountBook.switchTo(account) }
                        }
                    }
                    if (saved.isEmpty() && Session.isLoggedIn) {
                        ListRow(Session.user?.username ?: tr("Logged In"), subtitle = tr("nhentai account"),
                            leading = { Avatar(Session.user?.avatar, 44) }, chevron = true,
                            onClick = { navigator.push(AccountScreen()) })
                    }
                    ListRow(if (Session.isLoggedIn) tr("Add Account") else tr("Log In"), subtitle = if (Session.isLoggedIn) null else tr("Sync favorites and post comments"),
                        leading = { Avatar(null, 44) }, chevron = true,
                        onClick = { navigator.push(LoginScreen()) })
                    ListRow(keyTitle, chevron = true, last = true, onClick = {
                        overlays.prompt(keyTitle, keyMessage, "", login, cancel) { key ->
                            if (key.isBlank()) return@prompt
                            scope.launch {
                                try {
                                    Session.loginWithApiKey(key)
                                } catch (e: Exception) {
                                    overlays.alert(failed, e.localizedMessage)
                                }
                            }
                        }
                    })
                }
            }
            if (AppSettings.ehentaiEnabled) {
                val saved = AccountBook.accounts(Site.EHENTAI)
                section(Site.EHENTAI.label, footer = { if (saved.size > 1) FooterText(tr("Tap an account to switch to it.")) }) {
                    saved.forEach { account ->
                        AccountRow(account, forgetLabel) {
                            if (AccountBook.isActive(account)) navigator.push(EhAccountScreen())
                            else scope.launch { AccountBook.switchTo(account) }
                        }
                    }
                    if (saved.isEmpty() && EhSession.isLoggedIn) {
                        ListRow(tr("Logged In"),
                            subtitle = if (EhSession.hasExHentai) tr("E-Hentai account · ExHentai access") else tr("E-Hentai account"),
                            leading = { Avatar(null, 44) }, chevron = true,
                            onClick = { navigator.push(EhAccountScreen()) })
                    }
                    ListRow(if (EhSession.isLoggedIn) tr("Add Account") else tr("Log In"),
                        subtitle = if (EhSession.isLoggedIn) null else tr("Favorites, ratings, watched tags and ExHentai"),
                        leading = { Avatar(null, 44) }, chevron = true,
                        onClick = { navigator.push(EhLoginScreen()) })
                    ListRow(tr("Log In with Cookies"), chevron = true, last = true,
                        onClick = { navigator.push(EhCookieLoginScreen()) })
                }
            }
        }
    }

    /** A saved account: a check on the active one; long-press to remove it from the device. */
    @Composable
    private fun AccountRow(account: SavedAccount, forgetLabel: String, onClick: () -> Unit) {
        val overlays = LocalOverlays.current
        val anchor = rememberAnchor()
        val active = AccountBook.isActive(account)
        Box(Modifier.anchor(anchor).combinedClickable(onClick = onClick, onLongClick = {
            if (!active) overlays.menu(anchor.bounds, listOf(listOf(MenuItem(forgetLabel, Icons.Trash, destructive = true) { AccountBook.forget(account) })))
        })) {
            ListRow(
                account.name,
                subtitle = if (active) tr("Active") else null,
                leading = { Avatar(account.avatar, 44) },
                trailing = { if (active) AppIcon(Icons.Check, null, tint = AppTheme.tint, size = 18.dp) },
                chevron = active,
            )
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
        var limit by remember { mutableStateOf<com.atlanyuey.aenht.shared.eh.EhImageLimit?>(null) }
        LaunchedEffect(Unit) { limit = runCatching { EhClient.imageLimit() }.getOrNull() }
        GroupedListScreen(tr("E-Hentai")) {
            section {
                ListRow(tr("Member ID"), value = EhAuth.memberId ?: "-")
                ListRow(tr("ExHentai"), value = if (EhSession.hasExHentai) tr("Available") else tr("Not available"), last = true)
            }
            section("Image Limits", footer = { FooterText(tr("Viewing and downloading pages counts toward this limit; it recovers over time.")) }) {
                val l = limit
                if (l == null) {
                    ListRow(tr("Used"), last = true, trailing = { ActivityIndicator(size = 16.dp) })
                } else {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row {
                            Txt(tr("Used"), Modifier.weight(1f))
                            Txt("${l.used} / ${l.limit}", color = AppTheme.colors.secondaryLabel)
                        }
                        ProgressBar(if (l.limit > 0) l.used.toFloat() / l.limit else 0f)
                        l.resetCost?.let { Txt(tr("Reset Cost: %lld GP", it), style = Type.footnote, color = AppTheme.colors.secondaryLabel) }
                    }
                }
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
            section(footer = { FooterText(tr("The site's own pages, signed in with this account.")) }) {
                ListRow(tr("E-Hentai Settings"), chevron = true, onClick = { navigator.push(EhWebScreen(tr0("https://e-hentai.org/uconfig.php"), "E-Hentai Settings")) })
                ListRow(tr("My Tags"), chevron = true, last = true, onClick = { navigator.push(EhWebScreen(tr0("https://e-hentai.org/mytags"), "My Tags")) })
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
        val targets = listOf("tag", "title", "uploader", "comment", "commenter")
        val targetTitle: @Composable (String) -> String = { t ->
            when (t) {
                "tag" -> tr("Tag")
                "title" -> tr("Title")
                "comment" -> tr("Comment")
                "commenter" -> tr("Commenter")
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
            val blocked = AppSettings.blockedCommenterSet.sorted()
            if (blocked.isNotEmpty()) {
                section("Blocked Commenters", footer = { FooterText(tr("Their comments are hidden on E-Hentai galleries. Long-press a comment to block its author.")) }) {
                    blocked.forEachIndexed { i, name ->
                        ListRow(name, last = i == blocked.lastIndex, trailing = {
                            com.atlanyuey.aenht.ui.components.TextButton(tr("Unblock"), { AppSettings.unblockCommenter(name) })
                        })
                    }
                }
            }
        }
    }
}

// MARK: - Translation

/** Me › Translation: the language models (in order) and Microsoft Translator. */
class TranslationSettingsScreen : Screen() {
    private var msKey by mutableStateOf(TranslationService.microsoftKey ?: "")
    private var msRegion by mutableStateOf(TranslationService.microsoftRegion ?: "")

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.current
        TranslationService.revision
        val services = TranslationService.services
        GroupedListScreen(tr("Translation")) {
            section("Order of Use", footer = {
                FooterText(tr("Tried from top to bottom: a model that refuses, fails or reaches its monthly spending limit hands over to the next one, then to Microsoft Translator, then to the on-device translation."))
            }) {
                services.forEachIndexed { i, s ->
                    ListRow(
                        s.name,
                        leading = { ProviderIcon(s.presetKind) },
                        subtitle = serviceSubtitle(s),
                        titleColor = if (s.enabled) Color.Unspecified else AppTheme.colors.secondaryLabel,
                        onClick = { navigator.push(LlmServiceScreen(s, isNew = false)) },
                        trailing = {
                            if (i > 0) {
                                Box(Modifier.size(30.dp).clickable { TranslationService.move(s.id, up = true) }, contentAlignment = Alignment.Center) {
                                    AppIcon(Icons.ArrowUp, tr("Move Up"), size = 15.dp)
                                }
                            }
                            if (i < services.lastIndex) {
                                Box(Modifier.size(30.dp).clickable { TranslationService.move(s.id, up = false) }, contentAlignment = Alignment.Center) {
                                    AppIcon(Icons.ArrowDown, tr("Move Down"), size = 15.dp)
                                }
                            }
                            GlassToggle(s.enabled, { TranslationService.setEnabled(s.id, it) })
                        },
                    )
                }
                ButtonRow(tr("Add Service"), { navigator.push(LlmPresetScreen()) }, last = true)
            }
            section("Microsoft Translator", footer = { FooterText(tr("Used when no language model is set up or all of them fail. A key from Azure Translator has a free monthly allowance.")) }) {
                FieldRow(tr("API Key"), msKey) { msKey = it }
                FieldRow(tr("Region (optional)"), msRegion) { msRegion = it }
                ButtonRow(tr("Save"), {
                    TranslationService.microsoftKey = msKey
                    TranslationService.microsoftRegion = msRegion
                }, last = true)
            }
            section(footer = { FooterText(tr("The last fallback, free: Google's on-device translation (ML Kit). The first time, it downloads a language model of about 30 MB for each language.")) }) {
                ToggleRow(tr("On-Device Translation"), AppSettings.deviceTranslation, { AppSettings.deviceTranslation = it }, last = true)
            }
        }
    }

    @Composable
    private fun serviceSubtitle(s: LlmService): String {
        val usage = TranslationService.usage(s)
        return when {
            !TranslationService.hasKey(s.id) -> s.model + " · " + tr("No key")
            TranslationService.overLimit(s) -> s.model + " · " + tr("Spending limit reached")
            usage.monthInput + usage.monthOutput > 0 ->
                s.model + " · " + tr("This month") + " " + llmCost(usage.monthCost, s.currencySymbol) + " · " + llmTokens(usage.monthInput + usage.monthOutput)
            else -> s.model
        }
    }
}

/** A language model service's logo (LobeHub Icons, as in AY Translate). */
@Composable
fun ProviderIcon(preset: LlmPreset, size: androidx.compose.ui.unit.Dp = 30.dp) {
    val res = when (preset) {
        LlmPreset.DEEPSEEK -> com.atlanyuey.aenht.R.drawable.provider_deepseek
        LlmPreset.GEMINI -> com.atlanyuey.aenht.R.drawable.provider_gemini
        LlmPreset.OPENAI -> com.atlanyuey.aenht.R.drawable.provider_openai
        LlmPreset.QWEN -> com.atlanyuey.aenht.R.drawable.provider_qwen
        LlmPreset.SILICONFLOW -> com.atlanyuey.aenht.R.drawable.provider_siliconflow
        LlmPreset.MOONSHOT -> com.atlanyuey.aenht.R.drawable.provider_moonshot
        LlmPreset.ZHIPU -> com.atlanyuey.aenht.R.drawable.provider_zhipu
        LlmPreset.OPENROUTER -> com.atlanyuey.aenht.R.drawable.provider_openrouter
        LlmPreset.OTHER -> null
    }
    Box(
        Modifier.size(size).clip(RoundedCornerShape(size * 0.27f)).background(Color.White).padding(size * 0.1f),
        contentAlignment = Alignment.Center,
    ) {
        if (res != null) {
            androidx.compose.foundation.Image(androidx.compose.ui.res.painterResource(res), null, Modifier.fillMaxSize())
        } else {
            AppIcon(Icons.Sliders, null, tint = Color(0xFF007AFF), size = size * 0.6f)
        }
    }
}

internal fun llmCost(value: Double, symbol: String) =
    symbol + String.format(java.util.Locale.ROOT, if (value > 0 && value < 0.01) "%.4f" else "%.2f", value)

@Composable
internal fun llmTokens(count: Long): String = tr("%@ tokens", java.text.NumberFormat.getIntegerInstance().format(count))

/** Add Service: the presets (address and a model filled in). */
class LlmPresetScreen : Screen() {
    @Composable
    override fun Content() {
        val navigator = LocalNavigator.current
        GroupedListScreen(tr("Add Service")) {
            section {
                LlmPreset.entries.forEachIndexed { i, preset ->
                    ListRow(
                        if (preset == LlmPreset.OTHER) tr("Other (OpenAI-compatible)") else preset.displayName,
                        leading = { ProviderIcon(preset) },
                        subtitle = preset.baseUrl,
                        chevron = true,
                        last = i == LlmPreset.entries.lastIndex,
                        onClick = { navigator.push(LlmServiceScreen(LlmService.create(java.util.UUID.randomUUID().toString(), preset), isNew = true)) },
                    )
                }
            }
        }
    }
}

/** A service's settings: address, key, model (from its list), test, prices, monthly limit and usage. */
class LlmServiceScreen(private val original: LlmService, private val isNew: Boolean) : Screen() {
    private var name by mutableStateOf(original.name)
    private var baseUrl by mutableStateOf(original.baseUrl)
    private var key by mutableStateOf(TranslationService.key(original.id))
    private var model by mutableStateOf(original.model)
    private var currency by mutableStateOf(original.currency)
    private var inputPrice by mutableStateOf(original.inputPrice?.toString() ?: "")
    private var outputPrice by mutableStateOf(original.outputPrice?.toString() ?: "")
    private var limitOn by mutableStateOf(original.spendLimit != null)
    private var limit by mutableStateOf((original.spendLimit ?: 1.0).toString())
    private var models by mutableStateOf(emptyList<String>())
    private var loadingModels by mutableStateOf(false)
    private var modelsError by mutableStateOf<String?>(null)
    private var testing by mutableStateOf(false)
    private var testText by mutableStateOf(TranslationService.sampleText)
    private var testResult by mutableStateOf<String?>(null)
    // Request parameters; null: not sent (the service's own default).
    private var temperature by mutableStateOf(original.temperature)
    private var topP by mutableStateOf(original.topP)
    private var maxTokens by mutableStateOf(original.maxTokens)
    private var timeout by mutableStateOf(original.timeoutSeconds)
    private var extraBody by mutableStateOf(original.extraBody)

    private val effectiveKey get() = key.trim()

    private fun draft() = original.with(
        name = name.trim().ifEmpty { model.trim() }, baseUrl = baseUrl.trim(), model = model.trim(), enabled = original.enabled,
        inputPrice = inputPrice.toDoubleOrNull(), outputPrice = outputPrice.toDoubleOrNull(), currency = currency,
        spendLimit = if (limitOn) limit.toDoubleOrNull() else null,
    ).withParameters(temperature, topP, maxTokens, timeout, extraBody.trim())

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.current
        val overlays = LocalOverlays.current
        val errorTitle = tr("Couldn't Connect")
        val extraValid = draft().extraFields() != null
        val canSave = baseUrl.isNotBlank() && model.isNotBlank() && effectiveKey.isNotBlank() && extraValid
        TranslationService.revision
        // The model list loads by itself (again when the address or key changes).
        val listError = tr("Couldn't load the model list: %@")
        LaunchedEffect(baseUrl.trim(), effectiveKey) {
            if (baseUrl.isBlank() || effectiveKey.isBlank()) return@LaunchedEffect
            kotlinx.coroutines.delay(600)
            loadingModels = true
            try {
                models = TranslationService.models(baseUrl.trim(), effectiveKey)
                modelsError = null
                if (model.isBlank()) models.firstOrNull()?.let { model = it }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                models = emptyList()
                modelsError = listError.replace("%@", e.localizedMessage ?: "")
            } finally {
                loadingModels = false
            }
        }
        LaunchedEffect(model) {
            if (original.presetKind != LlmPreset.OPENROUTER || model.isBlank()) return@LaunchedEffect
            TranslationService.publishedPrice(baseUrl, effectiveKey, model)?.let { (i, o) ->
                inputPrice = String.format(java.util.Locale.ROOT, "%.4f", i).trimEnd('0').trimEnd('.')
                outputPrice = String.format(java.util.Locale.ROOT, "%.4f", o).trimEnd('0').trimEnd('.')
            }
        }
        GroupedListScreen(name.ifBlank { original.name }, trailing = {
            com.atlanyuey.aenht.ui.components.TextButton(tr("Save"), {
                TranslationService.store(draft(), key)
                if (isNew) navigator.popTo { it is TranslationSettingsScreen } else navigator.pop()
            }, enabled = canSave, bold = true)
        }) {
            section("Service") {
                FieldRow(tr("Name"), name) { name = it }
                FieldRow(tr("API Address"), baseUrl, url = true) { baseUrl = it }
                // Shown as typed, so it's clear which key this is.
                FieldRow(tr("API Key"), key, last = true) { key = it }
            }
            section("Model", footer = {
                FooterText(modelsError ?: tr("The service's models load by themselves once the address and key are in. The model can also be typed in."))
            }) {
                FieldRow(tr("Model"), model, last = models.isEmpty() && !loadingModels) { model = it }
                if (loadingModels) {
                    ListRow(tr("Loading Models…"), last = true, trailing = { ActivityIndicator(size = 16.dp) })
                } else if (models.isNotEmpty()) {
                    ListRow(tr("Choose from List"), value = "${models.size}", chevron = true, last = true, onClick = {
                        navigator.push(ModelPickerScreen(models, model) { model = it })
                    })
                }
            }
            parametersSection(extraValid)
            section("Test", footer = { testResult?.let { FooterText(it) } }) {
                FieldRow(tr("Text to Translate"), testText) { testText = it }
                ButtonRow(if (testing) tr("Testing…") else tr("Test Translation"), {
                    testing = true
                    scope.launch {
                        try {
                            val r = TranslationService.test(draft(), effectiveKey, testText)
                            testResult = r.text + "\n" + String.format(java.util.Locale.ROOT, "%.1f s", r.millis / 1000.0) +
                                " · " + (r.inputTokens + r.outputTokens) + " tokens"
                        } catch (e: Exception) {
                            testResult = null
                            overlays.alert(errorTitle, e.localizedMessage)
                        }
                        testing = false
                    }
                }, enabled = !testing && canSave && testText.isNotBlank(), last = true)
            }
            section("Cost", footer = {
                FooterText(tr("Prices are per million tokens in the service's billing currency (OpenRouter publishes its own). Past the monthly limit, the next service in the order takes over."))
            }) {
                val symbol = if (currency == "CNY") "¥" else "$"
                if (original.presetKind.choosesCurrency) {
                    PickerRow(tr("Billing Currency"), listOf("USD", "CNY"), currency, label = { if (it == "CNY") tr("Chinese Yuan (¥)") else tr("US Dollar ($)") }, onSelect = { currency = it })
                }
                FieldRow(tr("Input, per 1M tokens") + " ($symbol)", inputPrice) { inputPrice = it.filter { c -> c.isDigit() || c == '.' } }
                FieldRow(tr("Output, per 1M tokens") + " ($symbol)", outputPrice) { outputPrice = it.filter { c -> c.isDigit() || c == '.' } }
                ToggleRow(tr("Monthly Spending Limit"), limitOn, { limitOn = it }, last = !limitOn && isNew)
                if (limitOn) FieldRow(tr("Limit") + " ($symbol)", limit, last = isNew) { limit = it.filter { c -> c.isDigit() || c == '.' } }
                if (!isNew) {
                    val usage = TranslationService.usage(original)
                    ListRow(tr("This Month"), value = llmCost(usage.monthCost, symbol) + " · " + llmTokens(usage.monthInput + usage.monthOutput))
                    ListRow(tr("Total"), value = llmCost(usage.totalCost, symbol) + " · " + llmTokens(usage.totalInput + usage.totalOutput))
                    ButtonRow(tr("Reset Usage Figures"), { TranslationService.resetUsage(original) }, destructive = true, last = true)
                }
            }
            if (!isNew) {
                section {
                    ButtonRow(tr("Delete Service"), {
                        TranslationService.remove(original.id)
                        navigator.pop()
                    }, destructive = true, last = true)
                }
            }
        }
    }

    /** Request parameters, like AY Translate: each one is sent only when on. */
    private fun androidx.compose.foundation.lazy.LazyListScope.parametersSection(extraValid: Boolean) {
        section("Request Parameters", footer = {
            FooterText(tr("Defaults: temperature 0.2, timeout 30 s; Top P and max tokens aren't sent, so the service uses its own. Extra parameters are added to the request as they are."))
        }) {
            ParameterSlider(tr("Temperature"), temperature, 0f..2f, 0.1f, LlmService.DEFAULT_TEMPERATURE) { temperature = it }
            ParameterSlider(tr("Top P"), topP, 0f..1f, 0.05f, 1.0) { topP = it }
            ToggleRow(tr("Max Tokens"), maxTokens != null, { maxTokens = if (it) 1024 else null })
            maxTokens?.let { tokens ->
                FieldRow(tr("Tokens"), "$tokens") { text -> text.filter(Char::isDigit).toIntOrNull()?.let { maxTokens = it.coerceIn(1, 1_000_000) } }
            }
            FieldRow(tr("Timeout") + " (s)", "$timeout") { text -> text.filter(Char::isDigit).toIntOrNull()?.let { timeout = it.coerceIn(1, 600) } }
            FieldRow(tr("Extra Parameters (JSON)"), extraBody, last = extraValid) { extraBody = it }
            if (!extraValid) {
                Txt(tr("Not a JSON object."), Modifier.padding(start = 16.dp, end = 16.dp, bottom = 10.dp), style = Type.footnote, color = AppTheme.colors.red)
            }
        }
    }

    @Composable
    private fun ParameterSlider(title: String, value: Double?, range: ClosedFloatingPointRange<Float>, step: Float, fallback: Double, onChange: (Double?) -> Unit) {
        ToggleRow(title, value != null, { onChange(if (it) fallback else null) },
            subtitle = value?.let { String.format(java.util.Locale.ROOT, "%.2f", it) } ?: tr("Service Default"))
        if (value != null) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
                GlassSlider(value.toFloat(), { v -> onChange((Math.round(v / step) * step).toDouble()) }, range)
            }
            RowSeparator()
        }
    }
}

/** A service's model list, to pick one. */
class ModelPickerScreen(private val models: List<String>, private val selected: String, private val onPick: (String) -> Unit) : Screen() {
    @Composable
    override fun Content() {
        val navigator = LocalNavigator.current
        GroupedListScreen(tr("Model")) {
            section {
                models.forEachIndexed { i, m ->
                    ListRow(m, last = i == models.lastIndex, onClick = { onPick(m); navigator.pop() }, trailing = {
                        if (m == selected) AppIcon(Icons.Check, null, tint = AppTheme.tint, size = 16.dp)
                    })
                }
            }
        }
    }
}

/** Settings › Home: the rows pinned above each site's feed (up to two). */
class HomeSettingsScreen : Screen() {
    @Composable
    override fun Content() {
        GroupedListScreen(tr("Home")) {
            AppSettings.enabledSites.forEach { site ->
                val rows = AppSettings.homeRows(site)
                val options: List<HomeRow?> = listOf<HomeRow?>(null) + HomeRow.of(site)
                val label: @Composable (HomeRow?) -> String = { it?.let { r -> tr(r.title) } ?: tr("None") }
                section(site.label, footer = { FooterText(tr("Rows of covers above the feed, at most two. They load with the feed and on refresh.")) }) {
                    PickerRow(tr("First Row"), options, rows.getOrNull(0), label = label, onSelect = { r ->
                        AppSettings.setHomeRows(site, listOfNotNull(r, rows.getOrNull(1)?.takeIf { it != r }))
                    })
                    if (rows.isNotEmpty()) {
                        PickerRow(tr("Second Row"), options.filter { it != rows[0] }, rows.getOrNull(1), label = label, onSelect = { r ->
                            AppSettings.setHomeRows(site, listOfNotNull(rows[0], r))
                        })
                    }
                }
            }
        }
    }
}

private fun tr0(s: String) = s

/**
 * An E-Hentai page (site settings, My Tags) in a web view, signed in with
 * the account's cookies; ExHentai's when it is in use.
 */
class EhWebScreen(private val url: String, private val titleKey: String) : Screen() {
    private var loading by mutableStateOf(true)

    @SuppressLint("SetJavaScriptEnabled")
    @Composable
    override fun Content() {
        val target = if (exHentaiActive) url.replace("https://e-hentai.org", "https://exhentai.org") else url
        ScreenScaffold(title = tr(titleKey), trailing = { if (loading) ActivityIndicator(size = 18.dp) }) { padding ->
            AndroidView(
                modifier = Modifier.fillMaxSize().padding(padding),
                factory = { context ->
                    WebView(context).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        val cookies = CookieManager.getInstance()
                        cookies.setAcceptCookie(true)
                        for (host in listOf("https://e-hentai.org", "https://exhentai.org", "https://forums.e-hentai.org")) {
                            EhAuth.memberId?.let { cookies.setCookie(host, "ipb_member_id=$it; path=/") }
                            EhAuth.passHash?.let { cookies.setCookie(host, "ipb_pass_hash=$it; path=/") }
                            if (host.contains("exhentai")) EhAuth.igneous?.takeIf { it.isNotEmpty() && it != "mystery" }?.let { cookies.setCookie(host, "igneous=$it; path=/") }
                        }
                        cookies.flush()
                        webViewClient = object : WebViewClient() {
                            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) { loading = true }
                            override fun onPageFinished(view: WebView?, url: String?) { loading = false }
                        }
                        loadUrl(target)
                    }
                },
            )
        }
    }
}
