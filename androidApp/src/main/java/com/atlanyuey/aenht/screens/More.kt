package com.atlanyuey.aenht.screens

import com.atlanyuey.aenht.core.EhSession
import com.atlanyuey.aenht.core.label
import com.atlanyuey.aenht.ui.components.GlassCircleButton
import com.atlanyuey.aenht.shared.site.Site
import com.atlanyuey.aenht.shared.site.GalleryKey
import com.atlanyuey.aenht.core.GalleryItem
import com.atlanyuey.aenht.ui.components.SiteFilterButton
import com.atlanyuey.aenht.ui.components.LocalShowsSiteBadge
import com.atlanyuey.aenht.ui.components.galleryScreen

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.text.format.DateUtils
import android.text.format.Formatter
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.runtime.mutableStateListOf
import com.atlanyuey.aenht.ui.components.glass
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import coil3.SingletonImageLoader
import com.atlanyuey.aenht.BuildConfig
import com.atlanyuey.aenht.R
import com.atlanyuey.aenht.core.AccentChoice
import com.atlanyuey.aenht.core.AppLanguage
import com.atlanyuey.aenht.core.AppSettings
import com.atlanyuey.aenht.core.DownloadRecord
import com.atlanyuey.aenht.core.Downloads
import com.atlanyuey.aenht.core.GalleryListStyle
import com.atlanyuey.aenht.core.L10n
import com.atlanyuey.aenht.core.LanguageFilter
import com.atlanyuey.aenht.core.NHHost
import com.atlanyuey.aenht.core.Net
import com.atlanyuey.aenht.core.SearchSort
import com.atlanyuey.aenht.core.Session
import com.atlanyuey.aenht.core.TagStore
import com.atlanyuey.aenht.core.ThemeMode
import com.atlanyuey.aenht.core.tr
import com.atlanyuey.aenht.ui.components.ActivityIndicator
import com.atlanyuey.aenht.ui.components.AppIcon
import com.atlanyuey.aenht.ui.components.ButtonRole
import com.atlanyuey.aenht.ui.components.ButtonRow
import com.atlanyuey.aenht.ui.components.CoverView
import com.atlanyuey.aenht.ui.components.DialogButton
import com.atlanyuey.aenht.ui.components.EmptyState
import com.atlanyuey.aenht.ui.components.FooterText
import com.atlanyuey.aenht.ui.components.GlassSlider
import com.atlanyuey.aenht.ui.components.GroupedListScreen
import com.atlanyuey.aenht.ui.components.LargeTitle
import com.atlanyuey.aenht.ui.components.ListRow
import com.atlanyuey.aenht.ui.components.LocalNavigator
import com.atlanyuey.aenht.ui.components.LocalOverlays
import com.atlanyuey.aenht.ui.components.MenuItem
import com.atlanyuey.aenht.ui.components.Stepper
import com.atlanyuey.aenht.ui.components.fullLine
import com.atlanyuey.aenht.ui.components.LocalGalleryMenuItems
import com.atlanyuey.aenht.ui.components.LocalCoverBadge
import com.atlanyuey.aenht.ui.components.GalleryGrid
import com.atlanyuey.aenht.core.LibraryStyle
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.foundation.lazy.staggeredgrid.LazyStaggeredGridState
import com.atlanyuey.aenht.ui.components.PickerRow
import com.atlanyuey.aenht.ui.components.RemoteImage
import com.atlanyuey.aenht.ui.components.Screen
import com.atlanyuey.aenht.ui.components.ScreenScaffold
import com.atlanyuey.aenht.ui.components.SegmentedControl
import com.atlanyuey.aenht.ui.components.SettingsIcon
import com.atlanyuey.aenht.ui.components.ToggleRow
import com.atlanyuey.aenht.ui.components.Txt
import com.atlanyuey.aenht.ui.components.anchor
import com.atlanyuey.aenht.ui.components.rememberAnchor
import com.atlanyuey.aenht.ui.components.section
import com.atlanyuey.aenht.ui.theme.AppTheme
import com.atlanyuey.aenht.ui.theme.Icons
import com.atlanyuey.aenht.ui.theme.Type
import com.atlanyuey.aenht.ui.theme.color
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import java.text.DateFormat
import java.util.Date
import java.util.Locale

private val SettingsBlue = Color(0xFF007AFF)

// MARK: - More

class MoreScreen : Screen() {
    private val list = LazyListState()
    /** Wide (Tablet Layout › Me): the right half's own pages, of the entry chosen on the left. */
    private var pane by androidx.compose.runtime.mutableStateOf<com.atlanyuey.aenht.ui.components.Navigator?>(null)
    private var chosen by androidx.compose.runtime.mutableStateOf("overview")

    private fun show(key: String, screen: Screen) {
        pane?.stack?.forEach { it.onRemoved() }
        chosen = key
        pane = com.atlanyuey.aenht.ui.components.Navigator(screen)
    }

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.current
        val configuration = androidx.compose.ui.platform.LocalConfiguration.current
        // A tablet held sideways: Tablet Layout is offered.
        val tabletLandscape = configuration.smallestScreenWidthDp >= 600 && configuration.screenWidthDp > configuration.screenHeightDp
        val wide = AppSettings.tabletMe && com.atlanyuey.aenht.ui.components.isWidePage()
        if (wide) {
            val current = pane ?: com.atlanyuey.aenht.ui.components.Navigator(OverviewScreen { key, screen -> show(key, screen) })
                .also { pane = it }
            androidx.compose.foundation.layout.Row(Modifier.fillMaxSize()) {
                Box(Modifier.width(400.dp).fillMaxHeight()) {
                    MoreList(tabletLandscape, chosen) { key, screen -> show(key, screen) }
                }
                Box(Modifier.width(0.5.dp).fillMaxHeight().background(AppTheme.colors.separator))
                Box(Modifier.weight(1f).fillMaxHeight()) {
                    com.atlanyuey.aenht.ui.components.NavHost(current)
                }
            }
        } else {
            // Turned upright on the Tablet Layout page: the entry is gone, back to the overview next time.
            if (!tabletLandscape && chosen == "tablet") chosen = "overview"
            MoreList(tabletLandscape, null) { _, screen -> navigator.push(screen) }
        }
    }

    /** The Me page's entries; [open] pushes a page, or (wide) shows it on the right. */
    @Composable
    private fun MoreList(tabletLandscape: Boolean, selected: String?, open: (String, Screen) -> Unit) {
        GroupedListScreen(tr("Me"), list, largeTitle = true) {
            section {
                // Both sites' accounts are one row; the next screen has one section per site.
                val nhUser = Session.user.takeIf { AppSettings.nhentaiEnabled && Session.isLoggedIn }
                ListRow(
                    tr("Accounts"),
                    subtitle = accountsSummary(),
                    leading = {
                        Avatar(nhUser?.avatar, 56,
                            monogram = if (nhUser == null && AppSettings.ehentaiEnabled && EhSession.isLoggedIn) "EH" else null)
                    },
                    chevron = true,
                    last = true,
                    minHeight = 76.dp,
                    titleColor = if (selected == "accounts") AppTheme.tint else Color.Unspecified,
                    onClick = { open("accounts", AccountsScreen()) },
                )
            }
            section {
                val active = Downloads.records.count { it.state == DownloadRecord.DOWNLOADING }
                ListRow(
                    tr("Downloads"),
                    leading = { SettingsIcon(Icons.DownloadCircle, Color(0xFF34C759)) },
                    value = if (active > 0) "$active" else null,
                    chevron = true, last = !tabletLandscape,
                    titleColor = if (selected == "downloads") AppTheme.tint else Color.Unspecified,
                    onClick = { open("downloads", DownloadsScreen()) },
                )
                if (tabletLandscape) {
                    SettingsNav(tr("Tablet Layout"), Icons.Layers, Color(0xFF5856D6), last = true, key = "tablet",
                        selected = selected, open = open) { TabletLayoutScreen() }
                }
            }
            section {
                SettingsNav(tr("Home"), Icons.Home, Color(0xFF007AFF), key = "home", selected = selected, open = open) { HomeSettingsScreen() }
                SettingsNav(tr("Sites"), Icons.Layers, Color(0xFFFF2D55), key = "sites", selected = selected, open = open) { SitesScreen() }
                SettingsNav(tr("Filters"), Icons.EyeOff, Color(0xFF8E8E93), key = "filters", selected = selected, open = open) { FiltersScreen() }
                SettingsNav(tr("Translation"), Icons.Language, Color(0xFF34C759), key = "translation", selected = selected, open = open) { TranslationSettingsScreen() }
                SettingsNav(tr("Appearance"), Icons.Palette, SettingsBlue, key = "appearance", selected = selected, open = open) { AppearanceScreen() }
                SettingsNav(tr("Reading"), Icons.Book, Color(0xFFFF9500), key = "reading", selected = selected, open = open) { ReadingScreen() }
                SettingsNav(tr("General"), Icons.Gear, Color(0xFF8E8E93), key = "general", selected = selected, open = open) { GeneralScreen() }
                SettingsNav(tr("WebDAV Sync"), Icons.Cloud, Color(0xFF5AC8FA), key = "webdav", selected = selected, open = open) { WebDavScreen() }
                SettingsNav(tr("Settings Backup"), Icons.CloudUpload, Color(0xFF32ADE6), last = true, key = "backup",
                    selected = selected, open = open) { BackupScreen() }
            }
            section {
                // Two columns: the overview the right half starts with.
                if (selected != null) {
                    SettingsNav(tr("All"), Icons.Info, Color(0xFF8E8E93), key = "overview", selected = selected, open = open) {
                        OverviewScreen(open)
                    }
                }
                SettingsNav(tr("Language"), Icons.Globe, Color(0xFF30B0C7), key = "language", selected = selected, open = open) { LanguageScreen() }
                SettingsNav(tr("About"), Icons.Info, Color(0xFF5856D6), last = true, key = "about", selected = selected, open = open) { AboutScreen() }
            }
        }
    }
}

