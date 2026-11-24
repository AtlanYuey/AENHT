package com.atlanyuey.aenht.screens

import com.atlanyuey.aenht.ui.components.cutCards
import com.atlanyuey.aenht.ui.components.prompt
import com.atlanyuey.aenht.core.label
import com.atlanyuey.aenht.core.shortLabel
import com.atlanyuey.aenht.shared.site.GallerySummary
import com.atlanyuey.aenht.shared.site.Site
import com.atlanyuey.aenht.shared.site.SiteLinks
import com.atlanyuey.aenht.shared.site.GalleryLink
import com.atlanyuey.aenht.shared.site.QueryConverter
import com.atlanyuey.aenht.shared.eh.EhListSource
import com.atlanyuey.aenht.shared.eh.EhToplistPeriod
import com.atlanyuey.aenht.core.EhClient
import com.atlanyuey.aenht.core.HomeRow
import com.atlanyuey.aenht.core.EhHomeList
import com.atlanyuey.aenht.core.EhSession
import com.atlanyuey.aenht.core.FeedPage
import com.atlanyuey.aenht.core.SiteChoice
import com.atlanyuey.aenht.core.ehFeed
import com.atlanyuey.aenht.core.nhFeed
import com.atlanyuey.aenht.core.FavoritesStore
import com.atlanyuey.aenht.core.TagStore
import com.atlanyuey.aenht.ui.components.ToggleRow
import com.atlanyuey.aenht.ui.components.SiteFilterButton
import com.atlanyuey.aenht.ui.components.LocalShowsSiteBadge
import com.atlanyuey.aenht.ui.components.CoverBadge
import com.atlanyuey.aenht.ui.components.galleryScreen

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.togetherWith
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.runtime.derivedStateOf
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.atlanyuey.aenht.ui.components.NAV_BAR_HEIGHT
import com.atlanyuey.aenht.ui.components.rememberAnchor
import com.atlanyuey.aenht.ui.components.anchor
import com.atlanyuey.aenht.ui.components.MenuItem
import com.atlanyuey.aenht.ui.components.GlassCapsuleButton
import com.atlanyuey.aenht.ui.components.hidesBarsOnScroll
import com.atlanyuey.aenht.ui.components.BarsState
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.combinedClickable
import androidx.activity.compose.BackHandler
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalFocusManager
import android.content.Context
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.staggeredgrid.LazyStaggeredGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.atlanyuey.aenht.core.AppSettings
import com.atlanyuey.aenht.core.GalleryFeed
import com.atlanyuey.aenht.core.GalleryItem
import com.atlanyuey.aenht.core.GalleryListStyle
import com.atlanyuey.aenht.core.HistoryEntry
import com.atlanyuey.aenht.core.HistoryStore
import com.atlanyuey.aenht.core.L10n
import com.atlanyuey.aenht.core.LanguageFilter
import com.atlanyuey.aenht.core.NHClient
import com.atlanyuey.aenht.core.SearchSort
import com.atlanyuey.aenht.core.Session
import com.atlanyuey.aenht.core.TagSuggestion
import com.atlanyuey.aenht.core.tr
import com.atlanyuey.aenht.ui.components.ActivityIndicator
import com.atlanyuey.aenht.ui.components.AppIcon
import com.atlanyuey.aenht.ui.components.ButtonRole
import com.atlanyuey.aenht.ui.components.DialogButton
import com.atlanyuey.aenht.ui.components.EmptyState
import com.atlanyuey.aenht.ui.components.GalleryCard
import com.atlanyuey.aenht.ui.components.GalleryGrid
import com.atlanyuey.aenht.ui.components.GalleryLayout
import com.atlanyuey.aenht.ui.components.LocalCoverBadge
import com.atlanyuey.aenht.ui.components.LocalGalleryMenuItems
import com.atlanyuey.aenht.ui.components.galleryItems
import com.atlanyuey.aenht.core.LibraryStyle
import androidx.compose.runtime.CompositionLocalProvider
import com.atlanyuey.aenht.ui.components.GalleryRow
import com.atlanyuey.aenht.ui.components.GlassCircleButton
import com.atlanyuey.aenht.ui.components.LargeTitle
import com.atlanyuey.aenht.ui.components.LocalNavigator
import com.atlanyuey.aenht.ui.components.LocalOverlays
import com.atlanyuey.aenht.ui.components.MenuButton
import com.atlanyuey.aenht.ui.components.PullToRefresh
import com.atlanyuey.aenht.ui.components.Screen
import com.atlanyuey.aenht.ui.components.ScreenScaffold
import com.atlanyuey.aenht.ui.components.SearchField
import com.atlanyuey.aenht.ui.components.SectionTitle
import com.atlanyuey.aenht.ui.components.TextButton
import com.atlanyuey.aenht.ui.components.Txt
import com.atlanyuey.aenht.ui.components.feedFooter
import com.atlanyuey.aenht.ui.components.fullLine
import com.atlanyuey.aenht.ui.components.pickerItems
import com.atlanyuey.aenht.ui.theme.AppTheme
import com.atlanyuey.aenht.ui.theme.Icons
import com.atlanyuey.aenht.ui.theme.Type
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import java.text.DateFormat
import java.util.Calendar
import java.util.Date
import kotlin.math.roundToInt

// MARK: - Shared pieces

/** Language and sort menus of a gallery list. */
@Composable
fun FeedMenus(language: LanguageFilter, sort: SearchSort, onLanguage: (LanguageFilter) -> Unit, onSort: (SearchSort) -> Unit) {
    MenuButton(Icons.Globe, tr("Language"), {
        listOf(pickerItems(LanguageFilter.entries, language, { L10n.string(it.title) }, onLanguage))
    }, title = tr("Language"))
    MenuButton(Icons.Sort, tr("Sort"), {
        listOf(pickerItems(SearchSort.entries, sort, { L10n.string(it.title) }, onSort))
    }, title = tr("Sort"))
}

/** A full-width row that extends into the grid's side padding. */
fun Modifier.bleed(horizontal: androidx.compose.ui.unit.Dp) = layout { measurable, constraints ->
    val extra = (horizontal * 2).roundToPx()
    val placeable = measurable.measure(
        constraints.copy(minWidth = constraints.minWidth + extra, maxWidth = constraints.maxWidth + extra),
    )
    layout(constraints.maxWidth, placeable.height) { placeable.place(-horizontal.roundToPx(), 0) }
}

/** Horizontally scrolling row of covers. */
/** The title of a gallery page's row of its other versions. */
@Composable
fun VersionsTitle() {
    Column {
        SectionTitle(tr("Other Versions"))
        Txt(tr("Translations and other editions of this gallery"), Modifier.padding(start = 20.dp, end = 16.dp, bottom = 8.dp),
            style = Type.footnote, color = AppTheme.colors.secondaryLabel)
    }
}

/**
 * Horizontally scrolling row of covers, out to the screen's edges. Where it
 * is cut off instead ([cutStart], [cutEnd]: next to the other half of a wide
 * page), the covers end in round corners and their titles fade out.
 */
