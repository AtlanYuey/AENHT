package com.atlanyuey.aenht.ui.components

import androidx.compose.animation.core.VisibilityThreshold
import com.atlanyuey.aenht.core.privacyText
import com.atlanyuey.aenht.core.label
import com.atlanyuey.aenht.core.shortLabel
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.staggeredgrid.LazyStaggeredGridScope
import androidx.compose.foundation.lazy.staggeredgrid.LazyStaggeredGridState
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridItemSpan
import androidx.compose.foundation.lazy.staggeredgrid.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.size.Dimension
import coil3.size.Size
import com.atlanyuey.aenht.core.AppSettings
import com.atlanyuey.aenht.core.Downloads
import com.atlanyuey.aenht.core.GalleryFeed
import com.atlanyuey.aenht.core.GalleryItem
import com.atlanyuey.aenht.shared.site.GallerySummary
import com.atlanyuey.aenht.core.EhClient
import com.atlanyuey.aenht.screens.EhGalleryScreen
import com.atlanyuey.aenht.shared.site.Site
import com.atlanyuey.aenht.shared.site.SiteLinks
import com.atlanyuey.aenht.shared.site.GalleryLink
import com.atlanyuey.aenht.core.GalleryLanguage
import com.atlanyuey.aenht.core.GalleryListStyle
import com.atlanyuey.aenht.core.NHHost
import com.atlanyuey.aenht.core.TagName
import com.atlanyuey.aenht.core.TagStore
import com.atlanyuey.aenht.core.tr
import com.atlanyuey.aenht.screens.GalleryScreen
import com.atlanyuey.aenht.ui.theme.AppTheme
import com.atlanyuey.aenht.ui.theme.Icons
import com.atlanyuey.aenht.ui.theme.Type
import com.kyant.shapes.Capsule

// MARK: - Images

/** An image from the network (disk cached), decoded at [targetWidth]. */
@Composable
fun RemoteImage(
    url: Any?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
    targetWidth: Dp? = 400.dp,
    blur: Boolean = false,
    /** Where to load the image from when [url] fails (asked only then). */
    fallback: (() -> Any?)? = null,
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val placeholder = AppTheme.colors.quaternaryFill
    var failed by remember(url) { mutableStateOf(false) }
    // Couldn't be loaded (offline): loaded again when the network comes back.
    var errored by remember(url) { mutableStateOf(false) }
    var attempt by remember(url) { mutableIntStateOf(0) }
    val restored = com.atlanyuey.aenht.core.Connectivity.restored
    LaunchedEffect(restored) {
        if (errored) {
            errored = false
            attempt++
        }
    }
    val data = if (failed) fallback?.invoke() ?: url else url
    val request = ImageRequest.Builder(context)
        .data(data)
        .apply {
            targetWidth?.let { size(Size(Dimension(with(density) { it.roundToPx() }), Dimension.Undefined)) }
        }
        .build()
    // A new image node per attempt: the request is made again.
    androidx.compose.runtime.key(attempt) {
        AsyncImage(
            model = request,
            contentDescription = null,
            contentScale = contentScale,
            onError = {
                if (!failed && fallback != null) failed = true else errored = true
            },
            modifier = modifier
                .background(placeholder)
                .then(if (blur || com.atlanyuey.aenht.core.Privacy.blursImages) Modifier.blur(if (blur) 18.dp else 30.dp) else Modifier),
        )
    }
}

@Composable
fun LanguageBadge(language: GalleryLanguage, modifier: Modifier = Modifier) = CoverBadge(language.badge, modifier)

/** A small label on a cover (language, site); [tint] colors it (E-Hentai category). */
@Composable
fun CoverBadge(text: String, modifier: Modifier = Modifier, tint: Color? = null) {
    Txt(
        text,
        modifier
            .clip(Capsule())
            .background(tint?.copy(alpha = 0.85f) ?: Color.Black.copy(alpha = 0.45f))
            .padding(horizontal = 6.dp, vertical = 2.dp),
        style = Type.caption2.copy(fontWeight = FontWeight.Bold),
        color = Color.White,
    )
}

