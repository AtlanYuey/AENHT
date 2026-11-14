package com.atlanyuey.aenht.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.graphics.Bitmap
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.transformations
import coil3.size.Size
import coil3.transform.Transformation
import com.atlanyuey.aenht.core.*
import com.atlanyuey.aenht.shared.eh.EhComment
import com.atlanyuey.aenht.shared.eh.EhGalleryDetail
import com.atlanyuey.aenht.shared.eh.EhListSource
import com.atlanyuey.aenht.shared.eh.EhTag
import com.atlanyuey.aenht.shared.eh.EhThumbnail
import com.atlanyuey.aenht.shared.site.GallerySummary
import com.atlanyuey.aenht.ui.components.*
import com.atlanyuey.aenht.ui.theme.AppTheme
import com.atlanyuey.aenht.ui.theme.Icons
import com.atlanyuey.aenht.ui.theme.Type
import com.kyant.shapes.Capsule
import kotlinx.coroutines.launch

/** The card of a loaded E-Hentai gallery (history, reader, downloads). */
fun EhGalleryDetail.summary(preview: GallerySummary?) = GallerySummary(
    key = key,
    title = title,
    thumbnail = preview?.thumbnail ?: cover,
    thumbWidth = preview?.thumbWidth ?: 0,
    thumbHeight = preview?.thumbHeight ?: 0,
    pages = pages,
    languageBadge = GallerySummary.ehLanguageBadge(language?.substringBefore(' ')?.trim() ?: "japanese"),
    category = category,
    rating = rating,
    uploader = uploader,
    posted = posted,
    cardTags = tags.filterKeys { it != "language" }.values.flatten().map { it.name },
    favoriteFolder = favoriteFolder,
)

/** The user's favorite folder names, loaded once per session. */
object EhFolders {
    var names by mutableStateOf<List<String>>(List(10) { "Favorites $it" })
        private set
    private var loaded = false

    suspend fun load() {
        if (loaded || !EhSession.isLoggedIn) return
        runCatching { EhClient.favoriteFolderNames() }.getOrNull()?.let {
            names = it
            loaded = true
        }
    }

    /** E-Hentai's colors of the ten folders. */
    val colors = listOf(
        Color(0xFF000000), Color(0xFFFF0000), Color(0xFFFFAA00), Color(0xFFDDDD00), Color(0xFF008800),
        Color(0xFF99FF44), Color(0xFF44BBFF), Color(0xFF0000FF), Color(0xFF550088), Color(0xFFEE88EE),
    )
}

/** Cuts one tile out of a sprite sheet (E-Hentai's small thumbnails). */
class SpriteCrop(private val x: Int, private val width: Int, private val height: Int) : Transformation() {
    override val cacheKey = "sprite($x,$width,$height)"

    override suspend fun transform(input: Bitmap, size: Size): Bitmap {
        val left = x.coerceIn(0, maxOf(0, input.width - 1))
        val w = width.coerceAtMost(input.width - left).coerceAtLeast(1)
        val h = height.coerceAtMost(input.height).coerceAtLeast(1)
        return Bitmap.createBitmap(input, left, 0, w, h)
    }
}

/** A preview tile: a cut-out of a sprite sheet, or a whole image. */
@Composable
fun EhThumbnailImage(thumb: EhThumbnail, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val request = remember(thumb) {
        ImageRequest.Builder(context).data(thumb.imageUrl).apply {
            if (thumb.isSprite && thumb.width > 0) {
                size(Size.ORIGINAL)
                transformations(SpriteCrop(thumb.offsetX, thumb.width, thumb.height))
            }
        }.build()
    }
    AsyncImage(
        model = request,
        contentDescription = null,
        contentScale = ContentScale.Fit,
        modifier = modifier.background(AppTheme.colors.quaternaryFill),
    )
}

class EhGalleryScreen(private val gid: Long, private val token: String, private val preview: GallerySummary?) : Screen() {
    private var detail by mutableStateOf<EhGalleryDetail?>(null)
    private var error by mutableStateOf<String?>(null)
    private var folder by mutableStateOf<Int?>(null)
    private var rating by mutableStateOf(0.0)
    private var busy by mutableStateOf(false)
    private var pulling by mutableStateOf(false)
    private var titleExpanded by mutableStateOf(false)
    private val list = LazyListState()