@Composable
fun PopularRow(items: List<GallerySummary>, cutStart: Boolean = false, cutEnd: Boolean = false) {
    val state = rememberLazyListState()
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val cardWidth = if (maxWidth + 32.dp > 600.dp) 170.dp else 132.dp
        // Out to the edge of the screen, except where it is cut off.
        val before = if (cutStart) 0.dp else 16.dp
        val after = if (cutEnd) 0.dp else 16.dp
        val cut = if (cutStart || cutEnd) Modifier.cutCards(cutStart, cutEnd, imageHeight = cardWidth * 4 / 3) else Modifier
        LazyRow(
            Modifier.fillMaxWidth().bleedSides(before, after).then(cut),
            state = state,
            contentPadding = PaddingValues(start = before, end = after),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            flingBehavior = rememberSnapFlingBehavior(state),
        ) {
            items(items, key = { it.key.storageKey }) { item ->
                GalleryCard(item, GalleryListStyle.Grid, Modifier.width(cardWidth))
            }
        }
    }
}

/** Extends into the side padding by [start] and [end]. */
private fun Modifier.bleedSides(start: androidx.compose.ui.unit.Dp, end: androidx.compose.ui.unit.Dp) = layout { measurable, constraints ->
    val s = start.roundToPx()
    val e = end.roundToPx()
    val placeable = measurable.measure(constraints.copy(minWidth = constraints.minWidth + s + e, maxWidth = constraints.maxWidth + s + e))
    layout(constraints.maxWidth, placeable.height) { placeable.placeRelative(-s, 0) }
}

@Composable
private fun FeedTitle(title: String, subtitle: String?) {
    Column(Modifier.fillMaxWidth().padding(top = 18.dp, bottom = 6.dp, start = 4.dp)) {
        Txt(title, style = Type.title2)
        subtitle?.let { Txt(it, style = Type.subheadline, color = AppTheme.colors.secondaryLabel) }
    }
}

/** Shows the inline title once the large title (first item) scrolled away. */
private fun LazyStaggeredGridState.pastTitle() = firstVisibleItemIndex > 0 || firstVisibleItemScrollOffset > 60

// MARK: - Home

/** The E-Hentai list the home page shows for [list] (language narrows Latest and Watched). */
fun ehHomeSource(list: EhHomeList, language: LanguageFilter): EhListSource {
    val lang = language.queryTerm.orEmpty()
    return when (list) {
        EhHomeList.Latest -> EhListSource.Search(lang)
        EhHomeList.Popular -> EhListSource.Popular
        EhHomeList.Watched -> EhListSource.Watched(lang)
        EhHomeList.ToplistDay -> EhListSource.Toplist(EhToplistPeriod.YESTERDAY)
        EhHomeList.ToplistMonth -> EhListSource.Toplist(EhToplistPeriod.PAST_MONTH)
        EhHomeList.ToplistYear -> EhListSource.Toplist(EhToplistPeriod.PAST_YEAR)
        EhHomeList.ToplistAll -> EhListSource.Toplist(EhToplistPeriod.ALL_TIME)
    }
}

/** A tab's root screen: told when its tab button is tapped again (RootView). */
interface TabRoot {
    fun tabTapped()
}

/**
 * A tab button tapped again while the tab's list shows: away from the top,
 * the list goes to the top; at the top, back to where it was; tapped twice
 * quickly, the tab refreshes.
 */
class TabTaps(private val scope: kotlinx.coroutines.CoroutineScope) {
    private var saved: Pair<Int, Int>? = null
    /** A tap at the top, waiting to see if a second follows. */
    private var pending: Job? = null
    private var lastTopTap = 0L

    fun tapped(index: Int, offset: Int, scrollTo: suspend (Int, Int) -> Unit, refresh: () -> Unit) {
        val now = android.os.SystemClock.uptimeMillis()
        if (pending?.isActive == true || now - lastTopTap < DOUBLE_TAP_MILLIS) {
            pending?.cancel()
            pending = null
            lastTopTap = 0
            saved = null
            scope.launch { scrollTo(0, 0) }
            refresh()
            return
        }
        if (index > 0 || offset > 0) {
            saved = index to offset
            lastTopTap = now
            scope.launch { scrollTo(0, 0) }
            return
        }
        pending = scope.launch {
            delay(DOUBLE_TAP_MILLIS)
            pending = null
            val back = saved ?: return@launch
            saved = null
            scrollTo(back.first, back.second)
        }
    }

    private companion object {
        /** Two taps this close together refresh. */
        const val DOUBLE_TAP_MILLIS = 300L
    }
}

class HomeScreen : Screen(), TabRoot {
    private val nhFeedState = GalleryFeed(scope, nhFeed { page ->
        NHClient.galleries(AppSettings.homeLanguage.queryTerm ?: "", AppSettings.homeSort, page)
    })
    private val ehFeedState = GalleryFeed(scope, ehFeed { ehHomeSource(AppSettings.ehHomeList, AppSettings.homeLanguage) })
    private val feed: GalleryFeed get() = if (AppSettings.currentSite == Site.EHENTAI) ehFeedState else nhFeedState
    /** The rows pinned above the feed (Home settings), as loaded. */
    private var rows by mutableStateOf(emptyMap<HomeRow, List<GallerySummary>>())
    private val grid = LazyStaggeredGridState()
    private var pulling by mutableStateOf(false)

    // Large screens: the search field opens in the navigation bar and the
    // search shows in place of the feed (no separate search tab).
    private val search = SearchState()
    private val searchPanel = SearchPanel(search, scope)
    private var searching by mutableStateOf(false)

    private val taps = TabTaps(scope)

    /** The Home tab was tapped while Home shows (see [TabTaps]). */
    override fun tabTapped() {
        if (searching) return
        taps.tapped(
            grid.firstVisibleItemIndex, grid.firstVisibleItemScrollOffset,
            scrollTo = { index, offset -> grid.animateScrollToItem(index, offset) },
            refresh = { pulling = true; reload() },
        )
    }

    private fun reload() {
        val job = feed.reload()
        scope.launch {
            loadRows()
            job.join()
            pulling = false
        }
    }

    private suspend fun loadRows() = coroutineScope {
        val loaded = AppSettings.homeRows(AppSettings.currentSite).map { row ->
            async { row to (runCatching { loadRow(row) }.getOrNull() ?: rows[row]) }
        }.awaitAll()
        rows = rows + loaded.mapNotNull { (row, items) -> items?.let { row to it } }
    }

    private suspend fun loadRow(row: HomeRow): List<GallerySummary> = when (row) {
        HomeRow.NhPopular -> NHClient.popular().map(GallerySummary::from)
        HomeRow.NhToday -> NHClient.galleries("", SearchSort.PopularToday, 1).result.map(GallerySummary::from)
        HomeRow.NhWeek -> NHClient.galleries("", SearchSort.PopularWeek, 1).result.map(GallerySummary::from)
        HomeRow.NhMonth -> NHClient.galleries("", SearchSort.PopularMonth, 1).result.map(GallerySummary::from)
        HomeRow.NhAll -> NHClient.galleries("", SearchSort.Popular, 1).result.map(GallerySummary::from)
        HomeRow.EhPopular -> EhClient.list(EhListSource.Popular).galleries.map(GallerySummary::from)
        HomeRow.EhDay -> EhClient.list(EhListSource.Toplist(EhToplistPeriod.YESTERDAY)).galleries.map(GallerySummary::from)
        HomeRow.EhMonth -> EhClient.list(EhListSource.Toplist(EhToplistPeriod.PAST_MONTH)).galleries.map(GallerySummary::from)
        HomeRow.EhYear -> EhClient.list(EhListSource.Toplist(EhToplistPeriod.PAST_YEAR)).galleries.map(GallerySummary::from)
        HomeRow.EhAll -> EhClient.list(EhListSource.Toplist(EhToplistPeriod.ALL_TIME)).galleries.map(GallerySummary::from)
    }