/**
 * Me › Tablet Layout (a tablet held sideways): which screens use the room
 * for two halves side by side. The same settings as the iOS app's.
 */
class TabletLayoutScreen : Screen() {
    @Composable
    override fun Content() {
        GroupedListScreen(tr("Tablet Layout")) {
            section(footer = { FooterText(tr("The two pinned rows side by side.")) }) {
                ToggleRow(tr("Tablet Layout on Home"), AppSettings.tabletHome, { AppSettings.tabletHome = it }, last = true)
            }
            val pages = AppSettings.tabletDetailContent == com.atlanyuey.aenht.core.TabletDetailContent.Pages
            section(footer = {
                FooterText(tr(when {
                    !AppSettings.tabletDetail -> "Gallery pages show as on a phone: one column."
                    pages -> "The details on the left, every page on the right. Three Columns keeps the thumbnails' size and leaves more room to the details."
                    else -> "The details and the pages on the left; the other versions, similar galleries and comments on the right."
                }))
            }) {
                ToggleRow(tr("Tablet Layout for Gallery Pages"), AppSettings.tabletDetail, { AppSettings.tabletDetail = it },
                    last = !AppSettings.tabletDetail)
                if (AppSettings.tabletDetail) {
                    PickerRow(tr("Right Half"), com.atlanyuey.aenht.core.TabletDetailContent.entries, AppSettings.tabletDetailContent,
                        label = { tr(it.title) }, onSelect = { AppSettings.tabletDetailContent = it }, last = !pages)
                    // The other versions, similar galleries and comments need the room.
                    if (pages) {
                        PickerRow(tr("Pages Width"), listOf(false, true), AppSettings.tabletNarrowPages,
                            label = { if (it) tr("Three Columns") else tr("Half the Page") },
                            onSelect = { AppSettings.tabletNarrowPages = it }, last = true)
                    }
                }
            }
            section(footer = { FooterText(tr("The list on the left, the chosen page on the right.")) }) {
                ToggleRow(tr("Tablet Layout for Me"), AppSettings.tabletMe, { AppSettings.tabletMe = it }, last = true)
            }
        }
    }
}

/** The right half of a two-column Me page before an entry is chosen: how things stand, each part opening its page. */
class OverviewScreen(private val open: (String, Screen) -> Unit) : Screen() {
    @Composable
    override fun Content() {
        GroupedListScreen(tr("All")) {
            section("Accounts") {
                val nh = AppSettings.nhentaiEnabled && Session.isLoggedIn
                val eh = AppSettings.ehentaiEnabled && EhSession.isLoggedIn
                if (nh) {
                    ListRow(Session.user?.username ?: "nhentai", subtitle = "nhentai", leading = { Avatar(Session.user?.avatar, 40) },
                        chevron = true, last = !eh, onClick = { open("accounts", AccountsScreen()) })
                }
                if (eh) {
                    ListRow(if (EhSession.hasExHentai) "E-Hentai · ExHentai" else "E-Hentai", subtitle = tr("Logged In"),
                        leading = { Avatar(null, 40, monogram = "EH") }, chevron = true, last = true,
                        onClick = { open("accounts", AccountsScreen()) })
                }
                if (!nh && !eh) {
                    ListRow(tr("Not logged in"), leading = { Avatar(null, 40) }, chevron = true, last = true,
                        onClick = { open("accounts", AccountsScreen()) })
                }
            }
            section("Downloads") {
                ListRow(tr("Downloading"), value = "${Downloads.records.count { it.state != DownloadRecord.FINISHED }}",
                    chevron = true, onClick = { open("downloads", DownloadsScreen()) })
                ListRow(tr("Downloaded"), value = "${Downloads.records.count { it.state == DownloadRecord.FINISHED }}",
                    chevron = true, last = true, onClick = { open("downloads", DownloadsScreen()) })
            }
            section {
                ListRow(tr("History"), value = "${com.atlanyuey.aenht.core.HistoryStore.entries.size}")
                ListRow(tr("Favorites"), value = "${com.atlanyuey.aenht.core.FavoritesStore.records.size}", last = true)
            }
            section {
                val webDav = com.atlanyuey.aenht.core.WebDavService
                ListRow(tr("WebDAV Sync"), value = when {
                    !webDav.isConfigured -> tr("Not set up")
                    webDav.error != null -> webDav.error
                    webDav.lastSync == null -> tr("Not synced yet")
                    else -> java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT)
                        .format(java.util.Date(webDav.lastSync!!))
                }, chevron = true, last = true, onClick = { open("webdav", WebDavScreen()) })
            }
            section {
                ListRow(tr("Version"), value = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})", chevron = true, last = true,
                    onClick = { open("about", AboutScreen()) })
            }
        }
    }
}

@Composable
private fun SettingsNav(
    title: String, icon: ImageVector, color: Color, last: Boolean = false,
    key: String? = null, selected: String? = null, open: ((String, Screen) -> Unit)? = null, screen: () -> Screen,
) {
    val navigator = LocalNavigator.current
    ListRow(title, leading = { SettingsIcon(icon, color) }, chevron = true, last = last,
        titleColor = if (key != null && key == selected) AppTheme.tint else Color.Unspecified,
        onClick = { if (open != null && key != null) open(key, screen()) else navigator.push(screen()) })
}