/** A gallery cover with rounded corners and the language badge. */
@Composable
fun CoverView(
    item: GallerySummary,
    modifier: Modifier = Modifier,
    aspectRatio: Float? = null,
    cornerRadius: Dp = 12.dp,
    targetWidth: Dp = 360.dp,
    overlay: @Composable (Modifier) -> Unit = {},
) {
    val shape = RoundedCornerShape(cornerRadius)
    Box(
        modifier
            .fillMaxWidth()
            .aspectRatio(aspectRatio ?: item.aspectRatio.coerceIn(0.4f, 2f))
            .clip(shape)
            .border(0.5.dp, AppTheme.colors.label.copy(alpha = 0.08f), shape),
    ) {
        RemoteImage(item.thumbnail, Modifier.fillMaxSize(), targetWidth = targetWidth, blur = AppSettings.blurCovers) {
            // Without the network, a downloaded gallery shows its first page.
            Downloads.localFile(item.key, 0)
        }
        // The badge is colored by the category on both sites (nhentai's
        // category tag, in E-Hentai's category colors).
        val category = item.category ?: item.nhTagIds.firstNotNullOfOrNull { id ->
            com.atlanyuey.aenht.core.TagStore.tag(id)?.takeIf { it.type == "category" }
                ?.let { com.atlanyuey.aenht.shared.eh.EhCategory.fromLabel(it.name) }
        }
        val categoryTint = category?.let { Color(it.color) }
        val language = item.languageBadge ?: item.category?.let { "" }
        if (!language.isNullOrEmpty()) {
            CoverBadge(language, Modifier.padding(6.dp).align(Alignment.TopStart), tint = categoryTint)
        } else if (categoryTint != null) {
            Box(Modifier.padding(8.dp).align(Alignment.TopStart).size(10.dp).clip(Capsule()).background(categoryTint))
        }
        val selection = LocalGallerySelection.current
        if (LocalShowsSiteBadge.current && selection == null) {
            CoverBadge(item.site.shortLabel, Modifier.padding(6.dp).align(Alignment.TopEnd))
        }
        overlay(Modifier.align(Alignment.BottomCenter))
        LocalCoverBadge.current?.invoke(this, item)
        if (selection != null) SelectionMark(item in selection)
    }
}