    private val summary: GallerySummary?
        get() = detail?.summary(preview) ?: preview

    private suspend fun load() {
        error = null
        try {
            val loaded = EhClient.gallery(gid, token)
            val first = detail == null
            detail = loaded
            folder = loaded.favoriteFolder
            rating = loaded.rating
            EhPages.remember(gid, loaded.thumbnails)
            if (first) HistoryStore.record(loaded.summary(preview))
        } catch (e: Exception) {
            val offline = if (detail == null) Downloads.offlineEhDetail(com.atlanyuey.aenht.shared.site.GalleryKey(com.atlanyuey.aenht.shared.site.Site.EHENTAI, gid, token)) else null
            if (offline != null) {
                detail = offline
                folder = offline.favoriteFolder
                rating = offline.rating
                HistoryStore.record(offline.summary(preview))
            } else if (detail == null) {
                error = e.localizedMessage
            }
        }
        pulling = false
        EhFolders.load()
    }

    private fun setFolder(target: Int?, onError: (String) -> Unit) {
        if (busy) return
        val before = folder
        busy = true
        folder = target
        scope.launch {
            try {
                EhClient.setFavorite(gid, token, target)
                val card = summary
                if (target != null) card?.let { FavoritesStore.added(it, target) } else FavoritesStore.removed(com.atlanyuey.aenht.shared.site.GalleryKey(com.atlanyuey.aenht.shared.site.Site.EHENTAI, gid, token))
            } catch (e: Exception) {
                folder = before
                onError(e.localizedMessage ?: "")
            }
            busy = false
        }
    }

    private fun rate(stars: Double, onError: (String) -> Unit) {
        val d = detail ?: return
        scope.launch {
            try {
                rating = EhClient.rate(d, stars)
            } catch (e: Exception) {
                onError(e.localizedMessage ?: "")
            }
        }
    }

    @Composable
    override fun Content() {
        val context = LocalContext.current
        LaunchedEffect(Unit) { if (detail == null) load() }
        ScreenScaffold(
            title = detail?.title ?: preview?.title,
            showTitle = { list.firstVisibleItemIndex > 1 },
            trailing = {
                summary?.let { s -> GlassCircleButton(Icons.Share, tr("Share"), onClick = { context.shareLink(galleryLink(s), s.title) }) }
            },
        ) { padding ->
            PullToRefresh(pulling, { pulling = true; scope.launch { load() } }, topInset = padding.calculateTopPadding()) {
                LazyColumn(
                    Modifier.fillMaxSize(),
                    state = list,
                    contentPadding = PaddingValues(bottom = padding.calculateBottomPadding() + 24.dp),
                ) {
                    item("header") { Header() }
                    val d = detail
                    if (d != null) {
                        item("actions") { Actions(d) }
                        item("info") { Info(d) }
                        item("tags") { Tags(d) }
                        item("pages") { Previews(d) }
                        item("comments") { Comments(d) }
                    } else if (error != null) {
                        item("error") {
                            EmptyState(Icons.Offline, tr("Couldn't Load"), error, action = tr("Try Again") to { scope.launch { load() } })
                        }
                    } else {
                        item("loading") {
                            Box(Modifier.fillMaxWidth().padding(top = 60.dp), contentAlignment = Alignment.Center) { ActivityIndicator(size = 30.dp) }
                        }
                    }
                }
            }
        }
    }