@Composable
fun Avatar(url: String?, size: Int, monogram: String? = null) {
    Box(Modifier.size(size.dp).clip(CircleShape).background(AppTheme.colors.quaternaryFill), contentAlignment = Alignment.Center) {
        if (url != null) {
            RemoteImage(url, Modifier.fillMaxSize(), targetWidth = (size * 2).dp)
        } else if (monogram != null) {
            // Letters instead of a picture (E-Hentai accounts have none): an account, not a missing one.
            Box(
                Modifier.fillMaxSize().background(
                    androidx.compose.ui.graphics.Brush.linearGradient(
                        listOf(androidx.compose.ui.graphics.Color(0xFF9E405C), androidx.compose.ui.graphics.Color(0xFF5C296B)),
                    ),
                ),
                contentAlignment = Alignment.Center,
            ) {
                Txt(
                    monogram,
                    style = androidx.compose.ui.text.TextStyle(
                        fontSize = (size * 0.38f).sp,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                    ),
                    color = androidx.compose.ui.graphics.Color.White,
                    maxLines = 1,
                )
            }
        } else {
            AppIcon(Icons.PersonCircle, null, tint = AppTheme.colors.secondaryLabel, size = size.dp)
        }
    }
}

// MARK: - Account

class AccountScreen : Screen() {
    @Composable
    override fun Content() {
        val navigator = LocalNavigator.current
        val overlays = LocalOverlays.current
        val context = LocalContext.current
        val logoutTitle = tr("Log out of nhentai?")
        val logout = tr("Log Out")
        LaunchedEffect(Unit) { Session.reload() }
        GroupedListScreen(tr("Account")) {
            item {
                Column(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Avatar(Session.user?.avatar, 96)
                    Txt(Session.user?.username ?: tr("Logged In"), style = Type.title2)
                    Txt(tr("nhentai account"), style = Type.subheadline, color = AppTheme.colors.secondaryLabel)
                }
            }
            Session.user?.let { user ->
                section {
                    ListRow(tr("User ID"), value = "${user.id}")
                    ListRow(tr("Profile"), value = "nhentai.net", chevron = true, last = true, onClick = {
                        com.atlanyuey.aenht.core.startSafely(context, Intent(Intent.ACTION_VIEW, Uri.parse("${NHHost.SITE}/users/${user.id}/")))
                    })
                }
            }
            section {
                val anchor = rememberAnchor()
                ButtonRow(logout, {
                    overlays.confirm(anchor.bounds, logoutTitle, logout) {
                        Session.logout()
                        navigator.pop()
                    }
                }, destructive = true, last = true, modifier = Modifier.anchor(anchor))
            }
        }
    }
}

// MARK: - Login

/**
 * Signs in on nhentai.net in a web view (the site handles its challenge and
 * proof of work); the API tokens are read from the cookies it sets.
 */
class LoginScreen : Screen() {
    /** A web page: the whole height (the tab bar covered its bottom, Cloudflare's checkbox there). */
    override val hidesTabBar: Boolean get() = true

    private var loading by mutableStateOf(true)
    private var done = false

    @SuppressLint("SetJavaScriptEnabled")
    @Composable
    override fun Content() {
        val navigator = LocalNavigator.current
        val overlays = LocalOverlays.current
        val failedTitle = tr("Couldn't Log In")
        val failedMessage = tr("The login couldn't be saved on this device.")
        ScreenScaffold(
            title = tr("Log In"),
            trailing = { if (loading) ActivityIndicator() },
            // A WebView: not drawn into the bar's glass (it flickered there).
            contentUnderGlass = false,
        ) { padding ->
            AndroidView(
                // Gone with the page (a WebView left alive keeps its memory and page running).
                onRelease = { web -> web.stopLoading(); web.destroy() },
                modifier = Modifier.fillMaxSize().padding(padding),
                factory = { context ->
                    WebView(context).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        CookieManager.getInstance().setAcceptCookie(true)
                        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                        webViewClient = object : WebViewClient() {
                            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                                if (com.atlanyuey.aenht.BuildConfig.DEBUG) android.util.Log.d("AENHT-WEB", "nhentai login page: ${url?.substringBefore('?')}")
                                loading = true
                                check()
                            }

                            override fun onPageFinished(view: WebView?, url: String?) {
                                loading = false
                                check()
                            }

                            private fun check() {
                                if (done) return
                                val cookies = CookieManager.getInstance().getCookie(NHHost.SITE) ?: return
                                val map = cookies.split(";").mapNotNull {
                                    val parts = it.trim().split("=", limit = 2)
                                    if (parts.size == 2) parts[0] to Uri.decode(parts[1]) else null
                                }.toMap()
                                val access = map["access_token"]?.takeIf { it.isNotEmpty() } ?: return
                                done = true
                                scope.launch {
                                    if (!Session.login(access, map["refresh_token"])) {
                                        overlays.alert(failedTitle, failedMessage)
                                    }
                                    navigator.pop()
                                }
                            }
                        }
                        // The page asks for an account instead of picking up the login there was.
                        com.atlanyuey.aenht.core.WebLogins.forget(com.atlanyuey.aenht.core.WebLogins.NHENTAI)
                        loadUrl("${NHHost.SITE}/login/")
                    }
                },
            )
        }
    }
}

// MARK: - Downloads

/**
 * Galleries saved for offline reading: cards like Favorites, or rows with
 * their progress. A gallery opens on its page (offline too); Read there,
 * or in its menu, opens the pages.
 */
class DownloadsScreen : Screen() {
    private val grid = LazyStaggeredGridState()
    private var site by mutableStateOf(AppSettings.site(AppSettings.downloadsSite))
    /** List style: choosing several downloads (storage keys). */
    private var selecting by mutableStateOf(false)
    private val selected = mutableStateListOf<String>()