/** Over a cover while choosing: a check in a circle (filled when chosen), the cover dimmed when not. */
@Composable
private fun BoxScope.SelectionMark(chosen: Boolean) {
    // Chosen or not, it changes smoothly.
    val dim by androidx.compose.animation.core.animateFloatAsState(if (chosen) 0f else 0.18f, label = "dim")
    Box(Modifier.matchParentSize().background(Color.Black.copy(alpha = dim)))
    Box(
        Modifier
            .align(Alignment.TopEnd)
            .padding(6.dp)
            .size(24.dp)
            .clip(androidx.compose.foundation.shape.CircleShape)
            .background(if (chosen) AppTheme.tint else Color.Black.copy(alpha = 0.3f))
            .border(1.5.dp, Color.White, androidx.compose.foundation.shape.CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        androidx.compose.animation.AnimatedVisibility(chosen, enter = androidx.compose.animation.scaleIn() + androidx.compose.animation.fadeIn(), exit = androidx.compose.animation.scaleOut() + androidx.compose.animation.fadeOut()) {
            AppIcon(Icons.Check, null, tint = Color.White, size = 14.dp)
        }
    }
}

/** Drawn over a gallery's cover in cards and rows (download or reading progress). */
val LocalCoverBadge = compositionLocalOf<(@Composable BoxScope.(GallerySummary) -> Unit)?> { null }

/** Lists that mix both sites mark each cover with NH / EH (top right). */
val LocalShowsSiteBadge = compositionLocalOf { false }

/** More items for a gallery's long-press menu (Delete in History and Downloads). */
val LocalGalleryMenuItems = compositionLocalOf<((GallerySummary) -> List<MenuItem>)?> { null }

/**
 * Galleries chosen in a list (Favorites, History: Select). While it's
 * given, a tap on a card or row chooses it instead of opening it, and its
 * cover shows whether it's chosen.
 */
class GallerySelection {
    /** The chosen galleries' storage keys, in the order chosen. */
    val keys = androidx.compose.runtime.mutableStateListOf<String>()

    operator fun contains(item: GallerySummary) = item.key.storageKey in keys

    fun toggle(item: GallerySummary) {
        val key = item.key.storageKey
        if (!keys.remove(key)) keys.add(key)
    }

    /** All of [items] chosen, or (when they all are) none. */
    fun toggleAll(items: List<GallerySummary>) {
        val all = items.map { it.key.storageKey }
        if (all.isNotEmpty() && keys.containsAll(all)) keys.clear() else { keys.clear(); keys.addAll(all.distinct()) }
    }
}

/** The list's selection while choosing (see [GallerySelection]); null otherwise. */
val LocalGallerySelection = compositionLocalOf<GallerySelection?> { null }

/** Starts choosing with this gallery chosen ("Select" in the long-press menu); null where the list can't. */
val LocalGallerySelect = compositionLocalOf<((GallerySummary) -> Unit)?> { null }

// MARK: - Cards

fun galleryLink(id: Int) = NHHost.gallery(id)

/** The web address of a gallery of either site. */
fun galleryLink(item: GallerySummary): String = when (item.site) {
    Site.NHENTAI -> NHHost.gallery(item.key.id.toInt())
    Site.EHENTAI -> SiteLinks.url(GalleryLink.Eh(item.key.id, item.key.token.orEmpty(), EhClient.api.isExHentai))
}

/** nhentai list item for a card (the nhentai gallery screen's preview). */
fun GallerySummary.nhItem() = GalleryItem(
    id = key.id.toInt(), mediaId = mediaId, englishTitle = title,
    thumbnail = thumbnail, thumbnailWidth = thumbWidth, thumbnailHeight = thumbHeight,
    numPages = pages, tagIds = nhTagIds,
)

/** Opens a gallery of either site. */
fun galleryScreen(item: GallerySummary): Screen = when (item.site) {
    Site.NHENTAI -> GalleryScreen(item.key.id.toInt(), item.nhItem())
    Site.EHENTAI -> EhGalleryScreen(item.key.id, item.key.token.orEmpty(), item)
}

fun Context.shareLink(url: String, title: String? = null) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, url)
        title?.let { putExtra(Intent.EXTRA_SUBJECT, it) }
    }
    com.atlanyuey.aenht.core.startSafely(this, Intent.createChooser(intent, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

fun Context.copyText(text: String) {
    val clipboard = getSystemService(ClipboardManager::class.java)
    clipboard.setPrimaryClip(ClipData.newPlainText("link", text))
}

/** Share / Copy Link, shown when a gallery is long-pressed. */
fun galleryMenu(context: Context, item: GallerySummary, share: String, copy: String) = listOf(
    listOf(
        MenuItem(share, Icons.Share) { context.shareLink(galleryLink(item), item.title) },
        MenuItem(copy, Icons.Link) { context.copyText(galleryLink(item)) },
    ),
)

/** Tapping opens the gallery; a long press shows the gallery menu. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun Modifier.galleryClick(item: GallerySummary, spot: CoverSpot? = null): Modifier {
    val navigator = LocalNavigator.current
    val overlays = LocalOverlays.current
    val context = LocalContext.current
    val anchor = rememberAnchor()
    val share = tr("Share")
    val copy = tr("Copy Link")
    val selectLabel = tr("Select")
    val extra = LocalGalleryMenuItems.current
    val selection = LocalGallerySelection.current
    val select = LocalGallerySelect.current
    // Choosing: a tap (or a long press) chooses or unchooses.
    if (selection != null) {
        return this.anchor(anchor).combinedClickable(onClick = { selection.toggle(item) }, onLongClick = { selection.toggle(item) })
    }
    return this
        .anchor(anchor)
        .combinedClickable(
            onClick = {
                // Its cover flies to the page from this card.
                spot?.let { GalleryCovers.opened(item.key, it) }
                navigator.push(galleryScreen(item))
            },
            onLongClick = {
                val first = buildList {
                    if (select != null) add(MenuItem(selectLabel, Icons.CheckCircle) { select(item) })
                    extra?.invoke(item)?.let { addAll(it) }
                }
                overlays.menu(anchor.bounds, (if (first.isEmpty()) emptyList() else listOf(first)) + galleryMenu(context, item, share, copy))
            },
        )
}

/** Where this card's cover is (its page opens out of it); let go when the card goes. */
@Composable
private fun rememberCoverSpot(item: GallerySummary): CoverSpot {
    val spot = remember(item.key) { CoverSpot() }
    androidx.compose.runtime.DisposableEffect(spot) { onDispose { spot.attached = false } }
    return spot
}

/** Card used by the grid / waterfall styles. */
@Composable
fun GalleryCard(item: GallerySummary, style: GalleryListStyle, modifier: Modifier = Modifier) {
    val spot = rememberCoverSpot(item)
    Column(modifier.galleryClick(item, spot), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        when (style) {
            GalleryListStyle.Grid -> {
                CoverView(item, Modifier.galleryCover(item, spot), aspectRatio = 3f / 4f)
                val titleStyle = Type.footnote.copy(fontWeight = FontWeight.Medium)
                Txt(
                    GalleryItem.pretty(item.title),
                    Modifier.fillMaxWidth().heightIn(min = 34.dp).galleryTitle(item, spot, GalleryItem.pretty(item.title), titleStyle, 2).privacyText(),
                    style = titleStyle,
                    maxLines = 2,
                )
            }
            GalleryListStyle.Compact -> CoverView(item, Modifier.galleryCover(item, spot)) { m -> CompactOverlay(item, m) }
            else -> {
                CoverView(item, Modifier.galleryCover(item, spot))
                val titleStyle = Type.footnote.copy(fontWeight = FontWeight.Medium)
                Txt(GalleryItem.pretty(item.title), Modifier.galleryTitle(item, spot, GalleryItem.pretty(item.title), titleStyle, 2).privacyText(), style = titleStyle, maxLines = 2)
                CardFooter(item, overlay = false)
            }
        }
    }
}

/** Tags and title over the bottom of the cover (compact waterfall). */
@Composable
private fun CompactOverlay(item: GallerySummary, modifier: Modifier) {
    Column(
        modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.55f), Color.Black.copy(alpha = 0.8f))))
            .padding(start = 10.dp, end = 10.dp, top = 36.dp, bottom = 10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        CardFooter(item, overlay = true)
        Txt(GalleryItem.pretty(item.title), Modifier.privacyText(), style = Type.footnote.copy(fontWeight = FontWeight.SemiBold), color = Color.White, maxLines = 2)
    }
}

