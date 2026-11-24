package com.atlanyuey.aenht.screens

import com.atlanyuey.aenht.core.AppSettings
import com.atlanyuey.aenht.core.TabletDetailContent
import com.atlanyuey.aenht.ui.components.pagesPaneWidth
import com.atlanyuey.aenht.ui.components.rememberAnchor
import com.atlanyuey.aenht.ui.components.anchor
import com.atlanyuey.aenht.ui.components.readerThumb
import com.atlanyuey.aenht.core.privacyText
import com.atlanyuey.aenht.core.Filters
import com.atlanyuey.aenht.core.EhPageRef
import com.atlanyuey.aenht.shared.eh.EhGalleryDetail
import com.atlanyuey.aenht.shared.site.GallerySummary
import com.atlanyuey.aenht.ui.components.galleryLink
import com.atlanyuey.aenht.ui.components.TranslateLine

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.atlanyuey.aenht.core.GalleryTag
import android.text.format.DateUtils
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.atlanyuey.aenht.core.DownloadRecord
import com.atlanyuey.aenht.core.Downloads
import com.atlanyuey.aenht.core.GalleryComment
import com.atlanyuey.aenht.core.GalleryDetail
import com.atlanyuey.aenht.core.GalleryItem
import com.atlanyuey.aenht.core.HistoryStore
import com.atlanyuey.aenht.core.NHClient
import com.atlanyuey.aenht.core.Session
import com.atlanyuey.aenht.core.TagGroup
import com.atlanyuey.aenht.core.TagStore
import com.atlanyuey.aenht.core.tr
import com.atlanyuey.aenht.ui.components.ActivityIndicator
import com.atlanyuey.aenht.ui.components.AppIcon
import com.atlanyuey.aenht.ui.components.ButtonRole
import com.atlanyuey.aenht.ui.components.CoverView
import com.atlanyuey.aenht.ui.components.DialogButton
import com.atlanyuey.aenht.ui.components.EmptyState
import com.atlanyuey.aenht.ui.components.GlassCircleButton
import com.atlanyuey.aenht.ui.components.LocalNavigator
import com.atlanyuey.aenht.ui.components.LocalOverlays
import com.atlanyuey.aenht.ui.components.NAV_BAR_HEIGHT
import com.atlanyuey.aenht.ui.components.ProminentButton
import com.atlanyuey.aenht.ui.components.PullToRefresh
import com.atlanyuey.aenht.ui.components.RemoteImage
import com.atlanyuey.aenht.ui.components.Screen
import com.atlanyuey.aenht.ui.components.ScreenScaffold
import com.atlanyuey.aenht.ui.components.SectionTitle
import com.atlanyuey.aenht.ui.components.TagChip
import com.atlanyuey.aenht.ui.components.TextButton
import com.atlanyuey.aenht.ui.components.Txt
import com.atlanyuey.aenht.ui.components.compactCount
import com.atlanyuey.aenht.ui.components.glass
import com.atlanyuey.aenht.ui.components.shareLink
import com.atlanyuey.aenht.ui.theme.AppTheme
import com.atlanyuey.aenht.ui.theme.Icons
import com.atlanyuey.aenht.ui.theme.Type
import com.kyant.shapes.Capsule
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// MARK: - Reader source

/** A page of the reader: its image ([url]: a link, a file, an E-Hentai page), and its small picture (the slider's preview). */
data class ReaderPage(val index: Int, val url: Any?, val aspectRatio: Float, val thumb: Any? = null)

/** What the reader shows: a gallery's pages (local files when downloaded). */
class ReaderSource(
    val id: Int,
    val title: String,
    val pages: List<ReaderPage>,
    val shareUrl: String,
    /** The gallery as a card (history). */
    val summary: GallerySummary,
) {
    /** The gallery on its site ("nh:123", "eh:123"): what its page thumbnails are found by. */
    val thumbsKey: String get() = "${summary.key.site.key}:${summary.key.id}"

    companion object {
        fun from(detail: GalleryDetail) = ReaderSource(
            id = detail.id,
            title = detail.displayTitle,
            pages = detail.pages.mapIndexed { index, page ->
                ReaderPage(index, Downloads.localFile(detail.id, index) ?: page.imageUrl, page.aspectRatio, page.thumbUrl)
            },
            shareUrl = detail.url,
            summary = GallerySummary.from(detail.asItem).copy(title = detail.displayTitle, pages = detail.numPages),
        )

        /** An E-Hentai gallery: page images are looked up as the reader gets to them. */
        fun from(detail: EhGalleryDetail, summary: GallerySummary) = ReaderSource(
            id = detail.gid.toInt(),
            title = detail.title,
            pages = (1..detail.pages).map { number ->
                ReaderPage(number - 1, Downloads.localFile(detail.key, number - 1) ?: EhPageRef(detail.gid, detail.token, number), 0.7f)
            },
            shareUrl = galleryLink(summary),
            summary = summary.copy(pages = detail.pages),
        )

        fun from(record: DownloadRecord) = ReaderSource(
            id = record.id,
            title = record.title,
            pages = (0 until record.totalPages).map { index ->
                val page = record.pages.getOrNull(index)
                val ratio = if (page != null && page.width > 0 && page.height > 0) page.width.toFloat() / page.height else 0.7f
                val remote: Any? = if (record.isEh) EhPageRef(record.id.toLong(), record.token.orEmpty(), index + 1) else page?.remote
                ReaderPage(index, Downloads.localFile(record.key, index) ?: remote, ratio)
            },
            shareUrl = galleryLink(record.summary),
            summary = record.summary,
        )
    }
}