    @Composable
    override fun Content() {
        val context = LocalContext.current
        var usage by remember { mutableStateOf(0L) }
        val all = Downloads.records
        val records = all.filter { r -> site == null || r.site == site?.key }
        LaunchedEffect(all.size) { usage = withContext(Dispatchers.IO) { Downloads.diskUsage() } }
        val menu = downloadMenu()
        ScreenScaffold(
            title = tr("Downloads"),
            trailing = {
                if (usage > 0) Txt(Formatter.formatShortFileSize(context, usage), style = Type.caption1, color = AppTheme.colors.secondaryLabel)
                if (AppSettings.libraryStyle != LibraryStyle.Cards && records.isNotEmpty()) {
                    com.atlanyuey.aenht.ui.components.TextButton(if (selecting) tr("Done") else tr("Select"), {
                        selecting = !selecting
                        selected.clear()
                    })
                }
                SiteFilterButton(site, { site = it })
                val nav = LocalNavigator.current
                GlassCircleButton(Icons.Gear, tr("Download Settings"), { nav.push(DownloadSettingsScreen()) })
            },
            overlay = {
                if (selecting && AppSettings.libraryStyle != LibraryStyle.Cards) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) { SelectionBar(records) }
                }
            },
        ) { padding ->
            if (AppSettings.libraryStyle == LibraryStyle.Cards) {
                CompositionLocalProvider(
                    LocalCoverBadge provides { item -> DownloadBadge(item.key) },
                    LocalGalleryMenuItems provides { item -> menu(item.key) },
                    LocalShowsSiteBadge provides (site == null && AppSettings.enabledSites.size > 1),
                ) {
                    GalleryGrid(
                        records.map { it.summary }, grid, padding,
                        header = {
                            if (records.any { it.errorKind == "unsupported_avif" }) fullLine("format-warning") {
                                FooterText(tr("This Android version can't decode AVIF images. These pages were not marked as downloaded."))
                            }
                            // The same space under the bar as Favorites and History.
                            if (records.isEmpty()) fullLine("empty") { NoDownloads() }
                            else fullLine("top") { androidx.compose.foundation.layout.Spacer(Modifier.height(8.dp)) }
                        },
                    )
                }
            } else {
                Rows(padding, records, menu)
            }
        }
    }

    @Composable
    private fun SelectionBar(records: List<DownloadRecord>) {
        val chosen = records.filter { it.key.storageKey in selected }
        val running = chosen.filter { it.state == DownloadRecord.DOWNLOADING || it.state == DownloadRecord.QUEUED }
        val stopped = chosen.filter { it.state == DownloadRecord.PAUSED || it.state == DownloadRecord.FAILED }
        Row(
            Modifier
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .fillMaxWidth()
                .glass(RoundedCornerShape(26.dp))
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            com.atlanyuey.aenht.ui.components.TextButton(tr("Select All"), {
                selected.clear(); selected.addAll(records.map { it.key.storageKey })
            })
            Spacer(Modifier.weight(1f))
            com.atlanyuey.aenht.ui.components.TextButton(tr("Pause"), { running.forEach { Downloads.pause(it.key) } }, enabled = running.isNotEmpty())
            com.atlanyuey.aenht.ui.components.TextButton(tr("Resume"), { stopped.forEach { Downloads.resume(it.key) } }, enabled = stopped.isNotEmpty())
            com.atlanyuey.aenht.ui.components.TextButton(tr("Delete"), {
                chosen.forEach { Downloads.delete(it.key) }
                selected.clear()
            }, enabled = chosen.isNotEmpty(), bold = true)
        }
    }

    @Composable
    private fun NoDownloads() {
        EmptyState(Icons.DownloadCircle, tr("No Downloads"), tr("Download a gallery from its page to read it offline."))
    }

    /** Read, pause / resume and delete for a download. */
    @Composable
    private fun downloadMenu(): (GalleryKey) -> List<MenuItem> {
        val navigator = LocalNavigator.current
        val read = tr("Read")
        val pause = tr("Pause")
        val resume = tr("Resume")
        val retry = tr("Retry")
        val delete = tr("Delete")
        val exportCbz = tr("Share as CBZ")
        val saveCbz = tr("Save CBZ to Downloads")
        val saved = tr("Saved to Downloads › AENHT.")
        val failed = tr("Couldn't Save")
        val context = LocalContext.current
        val overlays = LocalOverlays.current
        val scope = com.atlanyuey.aenht.core.rememberSafeScope()
        return { key ->
            val record = Downloads.record(key)
            buildList {
                if (record != null && record.completed > 0) {
                    add(MenuItem(read, Icons.Book) { navigator.push(ReaderScreen(ReaderSource.from(record), 0)) })
                }
                when (record?.state) {
                    DownloadRecord.DOWNLOADING, DownloadRecord.QUEUED -> add(MenuItem(pause, Icons.Pause) { Downloads.pause(key) })
                    DownloadRecord.PAUSED -> add(MenuItem(resume, Icons.Refresh) { Downloads.resume(key) })
                    DownloadRecord.FAILED -> add(MenuItem(retry, Icons.Refresh) { Downloads.resume(key) })
                    else -> {}
                }
                if (record?.state == DownloadRecord.FINISHED) {
                    add(MenuItem(exportCbz, Icons.Share) {
                        scope.launch {
                            val file = Downloads.exportCbz(context, key) ?: return@launch
                            val uri = androidx.core.content.FileProvider.getUriForFile(context, context.packageName + ".files", file)
                            val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                                type = "application/vnd.comicbook+zip"
                                putExtra(android.content.Intent.EXTRA_STREAM, uri)
                                addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            com.atlanyuey.aenht.core.startSafely(context, android.content.Intent.createChooser(send, null))
                        }
                    })
                    if (android.os.Build.VERSION.SDK_INT >= 29) {
                        add(MenuItem(saveCbz, Icons.Download) {
                            scope.launch {
                                val ok = runCatching { Downloads.exportCbz(context, key)?.let { Downloads.saveToDownloads(context, it) } == true }.getOrDefault(false)
                                overlays.alert(if (ok) saveCbz else failed, if (ok) saved else null)
                            }
                        })
                    }
                }
                add(MenuItem(delete, Icons.Trash, destructive = true) { Downloads.delete(key) })
            }
        }
    }

    @OptIn(ExperimentalFoundationApi::class)
    @Composable
    private fun Rows(padding: PaddingValues, records: List<DownloadRecord>, menu: (GalleryKey) -> List<MenuItem>) {
        val navigator = LocalNavigator.current
        val overlays = LocalOverlays.current
        val showSite = site == null && AppSettings.enabledSites.size > 1
        val groups = listOf(
            tr("Downloading") to records.filter { it.state != DownloadRecord.FINISHED },
            tr("Downloaded") to records.filter { it.state == DownloadRecord.FINISHED },
        ).filter { it.second.isNotEmpty() }
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                top = padding.calculateTopPadding(),
                bottom = padding.calculateBottomPadding() + if (selecting) 72.dp else 0.dp,
            ),
        ) {
            if (records.isEmpty()) {
                item { NoDownloads() }
            }
            if (records.any { it.errorKind == "unsupported_avif" }) {
                item(key = "format-warning") {
                    FooterText(tr("This Android version can't decode AVIF images. These pages were not marked as downloaded."))
                }
            }
            groups.forEach { (title, group) ->
            item(key = "header-$title") {
                Txt(
                    title,
                    Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 18.dp, bottom = 4.dp),
                    style = Type.subheadline.copy(fontWeight = FontWeight.SemiBold),
                    color = AppTheme.colors.secondaryLabel,
                )
            }
            items(group.distinctBy { it.key.storageKey }, key = { it.key.storageKey }) { record ->
                val anchor = rememberAnchor()
                val storageKey = record.key.storageKey
                SwipeToDelete(onDelete = { Downloads.delete(record.key) }) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .background(AppTheme.colors.background)
                            .anchor(anchor)
                            .combinedClickable(
                                onClick = {
                                    if (selecting) {
                                        if (storageKey in selected) selected.remove(storageKey) else selected.add(storageKey)
                                    } else {
                                        navigator.push(galleryScreen(record.summary))
                                    }
                                },
                                onLongClick = { if (!selecting) overlays.menu(anchor.bounds, listOf(menu(record.key))) },
                            )
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (selecting) {
                            val on = storageKey in selected
                            Box(
                                Modifier.size(24.dp).clip(CircleShape)
                                    .background(if (on) AppTheme.tint else Color.Transparent)
                                    .border(1.5.dp, if (on) AppTheme.tint else AppTheme.colors.tertiaryLabel, CircleShape),
                                contentAlignment = Alignment.Center,
                            ) {
                                if (on) AppIcon(Icons.CheckCircle, null, tint = Color.White, size = 22.dp)
                            }
                        }
                        CompositionLocalProvider(LocalShowsSiteBadge provides showSite) {
                            CoverView(record.summary, Modifier.width(72.dp), aspectRatio = 0.72f, cornerRadius = 10.dp, targetWidth = 200.dp)
                        }
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Txt(GalleryItem.pretty(record.title), style = Type.subheadline.copy(fontWeight = FontWeight.SemiBold), maxLines = 2)
                            if (record.state == DownloadRecord.FINISHED) {
                                Txt(tr("%lld pages", record.totalPages), style = Type.footnote, color = AppTheme.colors.secondaryLabel)
                            } else {
                                ProgressBar(record.progress)
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Txt("${record.completed} / ${record.totalPages}", Modifier.weight(1f), style = Type.footnote, color = AppTheme.colors.secondaryLabel)
                                    val (label, action) = when (record.state) {
                                        DownloadRecord.DOWNLOADING, DownloadRecord.QUEUED -> tr("Pause") to { Downloads.pause(record.key) }
                                        DownloadRecord.PAUSED -> tr("Resume") to { Downloads.resume(record.key) }
                                        else -> tr("Retry") to { Downloads.resume(record.key) }
                                    }
                                    com.atlanyuey.aenht.ui.components.TextButton(label, action)
                                }
                            }
                        }
                    }
                }
            }
            }
        }
    }
}