    @Composable
    override fun Content() {
        val site = AppSettings.currentSite
        LaunchedEffect(Unit) {
            snapshotFlow { AppSettings.currentSite }.collect { if (!feed.hasLoaded) reload() }
        }
        LaunchedEffect(Unit) {
            // Rows chosen in the settings (or ExHentai turned on) load at once.
            snapshotFlow { AppSettings.homeRowsRaw to AppSettings.useExHentai }.drop(1).collect { (_, ex) ->
                // ExHentai's lists are other galleries: E-Hentai's rows are read again.
                rows = rows.filterKeys { r -> r.site != Site.EHENTAI }
                loadRows()
            }
        }
        LaunchedEffect(Unit) {
            snapshotFlow { AppSettings.homeLanguage to AppSettings.homeSort }.drop(1).collect { nhFeedState.reload() }
        }
        LaunchedEffect(Unit) {
            snapshotFlow { Triple(AppSettings.ehHomeList, AppSettings.homeLanguage, AppSettings.useExHentai) }.drop(1).collect { ehFeedState.reload() }
        }
        val language = AppSettings.homeLanguage
        val focusManager = LocalFocusManager.current
        val density = LocalDensity.current
        val screenWidth = LocalConfiguration.current.screenWidthDp.dp
        val endSearch = {
            focusManager.clearFocus()
            search.reset()
            searching = false
        }
        if (searching) {
            searchPanel.Effects()
            BackHandler { endSearch() }
        }
        val pinned = AppSettings.homeRows(site).mapNotNull { row ->
            rows[row]?.let { com.atlanyuey.aenht.core.Filters.visible(it) }?.takeIf { it.isNotEmpty() }?.let { row to it }
        }
        // The feed's header (site, sort, language, search) has scrolled under the
        // navigation bar: its buttons show in the bar instead.
        val statusTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
        val barBottom = with(density) { (statusTop + NAV_BAR_HEIGHT.dp).roundToPx() }
        val headerIndex = 1
        val docked by remember(headerIndex, barBottom) {
            derivedStateOf {
                val info = grid.layoutInfo
                val header = info.visibleItemsInfo.firstOrNull { it.key == "feedTitle" }
                if (header != null) {
                    // Offsets start after the top padding: on screen it is offset - viewportStartOffset.
                    header.offset.y - info.viewportStartOffset + header.size.height < barBottom
                } else {
                    (info.visibleItemsInfo.firstOrNull()?.index ?: 0) > headerIndex
                }
            }
        }
        val sortTitle = if (site == Site.EHENTAI) tr(AppSettings.ehHomeList.title) else tr(AppSettings.homeSort.title)
        val sortItems = {
            if (site == Site.EHENTAI) {
                val lists = EhHomeList.entries
                listOf(
                    pickerItems(lists.take(3), AppSettings.ehHomeList, { L10n.string(it.title) }) { AppSettings.ehHomeList = it },
                    pickerItems(lists.drop(3), AppSettings.ehHomeList, { L10n.string(it.title) }) { AppSettings.ehHomeList = it },
                )
            } else {
                listOf(pickerItems(SearchSort.entries, AppSettings.homeSort, { L10n.string(it.title) }) { AppSettings.homeSort = it })
            }
        }
        val languageItems = {
            listOf(pickerItems(LanguageFilter.entries, AppSettings.homeLanguage, { L10n.string(it.title) }) { AppSettings.homeLanguage = it })
        }
        val navigator = LocalNavigator.current
        val overlaysHome = LocalOverlays.current
        val randomLabel = tr("Random Gallery")
        val randomError = tr("Couldn't Load")
        // The title's menu switches the site: with one site on, there is no menu (and no arrow).
        val siteItems: (() -> List<List<MenuItem>>)? = if (AppSettings.enabledSites.size > 1) {
            { listOf(AppSettings.enabledSites.map { s -> MenuItem(s.label, checked = s == site) { AppSettings.homeSite = s } }) }
        } else {
            null
        }
        val openRandom: () -> Unit = {
            scope.launch {
                try {
                    navigator.push(GalleryScreen(NHClient.randomId(), null))
                } catch (e: Exception) {
                    overlaysHome.alert(randomError, com.atlanyuey.aenht.core.errorText(e))
                }
            }
        }
        ScreenScaffold(
            // Three parts, like the iOS bar: the sort, the site (a menu that
            // switches it) and the language and search.
            title = site.label,
            showTitle = { !searching },
            titleMenu = siteItems,
            leading = if (searching) null else {
                {
                    AnimatedVisibility(docked, enter = fadeIn() + scaleIn(initialScale = 0.8f), exit = fadeOut() + scaleOut(targetScale = 0.8f)) {
                        DockedSortButton(sortTitle, sortItems)
                    }
                }
            },
            trailing = {
                if (searching) {
                    val showsMenus = searchPanel.results() != null
                    if (showsMenus) {
                        FeedMenus(AppSettings.searchLanguage, AppSettings.searchSort, { AppSettings.searchLanguage = it }, { AppSettings.searchSort = it })
                    }
                    val focus = remember { FocusRequester() }
                    val room = screenWidth - 32.dp - 54.dp - if (showsMenus) 108.dp else 0.dp
                    SearchField(
                        text = search.text,
                        onTextChange = { search.text = it },
                        placeholder = tr("Titles, tags, artists, IDs"),
                        onSubmit = { search.submit(search.text) },
                        modifier = Modifier.width(minOf(340.dp, room)).height(44.dp),
                        focusRequester = focus,
                        leading = {
                            val sites = AppSettings.enabledSites
                            // Tap the site to search the other one.
                            SiteSearchIcon(search.site, onClick = if (sites.size > 1) {
                                { search.site = sites[(sites.indexOf(search.site) + 1) % sites.size] }
                            } else null)
                        },
                    )
                    LaunchedEffect(Unit) { focus.requestFocus() }
                    GlassCircleButton(Icons.Close, tr("Cancel"), endSearch)
                } else {
                    // nhentai's random gallery at the top right; the feed's language and
                    // search buttons take its place once they have scrolled up into the bar.
                    androidx.compose.animation.AnimatedContent(
                        targetState = docked,
                        transitionSpec = {
                            (fadeIn() + scaleIn(initialScale = 0.8f)) togetherWith (fadeOut() + scaleOut(targetScale = 0.8f))
                        },
                        label = "homeTrailing",
                    ) { isDocked ->
                        if (isDocked) {
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                MenuButton(Icons.Globe, tr("Language"), languageItems, title = tr("Language"))
                                GlassCircleButton(Icons.Search, tr("Search"), { search.site = AppSettings.searchStartSite(); searching = true })
                            }
                        } else if (site == Site.NHENTAI) {
                            GlassCircleButton(Icons.Dice, randomLabel, openRandom)
                        }
                    }
                }
            },
        ) { padding ->
            if (searching) {
                searchPanel.Body(padding, largeTitle = null)
                return@ScreenScaffold
            }
            val current = feed
            PullToRefresh(pulling, { pulling = true; reload() }, topInset = padding.calculateTopPadding()) {
                GalleryGrid(
                    current.items, grid, padding,
                    onItemShown = { current.loadMoreIfNeeded(it) },
                    header = {
                        // One item for all pinned rows, always there: rows that load
                        // after the feed make it taller instead of going in above
                        // the item the grid keeps in place.
                        fullLine("pinned") {
                            if (pinned.size == 2 && AppSettings.tabletHome && com.atlanyuey.aenht.ui.components.isWidePage()) {
                                // Wide (a tablet held sideways): the two rows side by side.
                                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    pinned.forEachIndexed { i, (row, items) ->
                                        // Each row scrolls inside its own half, cut off where the halves meet.
                                        Column(Modifier.weight(1f)) {
                                            FeedTitle(tr(row.title), null)
                                            PopularRow(items, cutStart = i == 1, cutEnd = i == 0)
                                        }
                                    }
                                }
                            } else {
                                Column {
                                    pinned.forEach { (row, items) ->
                                        FeedTitle(tr(row.title), null)
                                        PopularRow(items)
                                    }
                                }
                            }
                        }
                        fullLine("feedTitle") {
                            FeedHeader(site, null, sortTitle, language, sortItems, languageItems) {
                                search.site = AppSettings.searchStartSite()
                                searching = true
                            }
                        }
                    },
                    footer = { feedFooter(current, L10n.string("No Galleries") to Icons.Photo) },
                )
            }
        }
    }
}