// MARK: - Gallery page

class GalleryScreen(private val galleryId: Int, private val preview: GalleryItem?) : Screen() {
    private var detail by mutableStateOf<GalleryDetail?>(null)
    /** The gallery's JSON as loaded; a download keeps it for offline use. */
    private var json: kotlinx.serialization.json.JsonObject? = null
    private var error by mutableStateOf<String?>(null)
    private val comments = mutableStateListOf<GalleryComment>()
    private var isFavorited by mutableStateOf(false)
    private var numFavorites by mutableStateOf(0)
    private var toggling by mutableStateOf(false)
    private var pulling by mutableStateOf(false)
    private val list = LazyListState()

    private val item: GalleryItem? get() = preview ?: detail?.asItem

    /** Height of the cover in pixels; a collapsed title is cut to fit next to it. */
    private var coverHeight by mutableIntStateOf(0)
    private var titleExpanded by mutableStateOf(false)
    /** Other versions of the gallery (translations, the original), found by its title. */
    private var versions by mutableStateOf(emptyList<GallerySummary>())

    /**
     * Galleries with the same title once its bracketed parts are taken
     * away: its translations, the original, other editions.
     */
    private suspend fun loadVersions(d: GalleryDetail) {
        if (versions.isNotEmpty()) return
        val v = com.atlanyuey.aenht.shared.site.GalleryVersions
        val titles = listOfNotNull(d.englishTitle, d.japaneseTitle, d.prettyTitle)
        val found = mutableListOf<GallerySummary>()
        for (text in titles.map { v.searchText(it) }.filter { it.length >= 3 }.distinct()) {
            val page = runCatching { NHClient.galleries(text, com.atlanyuey.aenht.core.SearchSort.Popular, 1) }.getOrNull() ?: continue
            for (item in page.result) {
                if (item.id == galleryId || found.any { it.key.id == item.id.toLong() }) continue
                if (listOfNotNull(item.englishTitle, item.japaneseTitle).any { v.isVersion(titles, it) }) found += GallerySummary.from(item)
            }
        }
        versions = found
    }

    private suspend fun load() {
        error = null
        try {
            val data = NHClient.galleryJson(galleryId)
            val loaded = GalleryDetail.parse(data)
            val first = detail == null
            json = data
            detail = loaded
            isFavorited = loaded.isFavorited ?: false
            numFavorites = loaded.numFavorites
            Downloads.saveDetail(data, loaded)
            if (first) HistoryStore.record(loaded)
        } catch (e: Exception) {
            // Without the network, a downloaded gallery still has its page.
            val offline = if (detail == null) Downloads.offlineDetail(galleryId) else null
            if (offline != null) {
                detail = offline
                isFavorited = offline.isFavorited ?: false
                numFavorites = offline.numFavorites
                HistoryStore.record(offline)
            } else if (detail == null) {
                error = com.atlanyuey.aenht.core.errorText(e)
            }
        }
        detail?.let { scope.launch { loadVersions(it) } }
        runCatching { NHClient.comments(galleryId) }.getOrNull()?.let {
            comments.clear()
            comments.addAll(it)
        }
        pulling = false
    }

    private fun toggleFavorite(onError: (String) -> Unit) {
        if (toggling) return
        toggling = true
        val target = !isFavorited
        isFavorited = target
        scope.launch {
            try {
                val result = NHClient.setFavorite(galleryId, target)
                isFavorited = result.favorited ?: target
                numFavorites = result.numFavorites ?: numFavorites
                val card = item?.let { GallerySummary.from(it) }
                if (isFavorited) card?.let { com.atlanyuey.aenht.core.FavoritesStore.added(it) }
                else com.atlanyuey.aenht.core.FavoritesStore.removed(com.atlanyuey.aenht.shared.site.GalleryKey.nh(galleryId))
            } catch (e: Exception) {
                isFavorited = !target
                onError(com.atlanyuey.aenht.core.errorText(e))
            }
            toggling = false
        }
    }