/** Under a card: its tags (when shown) and, on the right, the rating. */
@Composable
fun CardFooter(item: GallerySummary, overlay: Boolean) {
    val rating = item.rating ?: 0.0
    if (!AppSettings.showTags && rating <= 0) return
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.weight(1f)) { if (AppSettings.showTags) CardTags(item, overlay) }
        if (rating > 0) RatingBadge(rating, overlay)
    }
}

/** One star filled as far as the rating goes (of 5), and the rating. */
@Composable
fun RatingBadge(rating: Double, overlay: Boolean = false, size: Dp = 11.dp) {
    val color = Color(0xFFFFB400)
    val fill = (rating / 5).coerceIn(0.0, 1.0).toFloat()
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        Box(Modifier.size(size)) {
            AppIcon(Icons.Star, null, tint = color.copy(alpha = 0.28f), size = size)
            Box(Modifier.fillMaxSize().drawWithContent {
                clipRect(right = this.size.width * fill) { this@drawWithContent.drawContent() }
            }) { AppIcon(Icons.Star, null, tint = color, size = size) }
        }
        Txt(
            String.format(java.util.Locale.ROOT, "%.2f", rating),
            style = Type.caption2.copy(fontWeight = FontWeight.SemiBold),
            color = if (overlay) Color.White else AppTheme.colors.secondaryLabel,
            maxLines = 1,
        )
    }
}