    @OptIn(ExperimentalFoundationApi::class)
    @Composable
    private fun Header() {
        val navigator = LocalNavigator.current
        val context = LocalContext.current
        val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + NAV_BAR_HEIGHT.dp
        val card = summary
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val wide = maxWidth > 600.dp
            Box(Modifier.matchParentSize()) {
                RemoteImage(card?.thumbnail, Modifier.fillMaxSize().blur(30.dp), contentScale = ContentScale.Crop, targetWidth = 12.dp)
                Box(
                    Modifier.fillMaxSize().background(
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
                Box(Modifier.width(if (wide) 200.dp else 132.dp).shadow(14.dp, RoundedCornerShape(14.dp))) {
                    if (card != null) {
                        CoverView(card, cornerRadius = 14.dp, targetWidth = 500.dp)
                    } else {
                        Box(Modifier.fillMaxWidth().height(180.dp).clip(RoundedCornerShape(14.dp)).background(AppTheme.colors.quaternaryFill))
                    }
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    val title = detail?.title ?: preview?.title ?: ""
                    Txt(
                        title,
                        Modifier.combinedClickable(
                            interactionSource = null, indication = null,
                            onClick = { titleExpanded = !titleExpanded },
                            onLongClick = {
                                val text = listOfNotNull(detail?.title, detail?.japaneseTitle).joinToString("\n")
                                context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("title", text))
                            },
                        ),
                        style = if (wide) Type.title2.copy(fontWeight = FontWeight.SemiBold) else Type.title3,
                        maxLines = if (titleExpanded) Int.MAX_VALUE else 5,
                    )
                    detail?.japaneseTitle?.let {
                        Txt(it, style = Type.subheadline, color = AppTheme.colors.secondaryLabel, maxLines = if (titleExpanded) Int.MAX_VALUE else 2)
                    }
                    detail?.let { d ->
                        Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Box(Modifier.weight(1f)) { TranslateLine(d.japaneseTitle ?: d.title, com.atlanyuey.aenht.shared.translate.TranslationKind.TITLE) }
                            RatingButton(d)
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        (detail?.category ?: preview?.category)?.let { c ->
                            Txt(
                                c.label,
                                Modifier.clip(Capsule()).background(Color(c.color)).padding(horizontal = 8.dp, vertical = 3.dp),
                                style = Type.caption1.copy(fontWeight = FontWeight.SemiBold), color = Color.White,
                            )
                        }
                        (detail?.uploader ?: preview?.uploader)?.let { uploader ->
                            Txt(
                                uploader,
                                Modifier.clickable { navigator.push(EhSearchScreen(EhListSource.Search("uploader:\"$uploader\""), uploader)) },
                                style = Type.subheadline.copy(fontWeight = FontWeight.SemiBold),
                                color = AppTheme.tint, maxLines = 1,
                            )
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun Actions(d: EhGalleryDetail) {
        val navigator = LocalNavigator.current
        val overlays = LocalOverlays.current
        val card = d.summary(preview)
        val lastPage = HistoryStore.lastReadPage(d.key) ?: 0
        val errorTitle = tr("Couldn't Update")
        val deleteTitle = tr("Delete the downloaded pages?")
        val deleteLabel = tr("Delete Download")
        val removeLabel = tr("Remove from Favorites")
        val folderTitle = tr("Favorites")
        val anchor = rememberAnchor()
        val record = Downloads.record(d.key)
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 22.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ProminentButton(
                text = if (lastPage > 0) tr("Resume · %lld", lastPage + 1) else tr("Read"),
                icon = if (lastPage > 0) Icons.Play else Icons.Book,
                onClick = { navigator.push(ReaderScreen(ReaderSource.from(d, card), lastPage)) },
                modifier = Modifier.weight(1f),
            )
            val current = folder
            GlassCircleButton(
                icon = if (current != null) Icons.HeartFill else Icons.Heart,
                contentDescription = if (current != null) tr("Unfavorite") else tr("Favorite"),
                onClick = {
                    if (!EhSession.isLoggedIn) {
                        navigator.push(EhLoginScreen())
                        return@GlassCircleButton
                    }
                    // Picking a folder adds or moves; picking the current one removes it.
                    val items = EhFolders.names.mapIndexed { i, name ->
                        MenuItem(name, Icons.Folder, checked = i == current) {
                            setFolder(if (i == current) null else i) { overlays.alert(errorTitle, it) }
                        }
                    }
                    val remove = if (current != null) listOf(listOf(MenuItem(removeLabel, Icons.Trash, destructive = true) {
                        setFolder(null) { overlays.alert(errorTitle, it) }
                    })) else emptyList()
                    overlays.menu(anchor.bounds, listOf(items) + remove, folderTitle)
                },
                modifier = Modifier.anchor(anchor),
                size = 46.dp,
                iconColor = current?.let { EhFolders.colors[it] }?.takeIf { it != Color.Black } ?: if (current != null) AppTheme.colors.pink else AppTheme.tint,
                enabled = !busy,
            )
            when (record?.state) {
                DownloadRecord.FINISHED -> GlassCircleButton(
                    Icons.CheckCircle, tr("Downloaded"),
                    onClick = { overlays.actionSheet(deleteTitle, null, listOf(DialogButton(deleteLabel, ButtonRole.Destructive) { Downloads.delete(d.key) })) },
                    size = 46.dp, iconColor = AppTheme.colors.green,
                )
                DownloadRecord.DOWNLOADING, DownloadRecord.QUEUED ->
                    GlassCircleButton(Icons.Pause, tr("Pause Download"), onClick = { Downloads.pause(d.key) }, size = 46.dp, iconColor = AppTheme.tint, iconSize = 18.dp)
                DownloadRecord.PAUSED, DownloadRecord.FAILED ->
                    GlassCircleButton(Icons.Refresh, tr("Resume Download"), onClick = { Downloads.resume(d.key) }, size = 46.dp, iconColor = AppTheme.tint, iconSize = 18.dp)
                else -> GlassCircleButton(Icons.ArrowDown, tr("Download"), onClick = { Downloads.start(d, card) }, size = 46.dp, iconColor = AppTheme.tint)
            }
        }
    }

    /** The rating under the title; tap to rate. */
    @Composable
    private fun RatingButton(d: EhGalleryDetail) {
        val overlays = LocalOverlays.current
        val anchor = rememberAnchor()
        val rateTitle = tr("Rate")
        val errorTitle = tr("Couldn't Update")
        val loginNeeded = tr("Log in to your E-Hentai account first.")
        val countTitle = tr("%lld ratings", d.ratingCount)
        Row(
                Modifier
                    .anchor(anchor)
                    .clip(Capsule())
                    .clickable {
                        if (!EhSession.isLoggedIn) {
                            overlays.alert(rateTitle, loginNeeded)
                            return@clickable
                        }
                        val stars = (1..10).map { it / 2.0 }.reversed()
                        overlays.menu(anchor.bounds, listOf(stars.map { v ->
                            MenuItem(if (v % 1.0 == 0.0) "★ ${v.toInt()}" else "★ $v") { rate(v) { overlays.alert(errorTitle, it) } }
                        }), "$rateTitle · $countTitle")
                    }
                    .padding(horizontal = 4.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                RatingStars(rating, size = 11.dp)
                Txt(
                    String.format(java.util.Locale.ROOT, "%.2f", rating),
                    style = Type.footnote.copy(fontWeight = FontWeight.SemiBold),
                    color = if (d.ratedByUser) AppTheme.tint else Color.Unspecified,
                    maxLines = 1,
                )
            }
    }

    @Composable
    private fun Info(d: EhGalleryDetail) {
        val colors = AppTheme.colors
        Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 22.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.fillMaxWidth().height(0.5.dp).background(colors.separator))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                InfoCell(tr("Pages"), "${d.pages}")
                InfoCell(tr("Favorites"), compactCount(d.favoritedCount))
                InfoCell(tr("Posted"), d.posted.substringBefore(' '))
                InfoCell(tr("Size"), d.fileSize ?: "-")
            }
            Box(Modifier.fillMaxWidth().height(0.5.dp).background(colors.separator))
        }
    }

    @Composable
    private fun RowScope.InfoCell(title: String, value: String) {
        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Txt(title, style = Type.caption1.copy(fontWeight = FontWeight.Medium), color = AppTheme.colors.secondaryLabel, maxLines = 1)
            Txt(value, style = Type.subheadline.copy(fontWeight = FontWeight.SemiBold), maxLines = 1)
        }
    }

    @Composable
    private fun Tags(d: EhGalleryDetail) {
        val navigator = LocalNavigator.current
        if (d.tags.isEmpty()) return
        Column(Modifier.fillMaxWidth().padding(top = 18.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            d.tags.forEach { (namespace, tags) ->
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Txt(
                        TagStore.displayName(namespace, "rows").let { if (it == namespace) namespace.replaceFirstChar { c -> c.uppercase() } else it },
                        Modifier.padding(horizontal = 16.dp),
                        style = Type.subheadline.copy(fontWeight = FontWeight.SemiBold),
                        color = AppTheme.colors.secondaryLabel,
                    )
                    TagRow(tags.map { it to TagStore.displayName(it.name, it.namespace) }) { tag: EhTag ->
                        navigator.push(EhSearchScreen(EhListSource.Search(tag.query), TagStore.displayName(tag.name, tag.namespace)))
                    }
                }
            }
        }
    }

    @Composable
    private fun Previews(d: EhGalleryDetail) {
        val navigator = LocalNavigator.current
        val card = d.summary(preview)
        Column {
            SectionTitle(tr("Pages")) {
                if (d.pages > 1) TextButton(tr("See All"), { navigator.push(EhPagesScreen(d, card)) })
            }
            LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                items(d.thumbnails, key = { it.page }) { thumb ->
                    Column(
                        Modifier.clickable { navigator.push(ReaderScreen(ReaderSource.from(d, card), thumb.page - 1)) },
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        val ratio = if (thumb.width > 0 && thumb.height > 0) thumb.width.toFloat() / thumb.height else 0.7f
                        EhThumbnailImage(thumb, Modifier.size(width = 160.dp * ratio.coerceIn(0.5f, 1.5f), height = 160.dp).clip(RoundedCornerShape(8.dp)))
                        Txt("${thumb.page}", style = Type.caption1, color = AppTheme.colors.secondaryLabel)
                    }
                }
            }
        }
    }

    @Composable
    private fun Comments(d: EhGalleryDetail) {
        val navigator = LocalNavigator.current
        Column {
            SectionTitle(tr("Comments")) {
                if (d.comments.isNotEmpty()) {
                    TextButton(tr("See All (%lld)", d.comments.size), { navigator.push(EhCommentsScreen(d.gid, d.token, d.comments)) })
                } else if (EhSession.isLoggedIn) {
                    TextButton(tr("Add a comment…"), { navigator.push(EhCommentsScreen(d.gid, d.token, d.comments)) })
                }
            }
            if (d.comments.isEmpty()) {
                Txt(tr("No comments yet"), Modifier.padding(horizontal = 20.dp), style = Type.subheadline, color = AppTheme.colors.secondaryLabel)
            } else {
                LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(d.comments.take(10), key = { it.id }) { comment ->
                        Box(
                            Modifier.width(300.dp).clip(RoundedCornerShape(18.dp)).background(AppTheme.colors.quaternaryFill).padding(14.dp),
                        ) { EhCommentBubble(comment, maxLines = 5) }
                    }
                }
            }
        }
    }
}

