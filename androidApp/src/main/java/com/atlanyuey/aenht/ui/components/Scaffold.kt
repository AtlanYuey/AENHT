package com.atlanyuey.aenht.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.getValue
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.atlanyuey.aenht.core.tr
import com.atlanyuey.aenht.ui.theme.AppTheme
import com.atlanyuey.aenht.ui.theme.Icons
import com.atlanyuey.aenht.ui.theme.Type
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop

/** Space taken by the floating tab bar at the bottom (0 when hidden). */
val LocalBottomBarHeight = compositionLocalOf { 0.dp }

const val NAV_BAR_HEIGHT = 54

/**
 * A screen with an iOS 26 navigation bar: glass buttons over the content,
 * a soft scroll edge under the bar and above the tab bar, and an inline
 * title (shown from the start, or once the large title scrolled away).
 */
@Composable
fun ScreenScaffold(
    title: String? = null,
    modifier: Modifier = Modifier,
    showTitle: () -> Boolean = { true },
    background: Color = Color.Unspecified,
    showBack: Boolean = true,
    leading: (@Composable RowScope.() -> Unit)? = null,
    trailing: @Composable RowScope.() -> Unit = {},
    overlay: @Composable () -> Unit = {},
    /** Makes the inline title a menu (with a chevron), like iOS's title menu. */
    titleMenu: (() -> List<List<MenuItem>>)? = null,
    /**
     * The content is drawn into the glass bar's backdrop (blurred under it).
     * Off for a WebView: drawn a second time into that layer it flickers.
     */
    contentUnderGlass: Boolean = true,
    content: @Composable (PaddingValues) -> Unit,
) {
    val backdrop = rememberLayerBackdrop()
    val navigator = LocalNavigator.current
    val statusTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val bottomBar = LocalBottomBarHeight.current
    val barHeight = NAV_BAR_HEIGHT.dp
    val bottomInset = maxOf(bottomBar, navBottom)
    val padding = PaddingValues(top = statusTop + barHeight, bottom = bottomInset + 12.dp)
    val bg = if (background.isSpecified) background else AppTheme.colors.background

    Box(modifier.fillMaxSize()) {
        Box(
            Modifier
                .fillMaxSize()
                .layerBackdrop(backdrop)
                .background(bg),
        ) {
            if (contentUnderGlass) content(padding)
        }
        if (!contentUnderGlass) Box(Modifier.fillMaxSize()) { content(padding) }
        CompositionLocalProvider(LocalBackdrop provides backdrop) {
            SoftEdge(statusTop + barHeight + 18.dp, Modifier.align(Alignment.TopCenter))
            if (bottomInset > 0.dp) {
                SoftEdge(bottomInset + 18.dp, Modifier.align(Alignment.BottomCenter), top = false)
            }
            NavBar(
                title = title,
                showTitle = showTitle,
                leading = leading ?: if (showBack && navigator.canPop) {
                    { BackButton() }
                } else {
                    null
                },
                trailing = trailing,
                titleMenu = titleMenu,
            )
            overlay()
        }
    }
}

@Composable
fun BackButton() {
    val navigator = LocalNavigator.current
    val rtl = LocalLayoutDirection.current == androidx.compose.ui.unit.LayoutDirection.Rtl
    GlassCircleButton(
        icon = if (rtl) Icons.ChevronForward else Icons.ChevronBack,
        contentDescription = tr("Close"),
        onClick = { navigator.pop() },
    )
}

@Composable
private fun NavBar(
    title: String?,
    showTitle: () -> Boolean,
    leading: (@Composable RowScope.() -> Unit)?,
    trailing: @Composable RowScope.() -> Unit,
    titleMenu: (() -> List<List<MenuItem>>)? = null,
) {
    SubcomposeLayout(
        Modifier.fillMaxWidth().statusBarsPadding().height(NAV_BAR_HEIGHT.dp).padding(horizontal = 16.dp),
    ) { constraints ->
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val start = subcompose("leading") {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                leading?.invoke(this)
            }
        }.single().measure(loose)
        val end = subcompose("trailing") {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically, content = trailing)
        }.single().measure(loose.copy(maxWidth = (constraints.maxWidth - start.width).coerceAtLeast(0)))
        val gap = 8.dp.roundToPx()
        val left = start.width + if (start.width > 0) gap else 0
        val right = end.width + if (end.width > 0) gap else 0
        val middle = subcompose("title") {
            if (title != null) AnimatedVisibility(visible = showTitle(), enter = fadeIn(), exit = fadeOut()) {
                if (titleMenu == null) {
                    Txt(title, style = Type.headline, maxLines = 1, align = TextAlign.Center)
                } else {
                    val overlays = LocalOverlays.current
                    val anchor = rememberAnchor()
                    Row(
                        Modifier.anchor(anchor).clip(RoundedCornerShape(12.dp))
                            .clickable { overlays.menu(anchor.bounds, titleMenu(), null) }
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Txt(title, Modifier.weight(1f, fill = false), style = Type.headline, maxLines = 1)
                        AppIcon(Icons.ChevronDown, null, tint = AppTheme.colors.secondaryLabel, size = 12.dp)
                    }
                }
            }
        }.map { it.measure(loose.copy(maxWidth = (constraints.maxWidth - left - right).coerceAtLeast(0))) }
        layout(constraints.maxWidth, constraints.maxHeight) {
            start.placeRelative(0, (constraints.maxHeight - start.height) / 2)
            end.placeRelative(constraints.maxWidth - end.width, (constraints.maxHeight - end.height) / 2)
            middle.forEach {
                val maxX = (constraints.maxWidth - right - it.width).coerceAtLeast(left)
                val x = ((constraints.maxWidth - it.width) / 2).coerceIn(left, maxX)
                it.placeRelative(x, (constraints.maxHeight - it.height) / 2)
            }
        }
    }
}

/** The large title at the top of a tab's root screen. */
@Composable
fun LargeTitle(text: String, modifier: Modifier = Modifier, inset: Dp = 20.dp) {
    Txt(
        text,
        modifier.padding(start = inset, end = inset, top = 2.dp, bottom = 8.dp),
        style = Type.largeTitle,
        maxLines = 1,
    )
}

/** Section header (iOS grouped list style, without the uppercase). */
@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier, trailing: @Composable RowScope.() -> Unit = {}) {
    Row(
        modifier.fillMaxWidth().padding(start = 20.dp, end = 16.dp, top = 22.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Txt(text, Modifier.weight(1f), style = Type.title3, maxLines = 1)
        trailing()
    }
}

/** Vertical space for a list's end, above the tab bar. */
@Composable
fun bottomInset(extra: Dp = 0.dp): Dp {
    val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    return maxOf(LocalBottomBarHeight.current, navBottom) + extra
}