/**
 * The site Home shows, as its large title ("nhentai", "E-Hentai",
 * "ExHentai"); with both sites on, tapping it switches between them.
 */
@Composable
private fun SiteTitle(site: Site, items: (() -> List<List<MenuItem>>)?) {
    val overlays = LocalOverlays.current
    val anchor = rememberAnchor()
    val title = tr("Site")
    Row(
        Modifier
            .padding(start = 4.dp, top = 2.dp, bottom = 4.dp)
            .anchor(anchor)
            .clip(RoundedCornerShape(10.dp))
            .then(if (items != null) Modifier.clickable { overlays.menu(anchor.bounds, items(), title) } else Modifier),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Txt(site.label, style = Type.largeTitle, maxLines = 1)
        if (items != null) AppIcon(Icons.ChevronDown, null, tint = AppTheme.colors.secondaryLabel, size = 18.dp)
    }
}

/** NH / EH as a small capsule with the menu of sites (home page header and bar). */
@Composable
fun SiteBadgeButton(site: Site, items: () -> List<List<MenuItem>>) {
    val overlays = LocalOverlays.current
    val anchor = rememberAnchor()
    val title = tr("Site")
    GlassCapsuleButton(
        onClick = { overlays.menu(anchor.bounds, items(), title) },
        modifier = Modifier.anchor(anchor),
        horizontalPadding = 12.dp,
    ) {
        Txt(site.shortLabel, style = Type.subheadline.copy(fontWeight = FontWeight.Bold), maxLines = 1)
        AppIcon(Icons.ChevronDown, null, tint = AppTheme.colors.secondaryLabel, size = 12.dp)
    }
}

/** The site a search runs on, at the start of the search field. */
@Composable
fun SiteSearchIcon(site: Site, onClick: (() -> Unit)? = null) {
    Txt(
        site.shortLabel,
        Modifier
            .clip(RoundedCornerShape(6.dp))
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .background(if (site == Site.EHENTAI) Color(0xFF9E2720) else AppTheme.tint)
            .padding(horizontal = 5.dp, vertical = 1.dp),
        style = Type.caption2.copy(fontWeight = FontWeight.Bold),
        color = Color.White,
    )
}

/**
 * The feed's header: the sort (tap it to switch), and on the right the
 * language and search buttons. Scrolled under the navigation bar, the same
 * buttons show in the bar.
 */
@Composable
private fun FeedHeader(
    site: Site,
    siteItems: (() -> List<List<MenuItem>>)?,
    sortTitle: String,
    language: LanguageFilter,
    sortItems: () -> List<List<MenuItem>>,
    languageItems: () -> List<List<MenuItem>>,
    onSearch: () -> Unit,
) {
    val overlays = LocalOverlays.current
    val anchor = rememberAnchor()
    val sortLabel = tr("Sort")
    Row(
        Modifier.fillMaxWidth().padding(top = 18.dp, bottom = 6.dp, start = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Column(
            Modifier
                .weight(1f)
                .anchor(anchor)
                .clickable(interactionSource = null, indication = null) { overlays.menu(anchor.bounds, sortItems(), sortLabel) }
                .semantics { contentDescription = sortLabel },
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Txt(sortTitle, style = Type.title2, maxLines = 1)
                AppIcon(Icons.ChevronDown, null, tint = AppTheme.colors.secondaryLabel, size = 16.dp)
            }
            Txt(tr(language.title), style = Type.subheadline, color = AppTheme.colors.secondaryLabel, maxLines = 1)
        }
        MenuButton(Icons.Globe, tr("Language"), languageItems, title = tr("Language"))
        GlassCircleButton(Icons.Search, tr("Search"), onSearch)
    }
}

/** The sort, as a small glass button in the navigation bar (header docked). */
@Composable
private fun DockedSortButton(sortTitle: String, items: () -> List<List<MenuItem>>) {
    val overlays = LocalOverlays.current
    val anchor = rememberAnchor()
    val title = tr("Sort")
    GlassCapsuleButton(
        onClick = { overlays.menu(anchor.bounds, items(), title) },
        modifier = Modifier.anchor(anchor).widthIn(max = 160.dp),
        horizontalPadding = 14.dp,
    ) {
        Txt(sortTitle, Modifier.weight(1f, fill = false), style = Type.subheadline.copy(fontWeight = FontWeight.SemiBold), maxLines = 1)
        AppIcon(Icons.ChevronDown, null, tint = AppTheme.colors.secondaryLabel, size = 12.dp)
    }
}

// MARK: - Favorites

/**
 * Favorites of one site (live from the site, E-Hentai by folder), or of
 * both ("All", from the local copy, newest first, marked NH / EH). The
 * title opens the menu of sites and folders.
 */
class FavoritesScreen : Screen(), TabRoot {
    private var site by mutableStateOf(AppSettings.site(AppSettings.favoritesSite))
    private var ehFolder by mutableStateOf<Int?>(null)
    /** Searching inside the favorites (both sites search their own; All filters the copy). */
    private var query by mutableStateOf("")
    private val nhFeedState = GalleryFeed(scope, nhFeed { page -> NHClient.favorites(page, query) }, filtered = false)
    private val ehFeedState = GalleryFeed(scope, ehFeed { EhListSource.Favorites(ehFolder, query) }, filtered = false)
    private val grid = LazyStaggeredGridState()
    private var pulling by mutableStateOf(false)
    private val taps = TabTaps(scope)
    /** What a pull does now (set while the list shows). */
    private var refreshAction: (() -> Unit)? = null

    override fun tabTapped() {
        taps.tapped(
            grid.firstVisibleItemIndex, grid.firstVisibleItemScrollOffset,
            scrollTo = { index, offset -> grid.animateScrollToItem(index, offset) },
            refresh = { refreshAction?.invoke() },
        )
    }

    override fun onAppear() {
        // "Same as Home" follows the site the home page is on now.
        if (AppSettings.favoritesSite == SiteChoice.Follow) site = AppSettings.site(SiteChoice.Follow)
    }