/** A download's progress over its cover until it has finished. */
@Composable
private fun androidx.compose.foundation.layout.BoxScope.DownloadBadge(key: GalleryKey) {
    val record = Downloads.record(key) ?: return
    if (record.state == DownloadRecord.FINISHED) return
    val icon = when (record.state) {
        DownloadRecord.DOWNLOADING -> Icons.ArrowDown
        DownloadRecord.QUEUED -> Icons.Hourglass
        DownloadRecord.PAUSED -> Icons.Pause
        else -> Icons.Warning
    }
    Box(
        Modifier
            .align(Alignment.Center)
            .padding(6.dp)
            .size(34.dp)
            .clip(CircleShape)
            .background(Color.Black.copy(alpha = 0.55f)),
        contentAlignment = Alignment.Center,
    ) {
        androidx.compose.foundation.Canvas(Modifier.fillMaxSize().padding(5.dp)) {
            val stroke = 3.dp.toPx()
            val inset = stroke / 2
            val arcSize = androidx.compose.ui.geometry.Size(size.width - stroke, size.height - stroke)
            val topLeft = androidx.compose.ui.geometry.Offset(inset, inset)
            drawArc(Color.White.copy(alpha = 0.3f), 0f, 360f, false, topLeft, arcSize, style = androidx.compose.ui.graphics.drawscope.Stroke(stroke))
            drawArc(
                Color.White, -90f, 360f * record.progress.coerceIn(0f, 1f), false, topLeft, arcSize,
                style = androidx.compose.ui.graphics.drawscope.Stroke(stroke, cap = androidx.compose.ui.graphics.StrokeCap.Round),
            )
        }
        AppIcon(icon, null, tint = Color.White, size = 12.dp)
    }
}

@Composable
internal fun ProgressBar(progress: Float) {
    Box(Modifier.fillMaxWidth().padding(vertical = 2.dp).height(4.dp).clip(RoundedCornerShape(2.dp)).background(AppTheme.colors.tertiaryFill)) {
        Box(Modifier.fillMaxWidth(progress.coerceIn(0f, 1f)).fillMaxHeight().background(AppTheme.tint))
    }
}

// MARK: - Appearance