    @Composable
    override fun Content() {
        val context = LocalContext.current
        LaunchedEffect(Unit) { if (detail == null) load() }
        ScreenScaffold(
            title = detail?.displayTitle ?: preview?.title,
            showTitle = { list.firstVisibleItemIndex > 1 },
            trailing = {
                detail?.let { d ->
                    GlassCircleButton(Icons.Share, tr("Share"), onClick = { context.shareLink(d.url, d.displayTitle) })
                }
            },
        ) { padding ->
            // A tablet held sideways (Me › Tablet Layout): the details on the left,
            // the pages or the related galleries and comments on the right.
            val tablet = com.atlanyuey.aenht.ui.components.tabletDetailContent()
            val wide = tablet != null
            PullToRefresh(pulling, { pulling = true; scope.launch { load() } }, topInset = padding.calculateTopPadding()) {
              Row(Modifier.fillMaxSize()) {
                LazyColumn(
                    Modifier.weight(1f).fillMaxHeight(),
                    state = list,
                    // Room under the comments, clear of the tab bar.
                    contentPadding = PaddingValues(bottom = padding.calculateBottomPadding() + 110.dp),
                ) {
                    item("header") { Header() }
                    val d = detail
                    if (d != null) {
                        item("actions") { Actions(d) }
                        item("info") { InfoStrip(d, numFavorites) }
                        item("tags") { TagsSection(d) }
                        // The pages, unless they have the right half to themselves.
                        if (tablet != TabletDetailContent.Pages) item("pages") { PreviewSection(d) }
                        // The rest, unless it is the right half.
                        if (tablet != TabletDetailContent.Related) related(d, cutStart = false, cutEnd = wide)
                    } else if (error != null) {
                        item("error") {
                            EmptyState(
                                Icons.Offline, tr("Couldn't Load"), error,
                                action = tr("Try Again") to { scope.launch { load() } },
                            )
                            com.atlanyuey.aenht.core.RetryWhenOnline { scope.launch { load() } }
                        }
                    } else {
                        item("loading") {
                            Box(Modifier.fillMaxWidth().padding(top = 60.dp), contentAlignment = Alignment.Center) {
                                ActivityIndicator(size = 30.dp)
                            }
                        }
                    }
                }
                val d = detail
                if (tablet == TabletDetailContent.Pages && d != null) {
                    // The pages run the full height, under the bars' soft edges like the details.
                    PagesGrid(
                        d,
                        PaddingValues(start = 12.dp, end = 16.dp, top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding() + 16.dp),
                        Modifier.pagesPaneWidth(this).fillMaxHeight(),
                        titled = true,
                        columns = if (AppSettings.tabletNarrowPages) 3 else null,
                    )
                } else if (tablet == TabletDetailContent.Related && d != null) {
                    LazyColumn(
                        Modifier.weight(1f).fillMaxHeight(),
                        contentPadding = PaddingValues(top = padding.calculateTopPadding() + 8.dp, bottom = padding.calculateBottomPadding() + 110.dp),
                    ) { related(d, cutStart = true, cutEnd = false) }
                }
              }
            }
        }
    }

    /** Its translations and other versions, galleries like it, the comments. */
    private fun androidx.compose.foundation.lazy.LazyListScope.related(d: GalleryDetail, cutStart: Boolean, cutEnd: Boolean) {
        if (versions.isNotEmpty()) {
            item("versionsTitle") { VersionsTitle() }
            item("versions") { Box(Modifier.padding(horizontal = 16.dp)) { PopularRow(versions, cutStart = cutStart, cutEnd = cutEnd) } }
        }
        val related = d.related.filter { r -> versions.none { it.key.id == r.id.toLong() } }
        if (related.isNotEmpty()) {
            item("relatedTitle") { SectionTitle(tr("More Like This")) }
            item("related") { Box(Modifier.padding(horizontal = 16.dp)) { PopularRow(related.map(GallerySummary::from), cutStart = cutStart, cutEnd = cutEnd) } }
        }
        item("comments") { CommentsPreview(d) }
    }