    private fun loggedIn(s: Site) = if (s == Site.NHENTAI) Session.isLoggedIn else EhSession.isLoggedIn

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.current
        val current = site
        val feed = if (current == Site.EHENTAI) ehFeedState else nhFeedState
        LaunchedEffect(current, Session.isLoggedIn, EhSession.isLoggedIn, ehFolder) {
            when {
                current == null -> FavoritesStore.syncIfStale()
                loggedIn(current) && !feed.hasLoaded -> feed.reload()
            }
        }
        LaunchedEffect(Unit) { snapshotFlow { ehFolder }.drop(1).collect { ehFeedState.reload() } }
        LaunchedEffect(feed.isLoading, FavoritesStore.syncing) { if (!feed.isLoading && FavoritesStore.syncing == null) pulling = false }
        val needsLogin = current != null && !loggedIn(current)
        ScreenScaffold(
            title = tr("Favorites"),
            showTitle = { needsLogin || grid.pastTitle() },
            trailing = {
                if (current == null) SyncIndicator()
                if (!needsLogin) {
                    val overlays = LocalOverlays.current
                    val title = tr("Search Favorites")
                    val search = tr("Search")
                    val cancel = tr("Cancel")
                    GlassCircleButton(Icons.Search, title, {
                        overlays.prompt(title, initial = query, confirm = search, cancel = cancel) { text ->
                            query = text.trim()
                            if (site != null) feed.reload()
                        }
                    })
                }
            },
        ) { padding ->
            if (needsLogin) {
                Column(Modifier.fillMaxSize().padding(padding)) {
                    FavoritesTitle()
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        EmptyState(
                            icon = Icons.Heart,
                            title = tr("Favorites"),
                            message = if (current == Site.EHENTAI) tr("Log in to your E-Hentai account to see your favorites.")
                            else tr("Log in to your nhentai account to see your favorites."),
                            action = tr("Log In") to { navigator.push(if (current == Site.EHENTAI) EhLoginScreen() else LoginScreen()) },
                        )
                    }
                }
                return@ScreenScaffold
            }
            val refresh: () -> Unit = {
                pulling = true
                if (current == null) FavoritesStore.sync() else feed.reload()
            }
            refreshAction = refresh
            PullToRefresh(pulling, refresh, topInset = padding.calculateTopPadding()) {
                if (current == null) {
                    val cards = FavoritesStore.cards(null, AppSettings.favoritesAllFolders.takeIf { it.size < 10 })
                        .filter { query.isBlank() || it.title.contains(query, ignoreCase = true) }
                    CompositionLocalProvider(LocalShowsSiteBadge provides true) {
                        GalleryGrid(
                            cards, grid, padding,
                            header = {
                                fullLine("title") { FavoritesTitle() }
                                if (cards.isEmpty()) fullLine("empty") {
                                    if (FavoritesStore.syncing != null) {
                                        Box(Modifier.fillMaxWidth().height(300.dp), contentAlignment = Alignment.Center) { ActivityIndicator(size = 30.dp) }
                                    } else {
                                        EmptyState(Icons.Heart, tr("No Favorites"), FavoritesStore.error ?: tr("Log in to a site to see its favorites here."))
                                    }
                                }
                            },
                        )
                    }
                } else {
                    GalleryGrid(
                        feed.items, grid, padding,
                        onItemShown = { feed.loadMoreIfNeeded(it) },
                        header = { fullLine("title") { FavoritesTitle() } },
                        footer = { feedFooter(feed, L10n.string("No Favorites") to Icons.Heart) },
                    )
                }
            }
        }
    }

    /** "Favorites ▾ · All": tap for the sites and E-Hentai's folders. */
    @Composable
    private fun FavoritesTitle() {
        val overlays = LocalOverlays.current
        val anchor = rememberAnchor()
        val sites = AppSettings.enabledSites
        val menuTitle = tr("Favorites")
        val allLabel = tr("All")
        val allFolders = tr("All Folders")
        val shownFolders = tr("E-Hentai Folders Shown in All")
        val subtitle = when (site) {
            null -> allLabel
            Site.NHENTAI -> "nhentai"
            Site.EHENTAI -> Site.EHENTAI.label + " · " + (ehFolder?.let { EhFolders.names.getOrNull(it) } ?: allFolders)
        } + if (query.isNotBlank()) " · “$query”" else ""
        LaunchedEffect(site) { if (site != Site.NHENTAI) EhFolders.load() }
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 4.dp, end = 4.dp, top = 8.dp, bottom = 6.dp)
                .anchor(anchor)
                .clickable(interactionSource = null, indication = null) {
                    val siteItems = buildList<MenuItem> {
                        if (sites.size > 1) add(MenuItem(allLabel, checked = site == null) { site = null })
                        sites.forEach { s -> add(MenuItem(s.label, checked = site == s && (s != Site.EHENTAI || ehFolder == null)) { site = s; ehFolder = null }) }
                    }
                    val folderItems = if (site == Site.EHENTAI && EhSession.isLoggedIn) {
                        EhFolders.names.mapIndexed { i, name ->
                            MenuItem(name, Icons.Folder, checked = ehFolder == i) { ehFolder = i }
                        }
                    } else {
                        emptyList()
                    }
                    val allItems = if (site == null && sites.contains(Site.EHENTAI)) {
                        listOf(MenuItem(shownFolders, Icons.Folder) { overlays.sheet { dismiss -> FolderPicker(dismiss) } })
                    } else {
                        emptyList()
                    }
                    overlays.menu(anchor.bounds, listOf(siteItems, folderItems, allItems).filter { it.isNotEmpty() }, menuTitle)
                },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Txt(tr("Favorites"), style = Type.largeTitle)
                    AppIcon(Icons.ChevronDown, null, tint = AppTheme.colors.secondaryLabel, size = 18.dp)
                }
                Txt(subtitle, style = Type.subheadline, color = AppTheme.colors.secondaryLabel, maxLines = 1)
            }
            if (query.isNotBlank()) {
                TextButton(tr("Clear Search"), {
                    query = ""
                    if (site == Site.EHENTAI) ehFeedState.reload() else if (site == Site.NHENTAI) nhFeedState.reload()
                })
            }
        }
    }

    /** Which E-Hentai folders "All" shows (all by default). */
    @Composable
    private fun FolderPicker(dismiss: () -> Unit) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Txt(tr("E-Hentai Folders Shown in All"), Modifier.weight(1f), style = Type.headline)
                TextButton(tr("Done"), dismiss, bold = true)
            }
            EhFolders.names.forEachIndexed { i, name ->
                val shown = i in AppSettings.favoritesAllFolders
                ToggleRow(name, shown, { on ->
                    AppSettings.favoritesAllFolders = if (on) AppSettings.favoritesAllFolders + i else AppSettings.favoritesAllFolders - i
                }, last = i == 9)
            }
        }
    }

    @Composable
    private fun SyncIndicator() {
        val syncing = FavoritesStore.syncing ?: return
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ActivityIndicator(size = 16.dp)
            Txt("${syncing.shortLabel} ${FavoritesStore.syncedCount}", style = Type.caption1, color = AppTheme.colors.secondaryLabel)
        }
    }
}

// MARK: - History

class HistoryScreen : Screen(), TabRoot {
    private val list = LazyListState()
    private val grid = LazyStaggeredGridState()
    private val taps = TabTaps(scope)

    override fun tabTapped() {
        val cards = AppSettings.libraryStyle == LibraryStyle.Cards
        taps.tapped(
            if (cards) grid.firstVisibleItemIndex else list.firstVisibleItemIndex,
            if (cards) grid.firstVisibleItemScrollOffset else list.firstVisibleItemScrollOffset,
            scrollTo = { index, offset -> if (cards) grid.animateScrollToItem(index, offset) else list.animateScrollToItem(index, offset) },
            // History is on the device: read again from the database.
            refresh = { scope.launch { HistoryStore.reloadFromDatabase() } },
        )
    }
    private var search by mutableStateOf("")
    private var site by mutableStateOf(AppSettings.site(AppSettings.historySite))