/** The card's tags: nhentai tag ids or E-Hentai tag names. */
@Composable
fun CardTags(item: GallerySummary, overlay: Boolean) {
    if (item.site == Site.NHENTAI) CardTagLine(item.nhTagIds, overlay)
    // Translated like the gallery page's tags (Translate Tags).
    else CardTagNames(item.cardTags.take(8).map { TagStore.displayName(it, TagStore.ehNamespaces(it).firstOrNull() ?: "tag") }, overlay)
}

/** One line of tag chips; chips that don't fit are left out. */
@Composable
fun CardTagLine(ids: List<Int>, overlay: Boolean) {
    val tags: List<TagName> = TagStore.cardTags(ids)
    CardTagNames(tags.map { TagStore.displayName(it.name, it.type) }, overlay)
}

@Composable
fun CardTagNames(names: List<String>, overlay: Boolean) {
    if (names.isEmpty()) return
    val colors = AppTheme.colors
    FittingRow(spacing = 5.dp, modifier = Modifier.privacyText()) {
        names.forEach { name ->
            Txt(
                name,
                Modifier
                    .clip(Capsule())
                    .background(if (overlay) Color.White.copy(alpha = 0.2f) else colors.tertiaryFill)
                    .padding(horizontal = 7.dp, vertical = 3.dp),
                style = Type.caption2.copy(fontWeight = FontWeight.Medium),
                color = if (overlay) Color.White else colors.secondaryLabel,
                maxLines = 1,
            )
        }
    }
}

/** Lays children out in one row and leaves out the ones that don't fit. */
@Composable
fun FittingRow(spacing: Dp, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Layout(content, modifier.fillMaxWidth()) { measurables, constraints ->
        val gap = spacing.roundToPx()
        val placeables = measurables.map { it.measure(constraints.copy(minWidth = 0, maxWidth = Int.MAX_VALUE)) }
        var x = 0
        var height = 0
        val shown = placeables.takeWhile { p ->
            val next = if (x == 0) p.width else x + gap + p.width
            (next <= constraints.maxWidth).also { fits ->
                if (fits) {
                    x = next
                    height = maxOf(height, p.height)
                }
            }
        }
        layout(constraints.maxWidth, height) {
            var px = 0
            shown.forEach {
                it.place(px, 0)
                px += it.width + gap
            }
        }
    }
}

/** Row used by the list style and history. */
@Composable
fun GalleryRow(item: GallerySummary, modifier: Modifier = Modifier, subtitle: String? = null) {
    val spot = rememberCoverSpot(item)
    Row(
        modifier.fillMaxWidth().galleryClick(item, spot).padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        CoverView(item, Modifier.width(84.dp).galleryCover(item, spot), aspectRatio = 0.72f, cornerRadius = 10.dp, targetWidth = 200.dp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            val titleStyle = Type.subheadline.copy(fontWeight = FontWeight.SemiBold)
            Txt(GalleryItem.pretty(item.title), Modifier.galleryTitle(item, spot, GalleryItem.pretty(item.title), titleStyle, 3).privacyText(), style = titleStyle, maxLines = 3)
            when {
                subtitle != null -> Txt(subtitle, style = Type.footnote, color = AppTheme.colors.secondaryLabel)
                item.pages > 0 -> Txt(tr("%lld pages", item.pages), style = Type.footnote, color = AppTheme.colors.secondaryLabel)
            }
            if (item.site == Site.EHENTAI) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    item.category?.let { c ->
                        Txt(c.label, Modifier.clip(Capsule()).background(Color(c.color)).padding(horizontal = 7.dp, vertical = 2.dp),
                            style = Type.caption2.copy(fontWeight = FontWeight.SemiBold), color = Color.White)
                    }
                    item.rating?.takeIf { it > 0 }?.let { RatingStars(it, size = 12.dp) }
                }
                item.uploader?.let { Txt(it, style = Type.footnote, color = AppTheme.colors.tertiaryLabel, maxLines = 1) }
            }
        }
    }
}

// MARK: - Collections