class AppearanceScreen : Screen() {
    @Composable
    override fun Content() {
        val navigator = LocalNavigator.current
        val large = LocalConfiguration.current.smallestScreenWidthDp >= 600
        GroupedListScreen(tr("Appearance")) {
            section(header = "Theme") {
                Box(Modifier.padding(16.dp)) {
                    SegmentedControl(ThemeMode.entries, AppSettings.theme, { tr(it.title) }, { AppSettings.theme = it }, Modifier.fillMaxWidth())
                }
                AccentPicker()
            }
            section(header = "Gallery List", footer = { FooterText(tr("Card size applies to the grid and waterfall styles.")) }) {
                PickerRow(tr("List Style"), GalleryListStyle.entries, AppSettings.listStyle, { tr(it.title) }, { AppSettings.listStyle = it })
                PickerRow(
                    tr("History and Downloads"),
                    com.atlanyuey.aenht.core.LibraryStyle.entries,
                    AppSettings.libraryStyle,
                    { tr(it.title) },
                    { AppSettings.libraryStyle = it },
                )
                ListRow(
                    tr("Card Size"),
                    value = "${AppSettings.columns(large, false)} / ${AppSettings.columns(large, true)}",
                    chevron = true,
                    onClick = { navigator.push(CardSizeScreen()) },
                )
                ToggleRow(tr("Blur Covers"), AppSettings.blurCovers, { AppSettings.blurCovers = it }, last = true)
            }
            section(footer = { FooterText(tr("Scrolling down a list slides the tab bar away; scrolling up brings it back.")) }) {
                ToggleRow(tr("Hide Tab Bar When Scrolling"), AppSettings.hideBarsOnScroll, { AppSettings.hideBarsOnScroll = it }, last = true)
            }
            if (large) {
                section(footer = { FooterText(tr("The tab bar moves to the side of the screen you touch: Fast at the first touch, Medium after two touches on that side, Slow after four.")) }) {
                    ToggleRow(tr("Tab Bar Follows Your Hand"), AppSettings.followHand, { AppSettings.followHand = it })
                    if (AppSettings.followHand) {
                        PickerRow(
                            tr("Follow Speed"),
                            com.atlanyuey.aenht.core.FollowSpeed.entries,
                            AppSettings.followSpeed,
                            { tr(it.title) },
                            { AppSettings.followSpeed = it },
                            last = true,
                        )
                    } else {
                        PickerRow(
                            tr("Tab Bar Position"),
                            com.atlanyuey.aenht.core.TabBarPosition.entries,
                            AppSettings.tabBarPosition,
                            { tr(it.title) },
                            { AppSettings.tabBarPosition = it },
                            last = true,
                        )
                    }
                }
            }
            section(
                header = "Tags",
                footer = {
                    FooterText(tr("%lld tags, %lld translations (EhTagTranslation). Updated automatically every week.", TagStore.tagCount, TagStore.translationCount))
                },
            ) {
                ToggleRow(tr("Show Tags on Cards"), AppSettings.showTags, { AppSettings.showTags = it })
                ToggleRow(tr("Translate Tags to Chinese"), AppSettings.translateTags, { AppSettings.translateTags = it })
                ListRow(
                    tr("Update Tag Database"),
                    titleColor = if (TagStore.isUpdating) AppTheme.colors.tertiaryLabel else AppTheme.tint,
                    last = true,
                    onClick = if (TagStore.isUpdating) null else ({ scope.launch { TagStore.update() } }),
                ) {
                    if (TagStore.isUpdating) {
                        ActivityIndicator()
                    } else {
                        TagStore.lastUpdated?.let {
                            Txt(
                                DateUtils.getRelativeTimeSpanString(it, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString(),
                                color = AppTheme.colors.secondaryLabel,
                            )
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun AccentPicker() {
        val dark = AppTheme.isDark
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            AccentChoice.entries.forEach { choice ->
                val color = choice.color(dark)
                val selected = choice == AppSettings.accent
                Box(
                    Modifier
                        .size(36.dp)
                        .border(2.dp, if (selected) color else Color.Transparent, CircleShape)
                        .padding(3.dp)
                        .clip(CircleShape)
                        .background(color)
                        .clickable { AppSettings.accent = choice },
                    contentAlignment = Alignment.Center,
                ) {
                    if (selected) AppIcon(Icons.Check, null, tint = Color.White, size = 14.dp)
                }
            }
        }
    }
}

class CardSizeScreen : Screen() {
    @Composable
    override fun Content() {
        GroupedListScreen(tr("Card Size")) {
            section(header = "Portrait") { ColumnsEditor(false) }
            section(
                header = "Landscape",
                footer = {
                    FooterText(tr("Choose how many columns of cards to show. Phones start with 2 in portrait and 4 in landscape, tablets with 3 and 5. A narrow window (Split View) may show fewer."))
                },
            ) { ColumnsEditor(true) }
        }
    }

    /** The columns of one orientation on this kind of device: a preview, the count and a stepper. */
    @Composable
    private fun ColumnsEditor(landscape: Boolean) {
        val tablet = LocalConfiguration.current.smallestScreenWidthDp >= 600
        val columns = AppSettings.columns(tablet, landscape)
        val range = if (landscape) AppSettings.LANDSCAPE_COLUMNS else AppSettings.PORTRAIT_COLUMNS
        Row(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            MiniScreen(columns, landscape, AppSettings.listStyle != GalleryListStyle.Grid)
            Column(Modifier.weight(1f)) {
                Txt("$columns", style = Type.largeTitle)
                Txt(tr("columns"), style = Type.subheadline, color = AppTheme.colors.secondaryLabel)
            }
            Stepper(columns, range) { value ->
                when {
                    tablet && landscape -> AppSettings.padColumnsLandscape = value
                    tablet -> AppSettings.padColumnsPortrait = value
                    landscape -> AppSettings.phoneColumnsLandscape = value
                    else -> AppSettings.phoneColumnsPortrait = value
                }
            }
        }
    }

    @Composable
    private fun MiniScreen(columns: Int, landscape: Boolean, waterfall: Boolean) {
        val colors = AppTheme.colors
        val tint = AppTheme.tint
        Canvas(Modifier.size(if (landscape) 132.dp else 70.dp, if (landscape) 66.dp else 140.dp)) {
            drawRoundRect(colors.background, cornerRadius = CornerRadius(10.dp.toPx()))
            drawRoundRect(colors.separator, cornerRadius = CornerRadius(10.dp.toPx()), style = Stroke(1.dp.toPx()))
            val pad = 5.dp.toPx()
            val gap = 3.dp.toPx()
            val n = maxOf(1, columns)
            val width = (size.width - pad * 2 - gap * (n - 1)) / n
            val ratios = floatArrayOf(1.4f, 1.15f, 1.55f, 1.25f, 1.05f, 1.45f)
            for (column in 0 until n) {
                var y = pad + 6.dp.toPx()
                var index = column
                while (y < size.height - pad) {
                    val height = width * if (waterfall) ratios[index % ratios.size] else 1.33f
                    val visible = minOf(height, size.height - pad - y)
                    drawRoundRect(
                        tint.copy(alpha = 0.25f + 0.1f * (index % 3)),
                        topLeft = Offset(pad + column * (width + gap), y),
                        size = Size(width, visible),
                        cornerRadius = CornerRadius(2.dp.toPx()),
                    )
                    y += height + gap
                    index += n
                }
            }
        }
    }
}

// MARK: - Reading / General

class ReadingScreen : Screen() {
    @Composable
    override fun Content() {
        GroupedListScreen(tr("Reading")) { readingSettings() }
    }
}

class GeneralScreen : Screen() {
    @Composable
    override fun Content() {
        val context = LocalContext.current
        val overlays = LocalOverlays.current
        var cache by remember { mutableStateOf<Long?>(null) }
        val refresh: suspend () -> Unit = {
            cache = withContext(Dispatchers.IO) { SingletonImageLoader.get(context).diskCache?.size ?: 0L }
        }
        LaunchedEffect(Unit) { refresh() }
        val clearTitle = tr("Clear the image cache?")
        val clearLabel = tr("Clear Image Cache")
        GroupedListScreen(tr("General")) {
            section(header = "Home") {
                PickerRow(tr("Language"), LanguageFilter.entries, AppSettings.homeLanguage, { tr(it.title) }, { AppSettings.homeLanguage = it })
                PickerRow(tr("Sort"), SearchSort.entries, AppSettings.homeSort, { tr(it.title) }, { AppSettings.homeSort = it }, last = true)
            }
            section(
                header = "Search",
                footer = { FooterText(tr("Recent searches show when you start a search. When this is off, new searches aren't saved and the list is hidden.")) },
            ) {
                PickerRow(tr("Language"), LanguageFilter.entries, AppSettings.searchLanguage, { tr(it.title) }, { AppSettings.searchLanguage = it })
                PickerRow(tr("Sort"), SearchSort.entries, AppSettings.searchSort, { tr(it.title) }, { AppSettings.searchSort = it })
                ToggleRow(tr("Save Search History"), AppSettings.saveSearchHistory, { AppSettings.saveSearchHistory = it }, last = true)
            }
            section(footer = { FooterText(tr("When you return to the app, offer to open an nhentai link found on the clipboard.")) }) {
                ToggleRow(tr("Detect Gallery Links"), AppSettings.detectClipboard, { AppSettings.detectClipboard = it }, last = true)
            }
            section(footer = { FooterText(tr("Asks for your fingerprint, face or device PIN when the app opens or comes back, and hides it in recent apps.")) }) {
                val ctx = LocalContext.current
                val available = com.atlanyuey.aenht.core.AppLock.available(ctx)
                ToggleRow(tr("App Lock"), AppSettings.appLock && available, { AppSettings.appLock = it }, last = true,
                    subtitle = if (available) null else tr("Set a screen lock on this device first."))
            }
            section(footer = { FooterText(tr("What the app shows when you switch away from it, so the app switcher shows nothing private. Blurring needs Android 12; before that the app is covered.")) }) {
                PickerRow(tr("In the Background"), com.atlanyuey.aenht.core.BackgroundPrivacy.entries, AppSettings.backgroundPrivacy,
                    label = { tr(it.title) }, onSelect = { AppSettings.backgroundPrivacy = it }, last = true)
            }
            section(header = "Storage") {
                ListRow(tr("Image Cache"), value = cache?.let { Formatter.formatShortFileSize(context, it) }) {
                    if (cache == null) ActivityIndicator()
                }
                val clearAnchor = rememberAnchor()
                ButtonRow(clearLabel, {
                    overlays.confirm(clearAnchor.bounds, clearTitle, clearLabel) {
                        val loader = SingletonImageLoader.get(context)
                        loader.memoryCache?.clear()
                        loader.diskCache?.clear()
                        scope.launch { refresh() }
                    }
                }, destructive = true, last = true, modifier = Modifier.anchor(clearAnchor))
            }
        }
    }
}

// MARK: - Backup (export / import, the same file as the iOS app)

class BackupScreen : Screen() {
    @Composable
    override fun Content() {
        val context = LocalContext.current
        val overlays = LocalOverlays.current
        val importTitle = tr("Import Settings")
        val imported = tr("The settings were imported.")
        val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
            if (uri != null) {
                runCatching {
                    context.contentResolver.openOutputStream(uri)?.use { out ->
                        out.write(AppSettings.toJson(pretty = true).toByteArray())
                    }
                }
            }
        }
        val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) {
                val result = runCatching {
                    val text = context.contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() } ?: ""
                    AppSettings.applyJson(text)
                }
                overlays.alert(importTitle, if (result.isSuccess) imported else result.exceptionOrNull()?.localizedMessage)
            }
        }
        val fileName = L10n.string("AENHT Settings") + ".json"
        GroupedListScreen(tr("Settings Backup")) {
            section(footer = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    FooterText(tr("Save the settings as a file and import it on another device. The file also works with the iOS app."))
                    FooterText(tr("Android's own backup also keeps these settings."))
                }
            }) {
                ListRow(tr("Export Settings"), leading = { AppIcon(Icons.Share, null, tint = AppTheme.tint) }, onClick = { com.atlanyuey.aenht.core.launchSafely { exporter.launch(fileName) } })
                ListRow(tr("Import Settings"), leading = { AppIcon(Icons.Folder, null, tint = AppTheme.tint) }, last = true, onClick = {
                    com.atlanyuey.aenht.core.launchSafely { importer.launch(arrayOf("application/json", "text/plain", "*/*")) }
                })
            }
        }
    }
}

