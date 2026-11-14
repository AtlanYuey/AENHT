package com.atlanyuey.aenht.ui

import androidx.compose.ui.draw.blur
import kotlinx.coroutines.launch

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.ui.BiasAlignment
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.layout.statusBarsPadding
import com.atlanyuey.aenht.ui.components.BarsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import android.content.ClipboardManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.atlanyuey.aenht.core.AppSettings
import com.atlanyuey.aenht.core.label
import com.atlanyuey.aenht.core.HandTracker
import com.atlanyuey.aenht.core.TabBarPosition
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import com.atlanyuey.aenht.core.tr
import com.atlanyuey.aenht.screens.FavoritesScreen
import com.atlanyuey.aenht.screens.GalleryScreen
import com.atlanyuey.aenht.screens.HistoryScreen
import com.atlanyuey.aenht.screens.HomeScreen
import com.atlanyuey.aenht.screens.MoreScreen
import com.atlanyuey.aenht.ui.components.LocalBackdrop
import com.atlanyuey.aenht.ui.components.ButtonRole
import com.atlanyuey.aenht.ui.components.DialogButton
import com.atlanyuey.aenht.ui.components.GlassCircleButton
import com.atlanyuey.aenht.ui.components.LocalBottomBarHeight
import com.atlanyuey.aenht.ui.components.LocalOverlays
import com.atlanyuey.aenht.ui.components.NavHost
import com.atlanyuey.aenht.ui.components.Navigator
import com.atlanyuey.aenht.ui.components.OverlayController
import com.atlanyuey.aenht.ui.components.OverlayHost
import com.atlanyuey.aenht.ui.components.AppIcon
import com.atlanyuey.aenht.ui.components.Txt
import com.atlanyuey.aenht.ui.glass.LiquidBottomTab
import com.atlanyuey.aenht.ui.glass.LiquidBottomTabs
import com.atlanyuey.aenht.ui.theme.AppTheme
import com.atlanyuey.aenht.ui.theme.Icons
import com.atlanyuey.aenht.ui.theme.Type
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop

private class TabInfo(val title: String, val icon: ImageVector)

/** Navigation stacks of the tabs; they live as long as the activity. */
class Tabs {
    val home = Navigator(HomeScreen())
    val favorites = Navigator(FavoritesScreen())
    val history = Navigator(HistoryScreen())
    val more = Navigator(MoreScreen())
    val all = listOf(home, favorites, history, more)
}