    @Composable
    private fun Header() {
        val navigator = LocalNavigator.current
        val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + NAV_BAR_HEIGHT.dp
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val wide = maxWidth > 600.dp
            // Blurred cover behind the header (a tiny image scaled up is
            // blurry on every Android version; RenderEffect smooths it more).
            Box(Modifier.matchParentSize()) {
                RemoteImage(
                    item?.thumbUrl,
                    Modifier.fillMaxSize().blur(30.dp),
                    contentScale = ContentScale.Crop,
                    targetWidth = 12.dp,
                )
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                0f to Color.Black.copy(alpha = 0.1f),
                                0.55f to AppTheme.colors.background.copy(alpha = 0.35f),
                                1f to AppTheme.colors.background,
                            ),
                        ),
                )
            }
            Row(
                Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = top + 12.dp),
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                Box(
                    Modifier
                        .width(if (wide) 200.dp else 132.dp)
                        .onSizeChanged { coverHeight = it.height }
                        .shadow(14.dp, RoundedCornerShape(14.dp)),
                ) {
                    val cover = item
                    if (cover != null) {
                        CoverView(GallerySummary.from(cover), cornerRadius = 14.dp, targetWidth = 500.dp)
                    } else {
                        Box(Modifier.fillMaxWidth().height(180.dp).clip(RoundedCornerShape(14.dp)).background(AppTheme.colors.quaternaryFill))
                    }
                }
                TitleBlock(wide)
            }
        }
    }

    /**
     * Title, subtitle and artists. Collapsed, they are no taller than the
     * cover: the artists and two lines of the subtitle stay, and the title
     * gets the lines that are left, ending with "…". Tapping the title shows
     * all of it; a long press copies it.
     */
    @Composable
    private fun androidx.compose.foundation.layout.RowScope.TitleBlock(wide: Boolean) {
        val navigator = LocalNavigator.current
        val context = LocalContext.current
        val density = LocalDensity.current
        val collapsed = !titleExpanded && coverHeight > 0
        // Translated, the translation takes the title's place; a tap switches
        // between it and the original.
        var titleTranslation by remember(detail?.id) { mutableStateOf<com.atlanyuey.aenht.shared.translate.Translation?>(null) }
        var showsOriginal by remember(detail?.id) { mutableStateOf(false) }
        val title = titleTranslation?.takeIf { !showsOriginal }?.text ?: detail?.displayTitle ?: preview?.title ?: ""
        val titleStyle = if (wide) Type.title2.copy(fontWeight = FontWeight.SemiBold) else Type.title3
        Column(
            Modifier
                .weight(1f)
                .then(if (collapsed) Modifier.heightIn(max = with(density) { coverHeight.toDp() }) else Modifier),
            verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.Bottom),
        ) {
            Column(
                Modifier
                    .then(if (collapsed) Modifier.weight(1f, fill = false) else Modifier)
                    .combinedClickable(
                        interactionSource = null,
                        indication = null,
                        onClick = { if (titleTranslation != null) showsOriginal = !showsOriginal else titleExpanded = !titleExpanded },
                        onLongClick = {
                            val text = listOfNotNull(detail?.displayTitle ?: preview?.title, detail?.subtitle).joinToString("\n")
                            context.getSystemService(ClipboardManager::class.java)
                                ?.setPrimaryClip(ClipData.newPlainText("title", text))
                        },
                    ),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (collapsed) {
                    // As many whole lines as fit in the space left.
                    BoxWithConstraints(Modifier.weight(1f, fill = false)) {
                        val lineHeight = with(density) { titleStyle.lineHeight.toPx() }
                        val lines = if (constraints.hasBoundedHeight && lineHeight > 0f) {
                            (constraints.maxHeight / lineHeight).toInt().coerceAtLeast(1)
                        } else {
                            Int.MAX_VALUE
                        }
                        Txt(title, Modifier.privacyText(), style = titleStyle, maxLines = lines)
                    }
                } else {
                    Txt(title, Modifier.privacyText(), style = titleStyle)
                }
                detail?.subtitle?.let {
                    Txt(it, Modifier.privacyText(), style = Type.subheadline, color = AppTheme.colors.secondaryLabel, maxLines = if (titleExpanded) Int.MAX_VALUE else 2)
                }
                detail?.let {
                    TranslateLine(it.japaneseTitle ?: it.displayTitle, com.atlanyuey.aenht.shared.translate.TranslationKind.TITLE,
                        onResult = { t -> titleTranslation = t; showsOriginal = false })
                }
            }
            detail?.let { d ->
                val artists = d.tags(TagGroup.Artist) + d.tags(TagGroup.Group)
                artists.firstOrNull()?.let { first ->
                    Txt(
                        artists.joinToString(", ") { it.name },
                        Modifier.clickable { navigator.push(SearchResultsScreen(first.query, first.name)) },
                        style = Type.subheadline.copy(fontWeight = FontWeight.SemiBold),
                        color = AppTheme.tint,
                        maxLines = if (titleExpanded) Int.MAX_VALUE else 1,
                    )
                }
            }
        }
    }

    @Composable
    private fun Actions(d: GalleryDetail) {
        val navigator = LocalNavigator.current
        val overlays = LocalOverlays.current
        val lastPage = HistoryStore.lastReadPage(d.id) ?: 0
        val deleteTitle = tr("Delete the downloaded pages?")
        val deleteLabel = tr("Delete Download")
        val errorTitle = tr("Couldn't Update")
        val record = Downloads.record(d.id)
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 22.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ProminentButton(
                text = if (lastPage > 0) tr("Resume · %lld", lastPage + 1) else tr("Read"),
                icon = if (lastPage > 0) Icons.Play else Icons.Book,
                onClick = { navigator.push(ReaderScreen(ReaderSource.from(d), lastPage)) },
                modifier = Modifier.weight(1f),
            )
            GlassCircleButton(
                icon = if (isFavorited) Icons.HeartFill else Icons.Heart,
                contentDescription = if (isFavorited) tr("Unfavorite") else tr("Favorite"),
                onClick = {
                    if (Session.isLoggedIn) {
                        toggleFavorite { message -> overlays.alert(errorTitle, message) }
                    } else {
                        navigator.push(LoginScreen())
                    }
                },
                size = 46.dp,
                iconColor = if (isFavorited) AppTheme.colors.pink else AppTheme.tint,
                enabled = !toggling,
            )
            when (record?.state) {
                DownloadRecord.FINISHED -> {
                    val anchor = rememberAnchor()
                    GlassCircleButton(
                        Icons.CheckCircle, tr("Downloaded"),
                        onClick = { overlays.confirm(anchor.bounds, deleteTitle, deleteLabel) { Downloads.delete(d.id) } },
                        modifier = Modifier.anchor(anchor),
                        size = 46.dp, iconColor = AppTheme.colors.green,
                    )
                }
                DownloadRecord.DOWNLOADING, DownloadRecord.QUEUED -> ProgressCircle(record.progress) {
                    GlassCircleButton(Icons.Pause, tr("Pause Download"), onClick = { Downloads.pause(d.id) }, size = 46.dp, iconColor = AppTheme.tint, iconSize = 18.dp)
                }
                DownloadRecord.PAUSED, DownloadRecord.FAILED -> ProgressCircle(record.progress) {
                    GlassCircleButton(Icons.Refresh, tr("Resume Download"), onClick = { Downloads.resume(d.id) }, size = 46.dp, iconColor = AppTheme.tint, iconSize = 18.dp)
                }
                else -> GlassCircleButton(Icons.ArrowDown, tr("Download"), onClick = { Downloads.start(d, json) }, size = 46.dp, iconColor = AppTheme.tint)
            }
        }
    }

    @Composable
    private fun ProgressCircle(progress: Float, content: @Composable () -> Unit) {
        val tint = AppTheme.tint
        Box(contentAlignment = Alignment.Center) {
            content()
            Canvas(Modifier.size(40.dp)) {
                drawArc(
                    tint, startAngle = -90f, sweepAngle = 360f * progress, useCenter = false,
                    style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round),
                )
            }
        }
    }

    @Composable
    private fun InfoStrip(d: GalleryDetail, favorites: Int) {
        val colors = AppTheme.colors
        val date = d.uploadDate?.let { SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(Date(it * 1000)) } ?: "-"
        Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 22.dp)) {
            Box(Modifier.fillMaxWidth().height(0.5.dp).background(colors.separator))
            Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                GalleryInfoItem(tr("Pages"), "${d.numPages}")
                GalleryInfoDivider()
                GalleryInfoItem(tr("Favorites"), compactCount(favorites), Icons.HeartFill)
                GalleryInfoDivider()
                GalleryInfoItem(tr("Uploaded"), date)
                GalleryInfoDivider()
                GalleryInfoItem(tr("ID"), "#${d.id}")
            }
            Box(Modifier.fillMaxWidth().height(0.5.dp).background(colors.separator))
        }
    }

    @Composable
    private fun TagsSection(d: GalleryDetail) {
        val navigator = LocalNavigator.current
        val order = listOf(TagGroup.Category, TagGroup.Language, TagGroup.Tag, TagGroup.Parody, TagGroup.Character, TagGroup.Artist, TagGroup.Group)
        val groups = order.filter { d.tags(it).isNotEmpty() }
        if (groups.isEmpty()) return
        Column(Modifier.fillMaxWidth().padding(top = 18.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            groups.forEach { group ->
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Txt(
                        tr(group.title),
                        Modifier.padding(horizontal = 16.dp),
                        style = Type.subheadline.copy(fontWeight = FontWeight.SemiBold),
                        color = AppTheme.colors.secondaryLabel,
                    )
                    TagRow(d.tags(group).map { it to TagStore.displayName(it.name, it.type) }, count = { it.count }) { tag ->
                        navigator.push(SearchResultsScreen(tag.query, tag.name))
                    }
                }
            }
        }
    }

    @Composable
    private fun PreviewSection(d: GalleryDetail) {
        val navigator = LocalNavigator.current
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val height = if (maxWidth > 600.dp) 220.dp else 160.dp
            Column {
                val rowState = androidx.compose.foundation.lazy.rememberLazyListState()
                // Tapped: the row goes to the first page; again: back to where it was.
                val jump = com.atlanyuey.aenht.ui.components.rememberRowJump(rowState)
                SectionTitle(tr("Pages"), Modifier.clickable(interactionSource = null, indication = null, onClick = jump)) {
                    if (d.pages.size > 1) TextButton(tr("See All"), { navigator.push(PagesScreen(d)) })
                }
                com.atlanyuey.aenht.ui.components.FollowReader("nh:${d.id}", rowState, d.pages.size)
                LazyRow(state = rowState, contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(d.pages.distinctBy { it.number }, key = { it.number }) { page ->
                        Column(
                            Modifier.clickable { navigator.push(ReaderScreen(ReaderSource.from(d), page.number - 1)) },
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            RemoteImage(
                                Downloads.localFile(d.id, page.number - 1) ?: page.thumbUrl,
                                Modifier
                                    .size(width = height * page.aspectRatio.coerceIn(0.5f, 1.5f), height = height)
                                    .readerThumb("nh:${d.id}", page.number - 1)
                                    .clip(RoundedCornerShape(8.dp)),
                                targetWidth = 240.dp,
                            )
                            Txt("${page.number}", style = Type.caption1, color = AppTheme.colors.secondaryLabel)
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun CommentsPreview(d: GalleryDetail) {
        val navigator = LocalNavigator.current
        Column {
            SectionTitle(tr("Comments")) {
                TextButton(
                    if (comments.isEmpty()) tr("Write") else tr("See All (%lld)", comments.size),
                    { navigator.push(CommentsScreen(d.id, comments.toList())) },
                )
            }
            if (comments.isEmpty()) {
                Txt(tr("No comments yet"), Modifier.padding(horizontal = 20.dp), style = Type.subheadline, color = AppTheme.colors.secondaryLabel)
            } else {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(comments.filter { !Filters.hidesComment(it.poster?.username ?: "", it.body) }.distinctBy { it.id }.take(10), key = { it.id }) { comment ->
                        Box(
                            Modifier
                                .width(300.dp)
                                .clip(RoundedCornerShape(18.dp))
                                .background(AppTheme.colors.quaternaryFill)
                                .padding(14.dp),
                        ) { CommentBubble(comment, maxLines = 5) }
                    }
                }
            }
        }
    }
}

// MARK: - All pages

class PagesScreen(private val detail: GalleryDetail) : Screen() {
    @Composable
    override fun Content() {
        val navigator = LocalNavigator.current
        ScreenScaffold(title = tr("Pages")) { padding ->
            PagesGrid(
                detail,
                PaddingValues(start = 16.dp, end = 16.dp, top = padding.calculateTopPadding() + 8.dp, bottom = padding.calculateBottomPadding()),
            )
        }
    }
}

/** Every page of a gallery in a grid ([titled]: under a "Pages" heading, as half of a wide gallery page). */
@Composable
fun PagesGrid(detail: GalleryDetail, contentPadding: PaddingValues, modifier: Modifier = Modifier, titled: Boolean = false, columns: Int? = null) {
    val navigator = LocalNavigator.current
    val state = androidx.compose.foundation.lazy.grid.rememberLazyGridState()
    // On the page the reader is on (the title is the first item).
    com.atlanyuey.aenht.ui.components.FollowReader("nh:${detail.id}", state, detail.pages.size) { page ->
        if (page in detail.pages.indices) page + (if (titled) 1 else 0) else null
    }
    LazyVerticalGrid(
        columns = columns?.let { GridCells.Fixed(it) } ?: GridCells.Adaptive(96.dp),
        modifier = modifier,
        state = state,
        contentPadding = contentPadding,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        if (titled) {
            item(key = "title", span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) {
                // The heading has the page margin of its own: the grid's is taken off.
                Box(Modifier.offset(x = (-16).dp)) { SectionTitle(tr("Pages")) }
            }
        }
        items(detail.pages.distinctBy { it.number }, key = { it.number }) { page ->
            Column(
                Modifier.clickable { navigator.push(ReaderScreen(ReaderSource.from(detail), page.number - 1)) },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                RemoteImage(
                    Downloads.localFile(detail.id, page.number - 1) ?: page.thumbUrl,
                    Modifier.fillMaxWidth().height(130.dp).readerThumb("nh:${detail.id}", page.number - 1).clip(RoundedCornerShape(8.dp)),
                    contentScale = ContentScale.Fit,
                    targetWidth = 240.dp,
                )
                Txt("${page.number}", style = Type.caption2, color = AppTheme.colors.secondaryLabel)
            }
        }
    }
}

// MARK: - Comments

@Composable
fun CommentBubble(comment: GalleryComment, maxLines: Int = Int.MAX_VALUE) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.size(34.dp).clip(CircleShape).background(AppTheme.colors.quaternaryFill), contentAlignment = Alignment.Center) {
            val avatar = comment.poster?.avatar
            if (avatar != null) {
                RemoteImage(avatar, Modifier.fillMaxSize(), targetWidth = 80.dp)
            } else {
                AppIcon(Icons.PersonCircle, null, tint = AppTheme.colors.secondaryLabel, size = 34.dp)
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Txt(
                    comment.poster?.username ?: tr("Anonymous"),
                    Modifier.weight(1f),
                    style = Type.subheadline.copy(fontWeight = FontWeight.SemiBold),
                    maxLines = 1,
                )
                Txt(
                    DateUtils.getRelativeTimeSpanString(comment.postDate * 1000, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString(),
                    style = Type.caption1,
                    color = AppTheme.colors.secondaryLabel,
                    maxLines = 1,
                )
            }
            Txt(comment.body, Modifier.privacyText(), style = Type.subheadline, maxLines = maxLines)
            if (maxLines == Int.MAX_VALUE) TranslateLine(comment.body, com.atlanyuey.aenht.shared.translate.TranslationKind.COMMENT)
        }
    }
}

class CommentsScreen(private val galleryId: Int, initial: List<GalleryComment>) : Screen() {
    private val comments = mutableStateListOf<GalleryComment>().apply { addAll(initial) }
    private var draft by mutableStateOf("")
    private var posting by mutableStateOf(false)
    private var pulling by mutableStateOf(false)

    /** The last page read and how many the site has (50 comments each). */
    private var page by mutableStateOf(1)
    private var pageCount by mutableStateOf(1)
    private var loadingMore by mutableStateOf(false)
    /** Why the comments couldn't be read (shown instead of "No Comments" when there are none to show). */
    private var error by mutableStateOf<String?>(null)

    /** Counts the reloads: a later page read for an earlier list isn't added to this one. */
    private var generation = 0
    private var moreJob: kotlinx.coroutines.Job? = null

    private suspend fun load() {
        val mine = ++generation
        moreJob?.cancel()
        moreJob = null
        loadingMore = false
        try {
            val first = NHClient.commentsPage(galleryId, 1)
            if (mine != generation) return
            comments.clear()
            comments.addAll(first.result)
            page = 1
            pageCount = first.numPages
            error = null
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            if (mine == generation) error = com.atlanyuey.aenht.core.errorText(e)
        } finally {
            if (mine == generation) pulling = false
        }
    }

    private fun loadMore() {
        if (loadingMore || page >= pageCount) return
        val mine = generation
        val wanted = page + 1
        loadingMore = true
        moreJob = scope.launch {
            try {
                val more = NHClient.commentsPage(galleryId, wanted)
                if (mine != generation) return@launch
                val known = comments.map { it.id }.toSet()
                comments.addAll(more.result.filter { it.id !in known })
                page = wanted
                pageCount = more.numPages
                error = null
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                if (mine == generation) error = com.atlanyuey.aenht.core.errorText(e)
            } finally {
                if (mine == generation) loadingMore = false
            }
        }
    }

    @Composable
    override fun Content() {
        val overlays = LocalOverlays.current
        val errorTitle = tr("Couldn't Post")
        LaunchedEffect(Unit) { load() }
        ScreenScaffold(
            title = tr("Comments"),
            overlay = {
                if (Session.isLoggedIn) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
                        CommentComposer(draft, { draft = it }, posting) { text ->
                            posting = true
                            scope.launch {
                                try {
                                    comments.add(0, NHClient.postComment(galleryId, text))
                                    draft = ""
                                } catch (e: Exception) {
                                    overlays.alert(errorTitle, com.atlanyuey.aenht.core.errorText(e))
                                }
                                posting = false
                            }
                        }
                    }
                }
            },
        ) { padding ->
            PullToRefresh(pulling, { pulling = true; scope.launch { load() } }, topInset = padding.calculateTopPadding()) {
                LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        start = 16.dp, end = 16.dp,
                        top = padding.calculateTopPadding() + 8.dp,
                        bottom = padding.calculateBottomPadding() + 80.dp,
                    ),
                    verticalArrangement = Arrangement.spacedBy(18.dp),
                ) {
                    if (comments.isEmpty()) item {
                        val failed = error
                        if (failed != null) {
                            EmptyState(Icons.Offline, tr("Couldn't Load"), message = failed, action = tr("Try Again") to { scope.launch { load() } })
                            com.atlanyuey.aenht.core.RetryWhenOnline { scope.launch { load() } }
                        } else {
                            EmptyState(Icons.Comment, tr("No Comments"))
                        }
                    }
                    items(comments.filter { !Filters.hidesComment(it.poster?.username ?: "", it.body) }.distinctBy { it.id }, key = { it.id }) { CommentBubble(it) }
                    // More pages: the next one loads when the end shows.
                    if (comments.isNotEmpty() && page < pageCount) item(key = "more") {
                        Box(Modifier.fillMaxWidth().padding(vertical = 12.dp), contentAlignment = Alignment.Center) {
                            if (error != null && !loadingMore) {
                                com.atlanyuey.aenht.ui.components.GlassCapsuleButton({ loadMore() }, height = 36.dp) { Txt(tr("Retry"), style = Type.subheadline) }
                                com.atlanyuey.aenht.core.RetryWhenOnline { loadMore() }
                            } else {
                                LaunchedEffect(page) { loadMore() }
                                ActivityIndicator()
                            }
                        }
                    }
                }
            }
        }
    }
}