// MARK: - Language

class LanguageScreen : Screen() {
    @Composable
    override fun Content() {
        val context = LocalContext.current
        var selection by remember { mutableStateOf(AppLanguage.selected(context)) }
        // From the configuration, so the list follows a language change.
        val current = androidx.compose.ui.platform.LocalConfiguration.current.locales[0]
        fun choose(code: String) {
            if (code == selection) return
            selection = code
            AppLanguage.select(context, code)
            if (android.os.Build.VERSION.SDK_INT < 33) (context as? android.app.Activity)?.recreate()
        }
        GroupedListScreen(tr("Language")) {
            section {
                val system = AppLanguage.systemLocale()
                LanguageRow(tr("System Language"), system.getDisplayName(current), selection == "", last = true) { choose("") }
            }
            section(footer = { FooterText(tr("The language can also be chosen for this app in the Settings app.")) }) {
                AppLanguage.all.forEachIndexed { index, option ->
                    val subtitle = Locale.forLanguageTag(option.code).getDisplayName(current)
                    LanguageRow(option.name, subtitle, selection == option.code, last = index == AppLanguage.all.lastIndex) { choose(option.code) }
                }
            }
        }
    }

    @Composable
    private fun LanguageRow(title: String, subtitle: String?, selected: Boolean, last: Boolean, onClick: () -> Unit) {
        ListRow(title, subtitle = subtitle?.takeIf { it != title }, last = last, onClick = onClick) {
            if (selected) AppIcon(Icons.Check, null, tint = AppTheme.tint, size = 18.dp)
        }
    }
}

// MARK: - About

class AboutScreen : Screen() {
    @Composable
    override fun Content() {
        val context = LocalContext.current
        val navigator = LocalNavigator.current
        fun open(url: String) = com.atlanyuey.aenht.core.startSafely(context, Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        GroupedListScreen(tr("About")) {
            item {
                Column(Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 4.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    AboutIcon()
                    Txt("AENHT", style = Type.title2)
                    Txt(tr("Version %@", "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"), style = Type.subheadline, color = AppTheme.colors.secondaryLabel)
                }
            }
            section {
                ListRow(tr("Source Code"), value = "GitHub", chevron = true, onClick = { open("https://github.com/AtlanYuey/AENHT") })
                ListRow(tr("Website"), value = "nhentai.net", chevron = true, last = true, onClick = { open(NHHost.SITE) })
            }
            section(footer = { FooterText(tr("The diagnostic log lists what went wrong in this session (failed requests, translations, sync), without tokens, cookies or keys.")) }) {
                ListRow(tr("Check for Updates"), chevron = true, onClick = { navigator.push(UpdatesScreen()) })
                ListRow(tr("Share Diagnostic Log"), chevron = true, last = true, onClick = {
                    val header = buildString {
                        append("AENHT ").append(com.atlanyuey.aenht.BuildConfig.VERSION_NAME).append(" (").append(com.atlanyuey.aenht.BuildConfig.VERSION_CODE).append(")\n")
                        append("Android ").append(android.os.Build.VERSION.RELEASE).append(" (API ").append(android.os.Build.VERSION.SDK_INT).append("), ")
                        append(android.os.Build.MANUFACTURER).append(' ').append(android.os.Build.MODEL).append('\n')
                        append("Sites: ").append(AppSettings.enabledSites.joinToString { it.key })
                        append(" · nhentai login: ").append(Session.isLoggedIn)
                        append(" · E-Hentai login: ").append(EhSession.isLoggedIn).append(" · ExHentai: ").append(AppSettings.useExHentai && EhSession.hasExHentai).append('\n')
                        append("Reader: ").append(AppSettings.readMode.raw).append(" · Language: ").append(java.util.Locale.getDefault().toLanguageTag())
                    }
                    val text = com.atlanyuey.aenht.shared.app.Diagnostics.report(header)
                    val send = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_SUBJECT, "AENHT diagnostics")
                        putExtra(Intent.EXTRA_TEXT, text)
                    }
                    com.atlanyuey.aenht.core.startSafely(context, Intent.createChooser(send, null))
                })
            }
            section {
                ListRow(tr("References"), chevron = true, onClick = { navigator.push(ReferencesScreen()) })
                ListRow(tr("Open Source Licenses"), chevron = true, last = true, onClick = { navigator.push(LicensesScreen()) })
            }
        }
    }
}

private class License(val name: String, val author: String, val license: String, val text: Int?, val url: String)

private val licenses = listOf(
    License("eros_n", "honjow", "Apache License 2.0", R.raw.apache_2_0, "https://github.com/honjow/eros_n"),
    License("Backdrop (AndroidLiquidGlass)", "Kyant", "Apache License 2.0", R.raw.apache_2_0, "https://github.com/Kyant0/AndroidLiquidGlass"),
    License("Telephoto", "Saket Narayan", "Apache License 2.0", R.raw.apache_2_0, "https://github.com/saket/telephoto"),
    License("Coil", "Coil Contributors", "Apache License 2.0", R.raw.apache_2_0, "https://github.com/coil-kt/coil"),
    License("OkHttp", "Square, Inc.", "Apache License 2.0", R.raw.apache_2_0, "https://github.com/square/okhttp"),
    License("Jetpack Compose, AndroidX, Kotlin", "Google, JetBrains", "Apache License 2.0", R.raw.apache_2_0, "https://developer.android.com/jetpack"),
    License("Ktor", "JetBrains", "Apache License 2.0", R.raw.apache_2_0, "https://github.com/ktorio/ktor"),
    License("kotlinx.coroutines, kotlinx.serialization", "JetBrains", "Apache License 2.0", R.raw.apache_2_0, "https://github.com/Kotlin"),
    License("Room, SQLite for Android", "Google", "Apache License 2.0", R.raw.apache_2_0, "https://developer.android.com/jetpack/androidx/releases/room"),
    License("Okio", "Square, Inc.", "Apache License 2.0", R.raw.apache_2_0, "https://github.com/square/okio"),
    License("Ksoup", "Fleeksoft", "MIT License", null, "https://github.com/fleeksoft/ksoup"),
    License("ncnn", "Tencent", "BSD 3-Clause License", null, "https://github.com/Tencent/ncnn"),
    License("Ionicons", "Ionic", "MIT License", R.raw.ionicons, "https://github.com/ionic-team/ionicons"),
    License("Inter", "The Inter Project Authors", "SIL Open Font License 1.1", R.raw.inter_ofl, "https://github.com/rsms/inter"),
    License("LobeHub Icons", "LobeHub", "MIT License", R.raw.lobehub_icons, "https://github.com/lobehub/lobe-icons"),
    License("ML Kit", "Google", "ML Kit Terms of Service", null, "https://developers.google.com/ml-kit/terms"),
    License("Real-ESRGAN (super-resolution models)", "Xintao Wang", "BSD 3-Clause License", R.raw.real_esrgan, "https://github.com/xinntao/Real-ESRGAN"),
    License("nhentai_tags_fetcher", "honjow", "MIT License", R.raw.nhentai_tags_fetcher, "https://github.com/honjow/nhentai_tags_fetcher"),
    License("EhTagTranslation Database", "EhTagTranslation", "CC BY-NC-SA 3.0", null, "https://github.com/EhTagTranslation/Database"),
)