/**
 * A row of covers cut off at [start] or [end] (where it meets the other half
 * of a wide page): the covers end in round corners like their own, as if
 * cut by a card. The titles under them ([imageHeight] on) are cut straight.
 */
fun Modifier.cutCards(
    start: Boolean,
    end: Boolean,
    imageHeight: androidx.compose.ui.unit.Dp,
    radius: androidx.compose.ui.unit.Dp = 12.dp,
): Modifier = this
    .graphicsLayer { compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.Offscreen }
    .drawWithContent {
        val h = imageHeight.toPx().coerceAtMost(size.height)
        val r = androidx.compose.ui.geometry.CornerRadius(radius.toPx())
        val none = androidx.compose.ui.geometry.CornerRadius.Zero
        val shape = androidx.compose.ui.graphics.Path().apply {
            addRoundRect(
                androidx.compose.ui.geometry.RoundRect(
                    0f, 0f, size.width, h,
                    topLeftCornerRadius = if (start) r else none,
                    topRightCornerRadius = if (end) r else none,
                    bottomRightCornerRadius = if (end) r else none,
                    bottomLeftCornerRadius = if (start) r else none,
                ),
            )
            addRect(androidx.compose.ui.geometry.Rect(0f, h, size.width, size.height))
        }
        clipPath(shape) { this@drawWithContent.drawContent() }
    }

/**
 * The window is wide enough for two halves side by side (a tablet held
 * sideways, not a phone or a narrow window): Home's two pinned rows, a
 * gallery page's details and pages.
 */
@Composable
fun isWidePage(): Boolean {
    val configuration = LocalConfiguration.current
    return configuration.screenWidthDp >= 960 && configuration.screenWidthDp > configuration.screenHeightDp
}

/** What the right half of a gallery page shows, or null: one column, as on a phone (Me › Tablet Layout). */
@Composable
fun tabletDetailContent(): com.atlanyuey.aenht.core.TabletDetailContent? =
    if (com.atlanyuey.aenht.core.AppSettings.tabletDetail && isWidePage()) com.atlanyuey.aenht.core.AppSettings.tabletDetailContent else null

/** The pages pane of a gallery page: as wide as half the page, or narrowed to three columns (Pages Width). */
@Composable
fun Modifier.pagesPaneWidth(scope: androidx.compose.foundation.layout.RowScope): Modifier {
    if (!com.atlanyuey.aenht.core.AppSettings.tabletNarrowPages) return with(scope) { weight(1f) }
    // Three columns of thumbnails as wide as they are in half of the page.
    val page = LocalConfiguration.current.screenWidthDp.toFloat()
    val inset = 16f
    val spacing = 10f
    val available = page / 2 - inset * 2
    val count = maxOf(3f, kotlin.math.floor((available + spacing) / (96f + spacing)))
    val tile = minOf(160f, (available - (count - 1) * spacing) / count)
    return width((tile * 3 + spacing * 2 + inset * 2).dp)
}

/**
 * Number of columns: the count chosen in Card Size for a phone or a tablet
 * in the orientation (a narrow window shows fewer rather than cards
 * narrower than 90 dp). Rows of the list style fill the width instead.
 */
@Composable
fun galleryColumns(width: Dp, style: GalleryListStyle = AppSettings.listStyle, spacing: Dp = 12.dp): Int {
    val configuration = LocalConfiguration.current
    val landscape = configuration.screenWidthDp > configuration.screenHeightDp
    val tablet = configuration.smallestScreenWidthDp >= 600
    val available = (width - 32.dp).value
    if (style == GalleryListStyle.List) {
        return if (available <= 0f) 1 else maxOf(1, kotlin.math.ceil(available / (style.preferredCardWidth + spacing.value)).toInt())
    }
    val chosen = AppSettings.columns(tablet, landscape)
    if (available <= 0f) return chosen
    return minOf(chosen, maxOf(1, ((available + spacing.value) / (90f + spacing.value)).toInt()))
}

/**
 * A scrolling gallery list in the user's style (waterfall, compact
 * waterfall, grid or list) with full-width [header] content.
 */