@Composable
fun EhCommentBubble(comment: EhComment, maxLines: Int = Int.MAX_VALUE) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Txt(comment.author, Modifier.weight(1f, fill = false), style = Type.subheadline.copy(fontWeight = FontWeight.SemiBold), maxLines = 1)
            if (comment.isUploader) {
                Txt(tr("Uploader"), Modifier.clip(Capsule()).background(AppTheme.colors.tertiaryFill).padding(horizontal = 6.dp, vertical = 1.dp),
                    style = Type.caption2, color = AppTheme.colors.secondaryLabel)
            }
            Spacer(Modifier.weight(1f))
            comment.score?.let { score ->
                Txt(if (score > 0) "+$score" else "$score", style = Type.caption1.copy(fontWeight = FontWeight.SemiBold),
                    color = if (score >= 0) AppTheme.colors.green else AppTheme.colors.red)
            }
        }
        Txt(comment.posted, style = Type.caption1, color = AppTheme.colors.secondaryLabel, maxLines = 1)
        Txt(comment.text, style = Type.subheadline, maxLines = maxLines)
        if (maxLines == Int.MAX_VALUE) TranslateLine(comment.text, com.atlanyuey.aenht.shared.translate.TranslationKind.COMMENT)
    }
}

/** All comments of an E-Hentai gallery. */
class EhCommentsScreen(private val gid: Long, private val token: String, initial: List<EhComment>) : Screen() {
    private val comments = mutableStateListOf<EhComment>().apply { addAll(initial) }
    private var draft by mutableStateOf("")
    private var posting by mutableStateOf(false)
    private var pulling by mutableStateOf(false)

