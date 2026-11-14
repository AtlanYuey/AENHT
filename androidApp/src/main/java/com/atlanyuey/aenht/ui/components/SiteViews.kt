package com.atlanyuey.aenht.ui.components

import com.atlanyuey.aenht.core.label
import com.atlanyuey.aenht.core.shortLabel
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.atlanyuey.aenht.core.AppSettings
import com.atlanyuey.aenht.core.tr
import com.atlanyuey.aenht.shared.site.Site
import com.atlanyuey.aenht.ui.theme.AppTheme
import com.atlanyuey.aenht.ui.theme.Icons
import com.atlanyuey.aenht.ui.theme.Type

/** "nhentai", "E-Hentai", or "All" for both. */
@Composable
fun siteTitle(site: Site?): String = when (site) {
    null -> tr("All")
    else -> site.label
}

/**
 * The site filter of a screen that lists both sites (Favorites, History,
 * Downloads): a small capsule in the navigation bar with a menu. Hidden
 * when only one site is in use.
 */
@Composable
fun SiteFilterButton(selected: Site?, onSelect: (Site?) -> Unit, allowAll: Boolean = true) {
    val sites = AppSettings.enabledSites
    if (sites.size < 2) return
    val overlays = LocalOverlays.current
    val anchor = rememberAnchor()
    val title = tr("Site")
    val options: List<Site?> = (if (allowAll) listOf<Site?>(null) else emptyList()) + sites
    val labels = options.map { siteTitle(it) }
    GlassCapsuleButton(
        onClick = {
            overlays.menu(anchor.bounds, listOf(options.mapIndexed { i, site ->
                MenuItem(labels[i], checked = site == selected) { onSelect(site) }
            }), title)
        },
        modifier = Modifier.anchor(anchor).widthIn(max = 150.dp),
        horizontalPadding = 14.dp,
    ) {
        Txt(selected?.shortLabel ?: tr("All"), style = Type.subheadline.copy(fontWeight = FontWeight.SemiBold), maxLines = 1)
        AppIcon(Icons.ChevronDown, null, tint = AppTheme.colors.secondaryLabel, size = 12.dp)
    }
}