/** Tabs with the iOS 26 floating glass tab bar. Search is on the Home page. */
@Composable
fun RootView(
    linkedGallery: androidx.compose.runtime.MutableState<com.atlanyuey.aenht.shared.site.GalleryLink?>,
    tabs: Tabs = remember { Tabs() },
    initialTab: Int = 0,
    overlays: OverlayController = remember { OverlayController() },
) {
    var selected by rememberSaveable { mutableIntStateOf(initialTab.coerceIn(0, 3)) }
    var lastTab by rememberSaveable { mutableIntStateOf(initialTab.coerceIn(0, 3)) }
    val holder = rememberSaveableStateHolder()
    val backdrop = rememberLayerBackdrop()
    val navigator = tabs.all[selected]
    val showTabBar = !navigator.top.hidesTabBar
    // Large screens: the bar sits left, center or right (automatically on
    // the side of the hand in use); phones keep it centered.
    val configuration = androidx.compose.ui.platform.LocalConfiguration.current
    val large = configuration.smallestScreenWidthDp >= 600
    // Where the bar is; touches on it don't move it.
    var barBounds by remember { mutableStateOf(Rect.Zero) }
    // Scrolling a list down slides the tab bar away.
    val barsHidden = BarsState.hiding
    val tabsAway by animateFloatAsState(if (barsHidden) 1f else 0f, label = "tabsAway")
    LaunchedEffect(selected) { BarsState.show() }
    val tracksHand by rememberUpdatedState(large && showTabBar && !barsHidden && AppSettings.followHand)
    val position = if (AppSettings.followHand) {
        when (HandTracker.side) {
            HandTracker.Side.Leading -> TabBarPosition.Leading
            HandTracker.Side.Center -> TabBarPosition.Center
            HandTracker.Side.Trailing -> TabBarPosition.Trailing
        }
    } else {
        AppSettings.tabBarPosition
    }
    // The bar slides across at the follow speed.
    val bias by animateFloatAsState(
        when {
            !large -> 0f
            position == TabBarPosition.Leading -> -1f
            position == TabBarPosition.Trailing -> 1f
            else -> 0f
        },
        tween(AppSettings.followSpeed.durationMillis, easing = FastOutSlowInEasing),
        label = "barPosition",
    )
    val barAlignment = BiasAlignment(bias, 1f)

    val items = listOf(
        // Home is named after the site it shows.
        TabInfo(AppSettings.currentSite.label, Icons.Home),
        TabInfo(tr("Favorites"), Icons.Heart),
        TabInfo(tr("History"), Icons.Clock),
        TabInfo(tr("Me"), Icons.PersonCircleOutline),
    )

    fun select(index: Int) {
        if (index == selected) {
            val navigator = tabs.all[index]
            val home = navigator.stack.first() as? HomeScreen
            if (home != null && !navigator.canPop) home.tabTapped() else navigator.popToRoot()
        } else {
            selected = index
            lastTab = index
            tabs.all[index].stack.first().onAppear()
        }
    }

    // Links opened from outside or copied (nhentai, E-Hentai, ExHentai).
    val linkScope = androidx.compose.runtime.rememberCoroutineScope()
    val openLink: (com.atlanyuey.aenht.shared.site.GalleryLink) -> Unit = { link ->
        when (link) {
            is com.atlanyuey.aenht.shared.site.GalleryLink.Nh -> tabs.all[selected].push(GalleryScreen(link.id, null))
            is com.atlanyuey.aenht.shared.site.GalleryLink.Eh ->
                tabs.all[selected].push(com.atlanyuey.aenht.screens.EhGalleryScreen(link.gid, link.token, null))
            is com.atlanyuey.aenht.shared.site.GalleryLink.EhPage -> linkScope.launch {
                runCatching { com.atlanyuey.aenht.core.EhClient.galleryToken(link.gid, link.pageToken, link.page) }.getOrNull()?.let { token ->
                    tabs.all[selected].push(com.atlanyuey.aenht.screens.EhGalleryScreen(link.gid, token, null))
                }
            }
        }
    }
    val linked = linkedGallery.value
    LaunchedEffect(linked) {
        if (linked != null) {
            openLink(linked)
            linkedGallery.value = null
        }
    }
    ClipboardLinks(overlays, openLink)

    CompositionLocalProvider(
        LocalOverlays provides overlays,
        LocalBottomBarHeight provides if (showTabBar) 86.dp else 0.dp,
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(AppTheme.colors.background)
                .pointerInput(Unit) {
                    // Which side the screen is touched on (tab bar position).
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            if (event.type == PointerEventType.Press && size.width > 0 && tracksHand) {
                                event.changes.firstOrNull()
                                    ?.takeUnless { barBounds.inflate(10.dp.toPx()).contains(it.position) }
                                    ?.let { HandTracker.record(it.position.x / size.width, AppSettings.followSpeed.touches) }
                            }
                        }
                    }
                },
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .layerBackdrop(backdrop)
                    // In the background: the pages blurred, the tab bar left sharp.
                    .then(if (com.atlanyuey.aenht.core.Privacy.blursContent) Modifier.blur(36.dp) else Modifier),
            ) {
                holder.SaveableStateProvider("tab$selected") {
                    NavHost(navigator)
                }
            }
            AnimatedVisibility(
                visible = showTabBar,
                modifier = Modifier.align(barAlignment),
                enter = fadeIn() + slideInVertically { it / 2 },
                exit = fadeOut() + slideOutVertically { it / 2 },
            ) {
                // The tabs only (search is on the Home page). Scrolling a list
                // down slides them away; their space doesn't change.
                LiquidBottomTabs(
                    selectedTabIndex = { lastTab },
                    onTabSelected = { select(it) },
                    backdrop = backdrop,
                    tabsCount = items.size,
                    modifier = Modifier
                        .navigationBarsPadding()
                        .padding(start = 16.dp, end = 16.dp, bottom = 8.dp)
                        .then(if (large) Modifier.width(366.dp) else Modifier.widthIn(max = 420.dp))
                        .onGloballyPositioned { barBounds = it.boundsInRoot() }
                        .away(tabsAway),
                ) {
                    items.forEachIndexed { index, item ->
                        LiquidBottomTab({ select(index) }) {
                            AppIcon(item.icon, null, size = 24.dp)
                            Txt(item.title, style = Type.caption2.copy(fontWeight = FontWeight.Medium), maxLines = 1)
                        }
                    }
                }
            }
            OverlayHost(overlays, backdrop)
        }
    }
}

/** Offers to open a gallery link (nhentai, E-Hentai, ExHentai) found on the clipboard, once per link. */
@Composable
private fun ClipboardLinks(overlays: OverlayController, open: (com.atlanyuey.aenht.shared.site.GalleryLink) -> Unit) {
    val context = LocalContext.current
    val title = tr("Open Gallery?")
    val openLabel = tr("Open")
    val cancel = tr("Cancel")
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        if (!AppSettings.detectClipboard) return@LifecycleEventEffect
        val clipboard = context.getSystemService(ClipboardManager::class.java)
        val text = clipboard.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString() ?: return@LifecycleEventEffect
        val link = com.atlanyuey.aenht.shared.site.SiteLinks.find(text) ?: return@LifecycleEventEffect
        if (link.site !in AppSettings.enabledSites) return@LifecycleEventEffect
        val id = com.atlanyuey.aenht.shared.site.SiteLinks.url(link)
        val prefs = context.getSharedPreferences("settings", android.content.Context.MODE_PRIVATE)
        if (prefs.getString("lastClipboardLink", null) == id) return@LifecycleEventEffect
        prefs.edit().putString("lastClipboardLink", id).apply()
        val message = when (link) {
            is com.atlanyuey.aenht.shared.site.GalleryLink.Nh ->
                com.atlanyuey.aenht.core.L10n.string("The clipboard contains a link to gallery #%lld.", link.id)
            is com.atlanyuey.aenht.shared.site.GalleryLink.Eh ->
                com.atlanyuey.aenht.core.L10n.string("The clipboard contains a link to an E-Hentai gallery (%lld).", link.gid)
            is com.atlanyuey.aenht.shared.site.GalleryLink.EhPage ->
                com.atlanyuey.aenht.core.L10n.string("The clipboard contains a link to an E-Hentai gallery (%lld).", link.gid)
        }
        overlays.alert(
            title,
            message,
            listOf(DialogButton(cancel, ButtonRole.Cancel), DialogButton(openLabel, ButtonRole.Preferred) { open(link) }),
        )
    }
}

/** Slides a bar part down and out of sight ([fraction] 1 = gone). */
private fun Modifier.away(fraction: Float) = graphicsLayer {
    alpha = 1f - fraction
    translationY = fraction * (size.height + 40.dp.toPx())
}