    override fun onAppear() {
        if (AppSettings.historySite == SiteChoice.Follow) site = AppSettings.site(SiteChoice.Follow)
    }

    @Composable
    override fun Content() {
        val overlays = LocalOverlays.current
        val historyError = HistoryStore.storageError
        val saveFailure = tr("Couldn't Save")
        LaunchedEffect(historyError) {
            if (historyError != null) {
                overlays.alert(saveFailure, historyError)
                HistoryStore.storageError = null
            }
        }
        val entries = HistoryStore.entries.filter { e -> site == null || e.site == site?.key }
        val text = search.trim()
        val filtered = if (text.isEmpty()) entries.toList() else entries.filter {
            it.title.contains(text, ignoreCase = true) || "${it.gid}" == text
        }
        val sections = filtered.groupBy { startOfDay(it.lastRead) }.toSortedMap(compareByDescending { it })
        val clearTitle = tr("Clear all history?")
        val clearLabel = tr("Clear History")
        val cards = AppSettings.libraryStyle == LibraryStyle.Cards
        ScreenScaffold(
            title = tr("History"),
            showTitle = {
                if (cards) grid.pastTitle() else list.firstVisibleItemIndex > 0 || list.firstVisibleItemScrollOffset > 60
            },
            trailing = {
                SiteFilterButton(site, { site = it })
                val clearAnchor = rememberAnchor()
                GlassCircleButton(
                    Icons.Trash, tr("Clear"),
                    onClick = { overlays.confirm(clearAnchor.bounds, clearTitle, clearLabel) { HistoryStore.clear() } },
                    modifier = Modifier.anchor(clearAnchor),
                    enabled = entries.isNotEmpty(),
                )
            },
        ) { padding ->
            if (cards) {
                Cards(padding, entries.isEmpty(), filtered, sections)
            } else {
                Rows(padding, entries.isEmpty(), filtered, sections)
            }
        }
    }

    @Composable
    private fun SearchBox(modifier: Modifier) {
        SearchField(search, { search = it }, tr("Search History"), onSubmit = {}, modifier = modifier, filled = true)
    }

    /** Cards like Favorites (in the list style), by day. */
    @Composable
    private fun Cards(padding: PaddingValues, empty: Boolean, filtered: List<HistoryEntry>, sections: Map<Long, List<HistoryEntry>>) {
        val byKey = filtered.associateBy { it.key }
        val deleteLabel = tr("Delete")
        CompositionLocalProvider(
            LocalCoverBadge provides { item -> ReadingProgress(byKey[item.key]) },
            LocalGalleryMenuItems provides { item ->
                listOf(MenuItem(deleteLabel, Icons.Trash, destructive = true) { HistoryStore.delete(item.key) })
            },
            LocalShowsSiteBadge provides (site == null && AppSettings.enabledSites.size > 1),
        ) {
            GalleryLayout(grid, padding) { style ->
                fullLine("title") { LargeTitle(tr("History"), inset = 4.dp) }
                fullLine("search") { SearchBox(Modifier.fillMaxWidth().padding(vertical = 6.dp)) }
                when {
                    empty -> fullLine("empty") { EmptyState(Icons.Clock, tr("No History"), tr("Galleries you open appear here.")) }
                    filtered.isEmpty() -> fullLine("noresults") { EmptyState(Icons.Search, tr("No Results")) }
                }
                sections.forEach { (day, dayEntries) ->
                    fullLine("day$day") { DayHeader(day, inset = 4.dp) }
                    galleryItems(dayEntries.map { it.summary }, style)
                }
            }
        }
    }

    /** Rows with the page read, by day; swipe left to delete. */
    @Composable
    private fun Rows(padding: PaddingValues, empty: Boolean, filtered: List<HistoryEntry>, sections: Map<Long, List<HistoryEntry>>) {
        val topSlack = with(LocalDensity.current) { 60.dp.roundToPx() }
        LazyColumn(
            Modifier
                .fillMaxSize()
                .hidesBarsOnScroll { list.firstVisibleItemIndex == 0 && list.firstVisibleItemScrollOffset < topSlack },
            state = list,
            contentPadding = PaddingValues(top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding()),
        ) {
            item("title") { LargeTitle(tr("History")) }
            item("search") { SearchBox(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) }
            when {
                empty -> item("empty") {
                    EmptyState(Icons.Clock, tr("No History"), tr("Galleries you open appear here."))
                }
                filtered.isEmpty() -> item("noresults") { EmptyState(Icons.Search, tr("No Results")) }
            }
            sections.forEach { (day, dayEntries) ->
                item("day$day") { DayHeader(day) }
                items(dayEntries, key = { it.key.storageKey }) { entry ->
                    SwipeToDelete(onDelete = { HistoryStore.delete(entry.key) }) {
                        CompositionLocalProvider(LocalShowsSiteBadge provides (site == null && AppSettings.enabledSites.size > 1)) {
                        GalleryRow(
                            entry.summary,
                            Modifier.background(AppTheme.colors.background).padding(horizontal = 16.dp),
                            subtitle = historySubtitle(entry),
                        )
                        }
                    }
                }
            }
        }
    }

    private fun startOfDay(time: Long): Long = Calendar.getInstance().apply {
        timeInMillis = time
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    @Composable
    private fun DayHeader(day: Long, inset: androidx.compose.ui.unit.Dp = 20.dp) {
        val today = startOfDay(System.currentTimeMillis())
        val title = when (day) {
            today -> tr("Today")
            today - 24L * 3600 * 1000 -> tr("Yesterday")
            else -> DateFormat.getDateInstance(DateFormat.FULL).format(Date(day))
        }
        Txt(
            title,
            Modifier.fillMaxWidth().padding(start = inset, end = 16.dp, top = 18.dp, bottom = 4.dp),
            style = Type.subheadline.copy(fontWeight = FontWeight.SemiBold),
            color = AppTheme.colors.secondaryLabel,
        )
    }

    @Composable
    private fun historySubtitle(entry: HistoryEntry): String {
        val time = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(entry.lastRead))
        if (entry.numPages <= 0) return time
        return tr("Page %lld of %lld · %@", entry.lastPage + 1, entry.numPages, time)
    }
}

/** How far a gallery has been read: a thin bar at the bottom of its cover. */
@Composable
private fun androidx.compose.foundation.layout.BoxScope.ReadingProgress(entry: HistoryEntry?) {
    if (entry == null || entry.numPages <= 1 || entry.lastPage <= 0) return
    val fraction = ((entry.lastPage + 1).toFloat() / entry.numPages).coerceIn(0f, 1f)
    Box(
        Modifier
            .align(Alignment.BottomCenter)
            .padding(start = 8.dp, end = 8.dp, bottom = 6.dp)
            .fillMaxWidth()
            .height(4.dp)
            .clip(RoundedCornerShape(2.dp))
            .background(Color.Black.copy(alpha = 0.35f)),
    ) {
        Box(Modifier.fillMaxWidth(fraction).fillMaxHeight().clip(RoundedCornerShape(2.dp)).background(Color.White))
    }
}