@Composable
fun GalleryGrid(
    items: List<GallerySummary>,
    state: LazyStaggeredGridState,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
    onItemShown: (Int) -> Unit = {},
    header: LazyStaggeredGridScope.() -> Unit = {},
    footer: LazyStaggeredGridScope.() -> Unit = {},
) {
    GalleryLayout(state, contentPadding, modifier) { style ->
        header()
        galleryItems(items, style, onItemShown)
        footer()
    }
}

/**
 * The grid of [GalleryGrid] for screens that lay out their own items
 * (History, with a header for each day): [content] gets the list style.
 */
@Composable
fun GalleryLayout(
    state: LazyStaggeredGridState,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
    content: LazyStaggeredGridScope.(GalleryListStyle) -> Unit,
) {
    val style = AppSettings.listStyle
    val topSlack = with(androidx.compose.ui.platform.LocalDensity.current) { 60.dp.roundToPx() }
    BoxWithConstraints(modifier.fillMaxSize()) {
        val columns = galleryColumns(maxWidth, style)
        LazyVerticalStaggeredGrid(
            columns = StaggeredGridCells.Fixed(columns),
            state = state,
            contentPadding = PaddingValues(
                start = 16.dp, end = 16.dp,
                top = contentPadding.calculateTopPadding(),
                bottom = contentPadding.calculateBottomPadding(),
            ),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalItemSpacing = if (style == GalleryListStyle.Compact) 12.dp else 16.dp,
            modifier = Modifier
                .fillMaxSize()
                .hidesBarsOnScroll { state.firstVisibleItemIndex == 0 && state.firstVisibleItemScrollOffset < topSlack },
        ) {
            content(style)
        }
    }
}

/** Galleries in a [GalleryLayout]: cards, or rows in the list style. */
fun LazyStaggeredGridScope.galleryItems(items: List<GallerySummary>, style: GalleryListStyle, onItemShown: (Int) -> Unit = {}) {
    // One of each (a key used twice closes the app).
    itemsIndexed(items.distinctBy { it.key.storageKey }, key = { _, item -> item.key.storageKey }) { index, item ->
        androidx.compose.runtime.LaunchedEffect(item.key) { onItemShown(index) }
        // Cards come in softly, and slide into place when others go (deleted, filtered).
        Box(Modifier.animateItem(fadeInSpec = listFadeIn, placementSpec = listPlacement, fadeOutSpec = listFadeOut)) {
            if (style == GalleryListStyle.List) GalleryRow(item) else GalleryCard(item, style)
        }
    }
}

/** A full-width item in a [GalleryGrid] (headers, footers). */
fun LazyStaggeredGridScope.fullLine(key: Any? = null, content: @Composable () -> Unit) {
    item(key = key, span = StaggeredGridItemSpan.FullLine) {
        Box(if (key != null) Modifier.animateItem(fadeInSpec = listFadeIn, placementSpec = listPlacement, fadeOutSpec = listFadeOut) else Modifier) { content() }
    }
}

/** How list items come, move and go (cards, rows, headers): soft, never a jump. */
val listFadeIn = androidx.compose.animation.core.tween<Float>(220)
val listFadeOut = androidx.compose.animation.core.tween<Float>(160)
val listPlacement = androidx.compose.animation.core.spring(
    dampingRatio = 1f,
    stiffness = androidx.compose.animation.core.Spring.StiffnessMediumLow,
    visibilityThreshold = androidx.compose.ui.unit.IntOffset.VisibilityThreshold,
)