/** The comment field at the bottom of a comments screen (both sites ask for at least 10 characters). */
@Composable
internal fun CommentComposer(draft: String, onDraft: (String) -> Unit, posting: Boolean, post: (String) -> Unit) {
    val colors = AppTheme.colors
    val ready = draft.trim().length >= 10 && !posting
    Row(
        Modifier
            .navigationBarsPadding()
            .imePadding()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            Modifier
                .weight(1f)
                .heightIn(min = 46.dp)
                .glass(RoundedCornerShape(23.dp))
                .padding(horizontal = 16.dp, vertical = 12.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            if (draft.isEmpty()) Txt(tr("Add a comment…"), style = Type.body, color = colors.secondaryLabel)
            BasicTextField(
                draft, onDraft,
                textStyle = Type.body.copy(color = colors.label),
                cursorBrush = SolidColor(AppTheme.tint),
                maxLines = 5,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (posting) {
            Box(Modifier.size(46.dp), contentAlignment = Alignment.Center) { ActivityIndicator() }
        } else {
            GlassCircleButton(
                Icons.ArrowUp, tr("Done"),
                onClick = { post(draft.trim()) },
                size = 46.dp,
                tint = if (ready) AppTheme.tint else Color.Unspecified,
                enabled = ready,
            )
        }
    }
}

/**
 * One line of tags that scrolls sideways. When they don't fit, a button at
 * the end of the line shows them all, wrapped onto as many lines as needed.
 */
@Composable
internal fun <T> TagRow(tags: List<Pair<T, String>>, count: (T) -> Int? = { null }, onTag: (T) -> Unit) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    // Width of the tags on one line, measured without the button so that
    // showing it can't change the answer.
    var tagsWidth by remember { mutableIntStateOf(0) }
    val density = LocalDensity.current
    val margin = 16.dp
    val buttonSize = 34.dp
    val fade = 18.dp
    val buttonSpace = margin + buttonSize + 8.dp
    val chips: @Composable () -> Unit = {
        tags.forEach { (tag, name) -> TagChip(name, count(tag)) { onTag(tag) } }
    }
    BoxWithConstraints(Modifier.fillMaxWidth().animateContentSize(spring(stiffness = Spring.StiffnessMediumLow))) {
        val overflows = tagsWidth > 0 && with(density) { tagsWidth.toDp() } + margin * 2 > maxWidth + 0.5.dp
        if (expanded) {
            TagFlow(
                Modifier.fillMaxWidth().padding(horizontal = margin),
                spacing = 8.dp,
                firstLineEnd = buttonSpace - margin,
                content = chips,
            )
        } else {
            Row(
                Modifier
                    .fillMaxWidth()
                    .then(if (overflows) Modifier.fadeBeforeButton(fade, buttonSpace) else Modifier)
                    .horizontalScroll(rememberScrollState())
                    .padding(start = margin, end = if (overflows) buttonSpace + fade else margin),
            ) {
                Row(Modifier.onSizeChanged { tagsWidth = it.width }, horizontalArrangement = Arrangement.spacedBy(8.dp)) { chips() }
            }
        }
        if (overflows || expanded) {
            val rotation by animateFloatAsState(if (expanded) 180f else 0f, label = "chevron")
            val label = tr(if (expanded) "Show Less" else "See All")
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(end = margin)
                    .size(buttonSize)
                    .clip(CircleShape)
                    .background(AppTheme.colors.tertiaryFill)
                    .clickable { expanded = !expanded }
                    .semantics { contentDescription = label },
                contentAlignment = Alignment.Center,
            ) {
                AppIcon(Icons.ChevronDown, null, Modifier.rotate(rotation), tint = AppTheme.colors.secondaryLabel, size = 14.dp)
            }
        }
    }
}