/** Swipe left to reveal Delete (iOS swipe action); a full swipe deletes. */
@Composable
fun SwipeToDelete(onDelete: () -> Unit, content: @Composable () -> Unit) {
    val density = LocalDensity.current
    val scope = com.atlanyuey.aenht.core.rememberSafeScope()
    val offset = remember { Animatable(0f) }
    val reveal = with(density) { 88.dp.toPx() }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val width = with(density) { maxWidth.toPx() }
        Box(
            Modifier
                .matchParentSize()
                .background(AppTheme.colors.red)
                .clickable { onDelete() },
            contentAlignment = Alignment.CenterEnd,
        ) {
            Txt(tr("Delete"), Modifier.padding(end = 20.dp), style = Type.body.copy(fontWeight = FontWeight.SemiBold), color = Color.White)
        }
        Box(
            Modifier
                .offset { IntOffset(offset.value.roundToInt(), 0) }
                .draggable(
                    orientation = Orientation.Horizontal,
                    state = rememberDraggableState { delta ->
                        scope.launch { offset.snapTo((offset.value + delta).coerceIn(-width, 0f)) }
                    },
                    onDragStopped = {
                        when {
                            offset.value < -width * 0.6f -> {
                                offset.animateTo(-width, spring(stiffness = 600f))
                                onDelete()
                            }
                            offset.value < -reveal / 2 -> offset.animateTo(-reveal, spring(stiffness = 500f))
                            else -> offset.animateTo(0f, spring(stiffness = 500f))
                        }
                    },
                ),
        ) { content() }
    }
}

private fun Modifier.matchParentSize() = this.then(Modifier.fillMaxWidth().height(androidx.compose.ui.unit.Dp.Unspecified))

// MARK: - Search

/** A search field's text and the search it started. */
class SearchState {
    /** The site searched (shown in the field; switched under the field with the results). */
    var site by mutableStateOf(AppSettings.searchStartSite())
    var text by mutableStateOf("")
    var query by mutableStateOf("")
    val suggestions = mutableStateListOf<TagSuggestion>()
    var submitCount by mutableStateOf(0)

    /** Searches for [term] (typed and submitted, or a recent search). */
    fun submit(term: String) {
        val trimmed = term.trim()
        if (trimmed.isEmpty()) return
        text = term
        suggestions.clear()
        query = trimmed
        submitCount++
    }

    /** Ends the search and clears the field. */
    fun reset() {
        text = ""
        query = ""
        suggestions.clear()
    }

    /** Replaces the word being typed with the tag. */
    fun complete(tag: TagSuggestion) {
        val parts = text.split(" ").toMutableList()
        if (parts.isNotEmpty()) parts.removeAt(parts.lastIndex)
        parts.add(if (site == Site.EHENTAI) com.atlanyuey.aenht.shared.eh.EhTag(tag.type, tag.name).query else tag.query)
        text = parts.joinToString(" ") + " "
        suggestions.clear()
    }
}

/** Recent searches, tag suggestions and results for a [SearchState]: the Home page searches in place. */
class SearchPanel(private val state: SearchState, private val scope: kotlinx.coroutines.CoroutineScope) {
    private var results: SearchResults? = null

    /** Results of the submitted query, if there is one. */
    @Composable
    fun results(): SearchResults? {
        val query = state.query
        if (query.isEmpty()) return null
        return results?.takeIf { it.query == query } ?: SearchResults(query, scope, state.site, state).also { results = it }
    }

    /** Tag suggestions while typing; recent searches on submit. */
    @Composable
    fun Effects() {
        LaunchedEffect(state.text) {
            val text = state.text
            val word = text.split(" ").lastOrNull() ?: ""
            if (word.length < 2 || word.contains(":") || text.endsWith(" ")) {
                state.suggestions.clear()
                return@LaunchedEffect
            }
            delay(250)
            val found = if (state.site == Site.EHENTAI) {
                TagStore.suggestEh(word).map { (ns, name) -> TagSuggestion(0, ns, name, null) }
            } else {
                // The local list first (also finds translated names); the site when it finds few.
                val local = TagStore.suggestNh(word)
                if (local.size >= 5) local else {
                    val known = local.map { it.name }.toSet()
                    local + runCatching { NHClient.suggestTags(word) }.getOrDefault(emptyList()).filter { it.name !in known }
                }
            }
            state.suggestions.clear()
            state.suggestions.addAll(found)
        }
        LaunchedEffect(state.text) { if (state.text.isEmpty()) state.query = "" }
        LaunchedEffect(state.submitCount) {
            val query = state.query
            if (query.isEmpty()) return@LaunchedEffect
            AppSettings.addRecentSearch(state.site, query)
        }
    }

    @Composable
    fun Body(padding: PaddingValues, largeTitle: String?) {
        val current = results()
        when {
            state.suggestions.isNotEmpty() -> Suggestions(padding)
            current != null -> current.Content(padding)
            else -> Recents(padding, largeTitle)
        }
    }