/** Footer of an infinite list: spinner while loading, error with retry. */
fun LazyStaggeredGridScope.feedFooter(feed: GalleryFeed, empty: Pair<String, ImageVector>) {
    fullLine("footer") {
        when {
            !feed.hasLoaded || (feed.isLoading && feed.items.isEmpty()) ->
                Box(Modifier.fillMaxWidth().height(300.dp), contentAlignment = Alignment.Center) {
                    ActivityIndicator(size = 30.dp)
                }
            feed.error != null && feed.items.isEmpty() -> EmptyState(
                icon = Icons.Offline,
                title = tr("Couldn't Load"),
                message = feed.error,
                action = tr("Try Again") to { feed.reload() },
            )
            feed.items.isEmpty() && feed.skippedHidden -> EmptyState(
                icon = empty.second,
                title = empty.first,
                message = tr("Everything on these pages is hidden by your filters."),
                action = tr("Load More") to { feed.loadMore() },
            )
            feed.items.isEmpty() -> EmptyState(icon = empty.second, title = empty.first)
            feed.isLoading -> Box(Modifier.fillMaxWidth().padding(vertical = 24.dp), contentAlignment = Alignment.Center) {
                ActivityIndicator()
            }
            feed.error != null -> Column(
                Modifier.fillMaxWidth().padding(vertical = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Txt(feed.error ?: "", style = Type.footnote, color = AppTheme.colors.secondaryLabel, align = TextAlign.Center)
                GlassCapsuleButton({ feed.loadMore() }, height = 36.dp) { Txt(tr("Retry"), style = Type.subheadline) }
            }
            feed.skippedHidden -> Box(Modifier.fillMaxWidth().padding(vertical = 24.dp), contentAlignment = Alignment.Center) {
                GlassCapsuleButton({ feed.loadMore() }, height = 36.dp) { Txt(tr("Load More"), style = Type.subheadline) }
            }
            else -> Spacer(Modifier.height(8.dp))
        }
    }
}

/** iOS ContentUnavailableView: icon, title, message and an action. */
@Composable
fun EmptyState(
    icon: ImageVector,
    title: String,
    message: String? = null,
    modifier: Modifier = Modifier,
    action: Pair<String, () -> Unit>? = null,
) {
    Column(
        modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 60.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        AppIcon(icon, null, tint = AppTheme.colors.secondaryLabel, size = 46.dp)
        Spacer(Modifier.size(2.dp))
        Txt(title, style = Type.title3.copy(fontWeight = FontWeight.Bold), align = TextAlign.Center)
        message?.let { Txt(it, style = Type.subheadline, color = AppTheme.colors.secondaryLabel, align = TextAlign.Center) }
        action?.let { (label, onClick) ->
            Spacer(Modifier.size(6.dp))
            GlassCapsuleButton(onClick, tint = AppTheme.tint, height = 40.dp) {
                Txt(label, style = Type.subheadline.copy(fontWeight = FontWeight.SemiBold), color = Color.White)
            }
        }
    }
}

/** A tag capsule (gallery page). */
@Composable
fun TagChip(name: String, count: Int?, onClick: () -> Unit) {
    val colors = AppTheme.colors
    Row(
        Modifier
            .clip(Capsule())
            .background(colors.tertiaryFill)
            .combinedClickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Txt(name, Modifier.privacyText(), style = Type.subheadline, maxLines = 1)
        if (count != null && count > 0) {
            Txt(compactCount(count), style = Type.caption1, color = colors.secondaryLabel, maxLines = 1)
        }
    }
}

/** 1234 -> 1.2K (locale aware compact notation). */
fun compactCount(value: Int): String {
    val formatter = android.icu.text.CompactDecimalFormat.getInstance(
        java.util.Locale.getDefault(),
        android.icu.text.CompactDecimalFormat.CompactStyle.SHORT,
    )
    return formatter.format(value.toLong())
}

/** 0–5 stars (half steps), E-Hentai's rating. */
@Composable
fun RatingStars(rating: Double, size: Dp = 14.dp, color: Color = Color(0xFFFFB400)) {
    Row(horizontalArrangement = Arrangement.spacedBy(1.dp)) {
        for (i in 1..5) {
            val fill = (rating - (i - 1)).coerceIn(0.0, 1.0)
            Box(Modifier.size(size)) {
                AppIcon(Icons.Star, null, tint = color.copy(alpha = 0.25f), size = size)
                if (fill > 0) {
                    Box(Modifier.fillMaxSize().drawWithContent {
                        clipRect(right = this.size.width * fill.toFloat()) { this@drawWithContent.drawContent() }
                    }) { AppIcon(Icons.Star, null, tint = color, size = size) }
                }
            }
        }
    }
}