/**
 * The projects AENHT draws on, all alike: where it started, what its
 * features follow, and the data it uses (their licenses: Open Source Licenses).
 */
class ReferencesScreen : Screen() {
    private class Project(val name: String, val author: String, val role: String, val url: String)

    private val projects = listOf(
        Project("eros_n", "honjow", "The project AENHT started from", "https://github.com/honjow/eros_n"),
        Project("AEros-NHT", "AtlanYuey", "The earlier version of this app", "https://github.com/AtlanYuey/AEros-NHT"),
        Project("NextE", "erosTeam", "How the E-Hentai features behave", "https://github.com/erosTeam/NextE"),
        Project("NextN", "erosTeam", "How the nhentai features behave", "https://github.com/erosTeam/NextN"),
        Project("AY Translate", "AtlanYuey", "The settings of AI translation services", "https://github.com/AtlanYuey/AY_Translate"),
        Project("nhentai_tags_fetcher", "honjow", "nhentai's tag data", "https://github.com/honjow/nhentai_tags_fetcher"),
        Project("EhTagTranslation", "EhTagTranslation", "Chinese translations of tags", "https://github.com/EhTagTranslation/Database"),
    )

    @Composable
    override fun Content() {
        val context = LocalContext.current
        GroupedListScreen(tr("References")) {
            section(footer = { FooterText(tr("Thanks to everyone behind these projects. Their licenses are under Open Source Licenses.")) }) {
                projects.forEachIndexed { i, project ->
                    ListRow("${project.name} · ${project.author}", subtitle = tr(project.role), chevron = true,
                        last = i == projects.lastIndex,
                        onClick = { com.atlanyuey.aenht.core.startSafely(context, Intent(Intent.ACTION_VIEW, Uri.parse(project.url))) })
                }
            }
        }
    }
}

class LicensesScreen : Screen() {
    @Composable
    override fun Content() {
        val navigator = LocalNavigator.current
        val context = LocalContext.current
        GroupedListScreen(tr("Open Source Licenses")) {
            section(footer = {
                FooterText(tr("Tag translations come from the EhTagTranslation database, licensed under CC BY-NC-SA 3.0 (attribution, non-commercial, share alike). They are downloaded when the app updates its tags."))
            }) {
                licenses.forEachIndexed { index, license ->
                    ListRow(
                        license.name,
                        subtitle = "${license.author} · ${license.license}",
                        chevron = true,
                        last = index == licenses.lastIndex,
                        onClick = {
                            val text = license.text
                            if (text != null) navigator.push(LicenseTextScreen(license.name, text))
                            else com.atlanyuey.aenht.core.startSafely(context, Intent(Intent.ACTION_VIEW, Uri.parse(license.url)))
                        },
                    )
                }
            }
        }
    }
}

class LicenseTextScreen(private val title: String, private val resource: Int) : Screen() {
    @Composable
    override fun Content() {
        val context = LocalContext.current
        val text = remember { context.resources.openRawResource(resource).use { it.readBytes().decodeToString() } }
        ScreenScaffold(title = title) { padding ->
            SelectionContainer {
                Txt(
                    text,
                    Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(PaddingValues(start = 16.dp, end = 16.dp, top = padding.calculateTopPadding() + 8.dp, bottom = padding.calculateBottomPadding() + 16.dp)),
                    style = Type.footnote.copy(fontFamily = FontFamily.Monospace),
                )
            }
        }
    }
}


/** Downloads › Settings: how many at once, retries, a pause between pages, E-Hentai's originals. */
class DownloadSettingsScreen : Screen() {
    @Composable
    override fun Content() {
        GroupedListScreen(tr("Download Settings")) {
            section(footer = { FooterText(tr("More at once is faster but more likely to be limited by the site. E-Hentai uses at most 3 pages at once.")) }) {
                ListRow(tr("Galleries at Once"), value = "${AppSettings.downloadGalleries}") {
                    Stepper(AppSettings.downloadGalleries, 1..5) { AppSettings.downloadGalleries = it }
                }
                ListRow(tr("Pages at Once"), value = "${AppSettings.downloadPages}") {
                    Stepper(AppSettings.downloadPages, 1..6) { AppSettings.downloadPages = it }
                }
                ListRow(tr("Retries"), value = "${AppSettings.downloadRetries}", last = true) {
                    Stepper(AppSettings.downloadRetries, 0..5) { AppSettings.downloadRetries = it }
                }
            }
            section(footer = { FooterText(tr("A pause before each page, gentler on the sites.")) }) {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                    Row {
                        Txt(tr("Interval"), Modifier.weight(1f))
                        Txt(String.format(java.util.Locale.ROOT, "%.1f", AppSettings.downloadInterval) + tr(" s"), color = AppTheme.colors.secondaryLabel)
                    }
                    GlassSlider(
                        AppSettings.downloadInterval.toFloat(),
                        { AppSettings.downloadInterval = (Math.round(it * 2) / 2.0).coerceIn(0.0, 5.0) },
                        0f..5f,
                    )
                }
            }
            if (AppSettings.ehentaiEnabled) {
                section(Site.EHENTAI.label, footer = { FooterText(tr("Original images are larger; on E-Hentai they cost GP from your account when the file is big.")) }) {
                    ToggleRow(tr("Download Original Images"), AppSettings.ehOriginalImages, { AppSettings.ehOriginalImages = it }, last = true)
                }
            }
        }
    }
}


/** About › Check for Updates: the latest version, the latest test build, and the release notes. */
class UpdatesScreen : Screen() {
    private var info by mutableStateOf<com.atlanyuey.aenht.shared.app.UpdateInfo?>(null)
    private var error by mutableStateOf<String?>(null)

    @Composable
    override fun Content() {
        val context = LocalContext.current
        fun open(url: String) = com.atlanyuey.aenht.core.startSafely(context, Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        LaunchedEffect(Unit) {
            try {
                info = com.atlanyuey.aenht.shared.app.UpdateChecker().check(com.atlanyuey.aenht.BuildConfig.VERSION_NAME)
            } catch (e: Exception) {
                error = com.atlanyuey.aenht.core.errorText(e)
            }
        }
        GroupedListScreen(tr("Check for Updates")) {
            val i = info
            section {
                ListRow(tr("This Version"), value = com.atlanyuey.aenht.BuildConfig.VERSION_NAME, last = i == null)
                if (i != null) {
                    ListRow(
                        tr("Latest Version"),
                        value = i.latest?.tag ?: tr("None yet"),
                        subtitle = if (i.updateAvailable) tr("Update available") else null,
                        chevron = i.latest != null,
                        onClick = i.latest?.let { r -> { open(r.url) } },
                    )
                    ListRow(
                        tr("Latest Test Build"),
                        value = i.testBuild?.date?.take(16)?.replace('T', ' ') ?: "-",
                        chevron = i.testBuild != null,
                        last = true,
                        onClick = i.testBuild?.let { r -> { open(r.url) } },
                    )
                }
            }
            when {
                error != null -> item { EmptyState(Icons.Warning, error ?: "") }
                i == null -> item { Box(Modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) { ActivityIndicator() } }
                else -> i.releases.filter { it.notes.isNotBlank() }.take(10).forEach { r ->
                    section(r.name + " · " + r.date.take(10)) {
                        Txt(r.notes.trim(), Modifier.padding(16.dp), style = Type.footnote)
                    }
                }
            }
        }
    }
}