/** Fades content out just before the button at the trailing edge. */
private fun Modifier.fadeBeforeButton(fade: Dp, button: Dp) = this
    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
    .drawWithContent {
        drawContent()
        val width = size.width
        if (width <= 0f) return@drawWithContent
        val end = (width - button.toPx()).coerceAtLeast(0f)
        val start = (end - fade.toPx()).coerceAtLeast(0f)
        drawRect(
            Brush.horizontalGradient(
                0f to Color.Black,
                start / width to Color.Black,
                end / width to Color.Transparent,
                1f to Color.Transparent,
            ),
            blendMode = BlendMode.DstIn,
        )
    }

/** Wraps items onto lines, keeping [firstLineEnd] free at the end of the first line for a button. */
@Composable
private fun TagFlow(modifier: Modifier, spacing: Dp, firstLineEnd: Dp, content: @Composable () -> Unit) {
    Layout(content, modifier) { measurables, constraints ->
        val gap = spacing.roundToPx()
        val reserve = firstLineEnd.roundToPx()
        val width = constraints.maxWidth
        val placeables = measurables.map { it.measure(Constraints(maxWidth = (width - reserve).coerceAtLeast(0))) }
        val positions = ArrayList<IntOffset>(placeables.size)
        var x = 0
        var y = 0
        var lineHeight = 0
        var firstLine = true
        placeables.forEach { p ->
            val limit = if (firstLine) width - reserve else width
            if (x > 0 && x + p.width > limit) {
                y += lineHeight + gap
                x = 0
                lineHeight = 0
                firstLine = false
            }
            positions += IntOffset(x, y)
            x += p.width + gap
            lineHeight = maxOf(lineHeight, p.height)
        }
        layout(width, y + lineHeight) { placeables.forEachIndexed { i, p -> p.place(positions[i]) } }
    }
}