    private suspend fun load() {
        runCatching { EhClient.gallery(gid, token).comments }.getOrNull()?.let {
            comments.clear()
            comments.addAll(it)
        }
        pulling = false
    }

    @Composable
    override fun Content() {
        val overlays = LocalOverlays.current
        val errorTitle = tr("Couldn't Post")
        ScreenScaffold(
            title = tr("Comments"),
            overlay = {
                if (EhSession.isLoggedIn) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
                        CommentComposer(draft, { draft = it }, posting) { text ->
                            posting = true
                            scope.launch {
                                try {
                                    val updated = EhClient.postComment(gid, token, text)
                                    comments.clear()
                                    comments.addAll(updated)
                                    draft = ""
                                } catch (e: Exception) {
                                    overlays.alert(errorTitle, e.localizedMessage)
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
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = padding.calculateTopPadding() + 8.dp, bottom = padding.calculateBottomPadding() + 80.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    if (comments.isEmpty()) item { EmptyState(Icons.Comment, tr("No Comments")) }
                    items(comments, key = { it.id }) { comment ->
                        Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(AppTheme.colors.quaternaryFill).padding(14.dp)) {
                            EhCommentBubble(comment)
                        }
                    }
                }
            }
        }
    }
}

/** Every page's thumbnail; more thumbnail pages load while scrolling. */
class EhPagesScreen(private val detail: EhGalleryDetail, private val card: GallerySummary) : Screen() {
    private val thumbs = mutableStateListOf<EhThumbnail>().apply { addAll(detail.thumbnails) }
    private var nextPage = 1
    private var loading = false