    @Composable
    private fun Suggestions(padding: PaddingValues) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = padding) {
            items(state.suggestions, key = { "${it.type}:${it.name}:${it.id}" }) { tag ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { state.complete(tag) }
                        .padding(horizontal = 20.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    AppIcon(Icons.Tag, null, tint = AppTheme.colors.secondaryLabel, size = 18.dp)
                    Column(Modifier.weight(1f)) {
                        Txt(tag.name, maxLines = 1)
                        TagStore.translate(tag.name, tag.type)?.let { Txt(it, style = Type.footnote, color = AppTheme.colors.secondaryLabel, maxLines = 1) }
                    }
                    Txt(tag.type.replaceFirstChar { it.uppercase() }, style = Type.subheadline, color = AppTheme.colors.secondaryLabel)
                }
            }
        }
    }

    @Composable
    private fun Recents(padding: PaddingValues, largeTitle: String?) {
        val overlays = LocalOverlays.current
        val deleteLabel = tr("Delete")
        val recents = AppSettings.recentSearches
        LazyColumn(Modifier.fillMaxSize(), contentPadding = padding) {
            largeTitle?.let { item { LargeTitle(it) } }
            if (!AppSettings.saveSearchHistory || recents.isEmpty()) {
                item {
                    EmptyState(
                        Icons.Search,
                        if (state.site == Site.EHENTAI) tr("Search E-Hentai") else tr("Search nhentai"),
                        if (state.site == Site.EHENTAI) tr("Search by title or use tags like artist:name$, or paste a gallery link.")
                        else tr("Search by title or use tags like artist:name, or enter a gallery number."),
                    )
                }
            } else {
                item {
                    SectionTitle(tr("Recent Searches")) {
                        TextButton(tr("Clear"), { AppSettings.clearRecentSearches() })
                    }
                }
                items(recents.toList(), key = { it }) { entry ->
                    // An entry is a search on a site (shown at its end): tapping it searches there.
                    val term = com.atlanyuey.aenht.shared.site.SearchHistory.query(entry)
                    val entrySite = com.atlanyuey.aenht.shared.site.SearchHistory.site(entry)
                    SwipeToDelete(onDelete = { AppSettings.removeRecentSearch(entry) }) {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .background(AppTheme.colors.background)
                                .combinedClickable(
                                    onClick = {
                                        if (entrySite != null && entrySite in AppSettings.enabledSites) state.site = entrySite
                                        state.submit(term)
                                    },
                                    onLongClick = {
                                        overlays.actionSheet(term, null, listOf(
                                            DialogButton(deleteLabel, ButtonRole.Destructive) { AppSettings.removeRecentSearch(entry) },
                                        ))
                                    },
                                )
                                .padding(horizontal = 20.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            AppIcon(Icons.Clock, null, tint = AppTheme.colors.secondaryLabel, size = 18.dp)
                            Txt(term, Modifier.weight(1f), maxLines = 1)
                            if (entrySite != null && AppSettings.enabledSites.size > 1) {
                                Txt(entrySite.badge, style = Type.caption1, color = AppTheme.colors.secondaryLabel)
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Search results for a query on both sites: the site it was typed for
 * first; switching site under the field converts the query to the other
 * site's syntax. A gallery number (nhentai) or a gallery link shows that
 * gallery above the results.
 */
class SearchResults(
    val query: String,
    private val scope: kotlinx.coroutines.CoroutineScope,
    startSite: Site,
    private val state: SearchState? = null,
) {
    var site by mutableStateOf(startSite)
        private set
    private val nhQuery: String
    private val ehQuery: String
    private val ehCategories: Set<com.atlanyuey.aenht.shared.eh.EhCategory>
    /** Terms of the query that the other site has no counterpart for. */
    private val dropped: Map<Site, List<String>>

    init {
        if (startSite == Site.EHENTAI) {
            val converted = QueryConverter.ehToNh(query)
            ehQuery = query
            ehCategories = emptySet()
            nhQuery = converted.query
            dropped = mapOf(Site.NHENTAI to converted.dropped)
        } else {
            val converted = QueryConverter.nhToEh(query) { TagStore.ehNamespaces(it) }
            nhQuery = query
            ehQuery = converted.query
            ehCategories = converted.categories
            dropped = mapOf(Site.EHENTAI to converted.dropped)
        }
    }

    private val link: GalleryLink? = SiteLinks.fromSearchText(query)
    /** A pasted link is opened, not searched for. */
    private val searches = link == null || link is GalleryLink.Nh

    val nhFeedState = GalleryFeed(scope, nhFeed { page ->
        val full = listOfNotNull(nhQuery, AppSettings.searchLanguage.queryTerm).joinToString(" ")
        NHClient.galleries(full, AppSettings.searchSort, page)
    })
    val ehFeedState = GalleryFeed(scope, ehFeed {
        val full = listOfNotNull(ehQuery, AppSettings.searchLanguage.queryTerm).joinToString(" ")
        EhListSource.Search(full, ehCategories)
    })
    val feed: GalleryFeed get() = if (site == Site.EHENTAI) ehFeedState else nhFeedState
    private val grid = LazyStaggeredGridState()
    private var pulling by mutableStateOf(false)

    private var match by mutableStateOf<GallerySummary?>(null)
    private var matchFailed by mutableStateOf(false)

    init {
        link?.let { l ->
            scope.launch {
                match = runCatching {
                    when (l) {
                        is GalleryLink.Nh -> GallerySummary.from(NHClient.gallery(l.id).asItem)
                        is GalleryLink.Eh -> EhClient.api.galleryData(listOf(l.gid to l.token)).firstOrNull()?.let(GallerySummary::from)
                        is GalleryLink.EhPage -> {
                            val token = EhClient.galleryToken(l.gid, l.pageToken, l.page)
                            EhClient.api.galleryData(listOf(l.gid to token)).firstOrNull()?.let(GallerySummary::from)
                        }
                    }
                }.getOrNull()
                matchFailed = match == null
            }
        }
    }

    fun switchTo(target: Site) {
        if (target == site) return
        site = target
        state?.site = target
        if (searches && !feed.hasLoaded) feed.reload()
    }

    @Composable
    fun Content(padding: PaddingValues) {
        LaunchedEffect(Unit) {
            if (searches) {
                if (!feed.hasLoaded) feed.reload()
                // Both sites at once when the setting says so (twice the requests).
                if (AppSettings.searchBothSites && AppSettings.enabledSites.size > 1) {
                    val other = if (site == Site.EHENTAI) nhFeedState else ehFeedState
                    if (!other.hasLoaded) other.reload()
                }
            }
            snapshotFlow { AppSettings.searchLanguage to AppSettings.searchSort }.drop(1).collect { if (searches) feed.reload() }
        }
        val current = feed
        LaunchedEffect(current.isLoading) { if (!current.isLoading) pulling = false }
        PullToRefresh(pulling, { pulling = true; current.reload() }, topInset = padding.calculateTopPadding()) {
            GalleryGrid(
                if (searches) current.items else emptyList(), grid, padding,
                onItemShown = { current.loadMoreIfNeeded(it) },
                header = {
                    // These lines are there from the start (the gallery with a
                    // placeholder while it loads): a lazy grid keeps its first
                    // visible item in place, so lines added above it later
                    // would open the results scrolled down.
                    if (link != null && !matchFailed) {
                        fullLine("matchTitle") {
                            val title = when (link) {
                                is GalleryLink.Nh -> tr("Gallery #%lld", link.id)
                                else -> tr("E-Hentai Gallery")
                            }
                            FeedTitle(title, link.site.label)
                        }
                        fullLine("match") {
                            val item = match
                            if (item != null) {
                                GalleryRow(item, Modifier.padding(horizontal = 4.dp))
                            } else {
                                Box(Modifier.fillMaxWidth().height(128.dp), contentAlignment = Alignment.Center) { ActivityIndicator() }
                            }
                        }
                    }
                    if (searches && AppSettings.enabledSites.size > 1) {
                        fullLine("sites") { SiteSwitch() }
                    }
                    if (searches) {
                        fullLine("total") {
                            val total = current.total
                            Column(Modifier.fillMaxWidth().heightIn(min = 36.dp), verticalArrangement = Arrangement.Center) {
                                dropped[site]?.takeIf { it.isNotEmpty() }?.let { terms ->
                                    Txt(
                                        tr("Not available on %@: %@", site.label, terms.joinToString(" ")),
                                        Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
                                        style = Type.footnote,
                                        color = AppTheme.colors.orange,
                                    )
                                }
                                if (total != null && current.hasLoaded) {
                                    Txt(
                                        tr("%lld results", total),
                                        Modifier.padding(horizontal = 4.dp, vertical = 8.dp),
                                        style = Type.subheadline,
                                        color = AppTheme.colors.secondaryLabel,
                                    )
                                }
                            }
                        }
                    }
                },
                footer = { if (searches) feedFooter(current, L10n.string("No Results") to Icons.Search) },
            )
        }
    }

    /** nhentai | E-Hentai, under the search field and above the results. */
    @Composable
    private fun SiteSwitch() {
        val colors = AppTheme.colors
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 6.dp).clip(RoundedCornerShape(10.dp)).background(colors.tertiaryFill).padding(3.dp),
        ) {
            AppSettings.enabledSites.forEach { s ->
                val selected = s == site
                Box(
                    Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (selected) colors.background else Color.Transparent)
                        .clickable { switchTo(s) }
                        .padding(vertical = 7.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Txt(s.label, style = Type.subheadline.copy(fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal), maxLines = 1)
                }
            }
        }
    }
}

/** Results of a tag / artist search, pushed from a gallery. */
class SearchResultsScreen(private val query: String, private val title: String?, site: Site = Site.NHENTAI) : Screen() {
    private val results = SearchResults(query, scope, site)

    @Composable
    override fun Content() {
        ScreenScaffold(
            title = title ?: query,
            trailing = {
                FeedMenus(AppSettings.searchLanguage, AppSettings.searchSort, { AppSettings.searchLanguage = it }, { AppSettings.searchSort = it })
            },
        ) { padding -> results.Content(padding) }
    }
}