/** A cell of a gallery page's info strip (both sites). */
@Composable
internal fun androidx.compose.foundation.layout.RowScope.GalleryInfoItem(title: String, value: String, icon: ImageVector? = null) {
    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Txt(title, style = Type.caption1.copy(fontWeight = FontWeight.Medium), color = AppTheme.colors.secondaryLabel, maxLines = 1)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            icon?.let { AppIcon(it, null, size = 12.dp) }
            androidx.compose.foundation.text.BasicText(
                value,
                style = Type.headline.copy(color = AppTheme.colors.label),
                maxLines = 1,
                autoSize = androidx.compose.foundation.text.TextAutoSize.StepBased(minFontSize = 11.sp, maxFontSize = 17.sp),
            )
        }
    }
}

/** The line between cells of an info strip. */
@Composable
internal fun GalleryInfoDivider() {
    Box(Modifier.width(0.5.dp).height(34.dp).background(AppTheme.colors.separator))
}

/** The names of E-Hentai's tag namespaces, in the app's language. */
@Composable
internal fun ehNamespaceTitle(namespace: String): String = when (namespace) {
    "language" -> tr("Languages")
    "parody" -> tr("Parodies")
    "character" -> tr("Characters")
    "group" -> tr("Groups")
    "artist" -> tr("Artists")
    "cosplayer" -> tr("Cosplayers")
    "male" -> tr("Male")
    "female" -> tr("Female")
    "mixed" -> tr("Mixed")
    "other" -> tr("Other")
    "location" -> tr("Locations")
    "reclass" -> tr("Reclassified")
    "temp" -> tr("Temporary")
    else -> namespace.replaceFirstChar { it.uppercase() }
}