    private fun loadMore() {
        if (loading || thumbs.size >= detail.pages || nextPage >= detail.thumbnailPageCount) return
        loading = true
        scope.launch {
            runCatching { EhClient.thumbnails(detail.gid, detail.token, nextPage) }.getOrNull()?.let { more ->
                val known = thumbs.map { it.page }.toSet()
                thumbs.addAll(more.filter { it.page !in known })
                EhPages.remember(detail.gid, more)
                nextPage++
            }
            loading = false
        }
    }

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.current
        ScreenScaffold(title = tr("Pages")) { padding ->
            LazyVerticalGrid(
                columns = GridCells.Adaptive(96.dp),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = padding.calculateTopPadding() + 8.dp, bottom = padding.calculateBottomPadding()),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                itemsIndexed(thumbs, key = { _, t -> t.page }) { index, thumb ->
                    LaunchedEffect(thumb.page) { if (index >= thumbs.size - 6) loadMore() }
                    Column(
                        Modifier.clickable { navigator.push(ReaderScreen(ReaderSource.from(detail, card), thumb.page - 1)) },
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        EhThumbnailImage(thumb, Modifier.fillMaxWidth().height(130.dp).clip(RoundedCornerShape(8.dp)))
                        Txt("${thumb.page}", style = Type.caption2, color = AppTheme.colors.secondaryLabel)
                    }
                }
            }
        }
    }
}

/** A list of E-Hentai galleries (tag, uploader or text search) opened from a gallery page. */
class EhSearchScreen(private val source: EhListSource, private val title: String) : Screen() {
    private val feed = GalleryFeed(scope, ehFeed { source })
    private val grid = androidx.compose.foundation.lazy.staggeredgrid.LazyStaggeredGridState()

    @Composable
    override fun Content() {
        LaunchedEffect(Unit) { if (!feed.hasLoaded) feed.reload() }
        ScreenScaffold(title = title) { padding ->
            GalleryGrid(
                feed.items, grid, padding,
                onItemShown = { feed.loadMoreIfNeeded(it) },
                footer = { feedFooter(feed, com.atlanyuey.aenht.core.L10n.string("No Galleries") to Icons.Photo) },
            )
        }
    }
}
