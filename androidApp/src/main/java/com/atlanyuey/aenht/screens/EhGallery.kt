package com.atlanyuey.aenht.screens

import com.atlanyuey.aenht.ui.components.readerThumb
import com.atlanyuey.aenht.core.privacyText
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

    /** Another account: its folders are read again. */
    fun reset() {
        loaded = false
        names = List(10) { "Favorites $it" }
    }

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
                // Moving between folders keeps the note.
                val note = if (target != null && before != null) runCatching { EhClient.favoriteNote(gid, token) }.getOrDefault("") else ""
                EhClient.setFavorite(gid, token, target, note)
                val card = summary
                if (target != null) card?.let { FavoritesStore.added(it, target) } else FavoritesStore.removed(com.atlanyuey.aenht.shared.site.GalleryKey(com.atlanyuey.aenht.shared.site.Site.EHENTAI, gid, token))
            } catch (e: Exception) {
                folder = before
                onError(e.localizedMessage ?: "")
            }
            busy = false
        }
    }

    /** Shows the favorite's note to edit (E-Hentai saves it with the folder). */
    private fun editNote(overlays: com.atlanyuey.aenht.ui.components.OverlayController, labels: List<String>) {
        val current = folder ?: return
        scope.launch {
            val note = runCatching { EhClient.favoriteNote(gid, token) }.getOrDefault("")
            overlays.prompt(labels[2], initial = note, confirm = labels[3], cancel = labels[4], singleLine = false) { text ->
                scope.launch {
                    try {
                        EhClient.setFavorite(gid, token, current, text.take(200))
                    } catch (e: Exception) {
                        overlays.alert(labels[5], e.localizedMessage)
                    }
                }
            }
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
                val navigator = LocalNavigator.current
                val overlays = LocalOverlays.current
                val labels = listOf(tr("Open in Browser"), tr("Torrents"), tr("Favorite Note"), tr("Save"), tr("Cancel"), tr("Couldn't Update"))
                val archiveLabel = tr("Download Archive")
                val tagsLabel = tr("Vote on Tags")
                MenuButton(Icons.Ellipsis, tr("More"), {
                    listOf(buildList {
                        add(MenuItem(labels[0], Icons.Safari) {
                            context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(EhClient.api.galleryUrl(gid, token))))
                        })
                        val d = detail
                        if (d != null && d.archiverKey != null && EhSession.isLoggedIn) {
                            add(MenuItem(archiveLabel, Icons.Albums) { navigator.push(EhArchiveScreen(d)) })
                        }
                        if (d != null && EhSession.isLoggedIn) {
                            add(MenuItem(tagsLabel, Icons.Tag) { navigator.push(EhTagVoteScreen(d)) })
                        }
                        if ((detail?.torrentCount ?: 0) > 0) {
                            add(MenuItem(labels[1], Icons.ArrowDown) { navigator.push(EhTorrentsScreen(gid, token)) })
                        }
                        if (folder != null && EhSession.isLoggedIn) {
                            add(MenuItem(labels[2], Icons.Document) { editNote(overlays, labels) })
                        }
                    })
                })
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
                    // Translated, the translation takes the title's place; a tap
                    // switches between it and the original.
                    var titleTranslation by remember(detail?.gid) { mutableStateOf<com.atlanyuey.aenht.shared.translate.Translation?>(null) }
                    var showsOriginal by remember(detail?.gid) { mutableStateOf(false) }
                    val title = titleTranslation?.takeIf { !showsOriginal }?.text ?: detail?.title ?: preview?.title ?: ""
                    Txt(
                        title,
                        Modifier.privacyText().combinedClickable(
                            interactionSource = null, indication = null,
                            onClick = { if (titleTranslation != null) showsOriginal = !showsOriginal else titleExpanded = !titleExpanded },
                            onLongClick = {
                                val text = listOfNotNull(detail?.title, detail?.japaneseTitle).joinToString("\n")
                                context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("title", text))
                            },
                        ),
                        style = if (wide) Type.title2.copy(fontWeight = FontWeight.SemiBold) else Type.title3,
                        maxLines = if (titleExpanded) Int.MAX_VALUE else 5,
                    )
                    detail?.japaneseTitle?.let {
                        Txt(it, Modifier.privacyText(), style = Type.subheadline, color = AppTheme.colors.secondaryLabel, maxLines = if (titleExpanded) Int.MAX_VALUE else 2)
                    }
                    detail?.let { d ->
                        Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Box(Modifier.weight(1f)) {
                                TranslateLine(d.japaneseTitle ?: d.title, com.atlanyuey.aenht.shared.translate.TranslationKind.TITLE,
                                    onResult = { t -> titleTranslation = t; showsOriginal = false })
                            }
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
            val rowState = androidx.compose.foundation.lazy.rememberLazyListState()
            com.atlanyuey.aenht.ui.components.FollowReader(d.gid.toInt(), rowState, d.thumbnails.size)
            LazyRow(state = rowState, contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                items(d.thumbnails, key = { it.page }) { thumb ->
                    Column(
                        Modifier.clickable { navigator.push(ReaderScreen(ReaderSource.from(d, card), thumb.page - 1)) },
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        val ratio = if (thumb.width > 0 && thumb.height > 0) thumb.width.toFloat() / thumb.height else 0.7f
                        EhThumbnailImage(thumb, Modifier.size(width = 160.dp * ratio.coerceIn(0.5f, 1.5f), height = 160.dp)
                            .readerThumb(d.gid.toInt(), thumb.page - 1).clip(RoundedCornerShape(8.dp)))
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
                    TextButton(tr("See All (%lld)", d.comments.size), { navigator.push(EhCommentsScreen(d)) })
                } else if (EhSession.isLoggedIn) {
                    TextButton(tr("Add a comment…"), { navigator.push(EhCommentsScreen(d)) })
                }
            }
            val shown = d.comments.filter { it.author !in AppSettings.blockedCommenterSet && !Filters.hidesComment(it.author, it.text) }
            if (shown.isEmpty()) {
                Txt(tr("No comments yet"), Modifier.padding(horizontal = 20.dp), style = Type.subheadline, color = AppTheme.colors.secondaryLabel)
            } else {
                LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(shown.take(10), key = { it.id }) { comment ->
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
        Txt(comment.text, Modifier.privacyText(), style = Type.subheadline, maxLines = maxLines)
        if (maxLines == Int.MAX_VALUE) TranslateLine(comment.text, com.atlanyuey.aenht.shared.translate.TranslationKind.COMMENT)
    }
}

/** All comments of an E-Hentai gallery. */
class EhCommentsScreen(private val detail: EhGalleryDetail) : Screen() {
    private val gid = detail.gid
    private val token = detail.token
    private val comments = mutableStateListOf<EhComment>().apply { addAll(detail.comments) }
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

    private class Labels(
        val up: String, val down: String, val copy: String, val block: String, val blockTitle: String,
        val blockMessage: String, val cancel: String, val voteError: String, val copyText: (String) -> Unit,
    )

    private fun commentMenu(comment: EhComment, l: Labels, overlays: com.atlanyuey.aenht.ui.components.OverlayController): List<List<MenuItem>> = buildList {
        val vote = { up: Boolean ->
            scope.launch {
                try {
                    val score = EhClient.voteComment(detail, comment.id, up)
                    val i = comments.indexOfFirst { it.id == comment.id }
                    if (i >= 0 && score != null) comments[i] = comments[i].copy(score = score)
                } catch (e: Exception) {
                    overlays.alert(l.voteError, e.localizedMessage)
                }
            }
            Unit
        }
        if (EhSession.isLoggedIn && !comment.isUploader) {
            add(listOf(MenuItem(l.up, Icons.ArrowUp) { vote(true) }, MenuItem(l.down, Icons.ArrowDown) { vote(false) }))
        }
        add(listOf(MenuItem(l.copy, Icons.Copy) { l.copyText(comment.text) }))
        if (!comment.isUploader) {
            add(listOf(MenuItem(l.block + " " + comment.author, Icons.EyeOff, destructive = true) {
                overlays.alert(l.blockTitle, l.blockMessage, listOf(
                    DialogButton(l.cancel, ButtonRole.Cancel) {},
                    DialogButton(l.block, ButtonRole.Destructive) { AppSettings.blockCommenter(comment.author) },
                ))
            }))
        }
    }

    @Composable
    override fun Content() {
        val overlays = LocalOverlays.current
        val errorTitle = tr("Couldn't Post")
        val context = androidx.compose.ui.platform.LocalContext.current
        val labels = Labels(
            tr("Vote Up"), tr("Vote Down"), tr("Copy"), tr("Block"), tr("Block Commenter?"),
            tr("Comments by this user are hidden on every gallery. Unblock in Settings › Filters."), tr("Cancel"), tr("Couldn't Vote"),
        ) { text ->
            context.getSystemService(android.content.ClipboardManager::class.java)
                ?.setPrimaryClip(android.content.ClipData.newPlainText("comment", text))
        }
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
                    val blocked = AppSettings.blockedCommenterSet
                    val shown = comments.filter { it.author !in blocked && !Filters.hidesComment(it.author, it.text) }
                    if (shown.isEmpty()) item { EmptyState(Icons.Comment, tr("No Comments")) }
                    items(shown, key = { it.id }) { comment ->
                        val anchor = rememberAnchor()
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .anchor(anchor)
                                .clip(RoundedCornerShape(18.dp))
                                .background(AppTheme.colors.quaternaryFill)
                                .combinedClickable(onClick = {}, onLongClick = { overlays.menu(anchor.bounds, commentMenu(comment, labels, overlays)) })
                                .padding(14.dp),
                        ) {
                            EhCommentBubble(comment)
                        }
                    }
                }
            }
        }
    }
}

/** Archive download: the original or resampled zip (costs GP), saved or shared when done. */
class EhArchiveScreen(private val detail: EhGalleryDetail) : Screen() {
    private var info by mutableStateOf<com.atlanyuey.aenht.shared.eh.EhArchiveInfo?>(null)
    private var error by mutableStateOf<String?>(null)
    private var progress by mutableStateOf<Float?>(null)
    private var file by mutableStateOf<java.io.File?>(null)

    private suspend fun download(context: android.content.Context, url: String) {
        progress = 0f
        file = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val dir = java.io.File(context.cacheDir, "shared").apply { mkdirs() }
            val name = detail.title.replace(Regex("""[\\/:*?"<>|]"""), "_").take(120).ifBlank { "${detail.gid}" }
            val out = java.io.File(dir, "$name.zip")
            Net.client.newCall(okhttp3.Request.Builder().url(url).build()).execute().use { response ->
                if (!response.isSuccessful) throw NHException("HTTP ${response.code}")
                val body = response.body
                val total = body.contentLength()
                body.byteStream().use { input ->
                    out.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        var done = 0L
                        while (true) {
                            val n = input.read(buffer)
                            if (n < 0) break
                            output.write(buffer, 0, n)
                            done += n
                            if (total > 0) progress = done.toFloat() / total
                        }
                    }
                }
            }
            out
        }
        progress = null
    }

    @Composable
    override fun Content() {
        val context = LocalContext.current
        val overlays = LocalOverlays.current
        val confirmTitle = tr("Download Archive?")
        val confirm = tr("Download")
        val cancel = tr("Cancel")
        val failed = tr("Couldn't Download")
        val saved = tr("Saved to Downloads › AENHT.")
        LaunchedEffect(Unit) {
            try { info = EhClient.archiveInfo(detail) } catch (e: Exception) { error = e.localizedMessage }
        }
        GroupedListScreen(tr("Download Archive")) {
            val i = info
            when {
                error != null -> item { EmptyState(Icons.Warning, error ?: "") }
                i == null -> item { Box(Modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) { ActivityIndicator() } }
                else -> {
                    section(footer = { FooterText(tr("The archive is a zip of the whole gallery from E-Hentai's servers. Its cost is taken from your account's GP.") + (i.funds?.let { "\n" + tr("Funds: %@", it) } ?: "")) }) {
                        i.options.forEachIndexed { index, option ->
                            ListRow(
                                option.label,
                                subtitle = listOf(option.cost, option.size).filter { it.isNotBlank() }.joinToString(" · "),
                                last = index == i.options.lastIndex,
                                onClick = {
                                    if (progress != null) return@ListRow
                                    overlays.alert(confirmTitle, listOf(option.cost, option.size).filter { it.isNotBlank() }.joinToString(" · "), listOf(
                                        DialogButton(cancel, ButtonRole.Cancel) {},
                                        DialogButton(confirm, ButtonRole.Preferred) {
                                            scope.launch {
                                                try {
                                                    download(context, EhClient.requestArchive(detail, option))
                                                } catch (e: Exception) {
                                                    progress = null
                                                    overlays.alert(failed, e.localizedMessage)
                                                }
                                            }
                                        },
                                    ))
                                },
                            )
                        }
                    }
                    progress?.let { p ->
                        section { Column(Modifier.fillMaxWidth().padding(16.dp)) { ProgressBar(p) } }
                    }
                    file?.let { f ->
                        section {
                            ButtonRow(tr("Share"), {
                                val uri = androidx.core.content.FileProvider.getUriForFile(context, context.packageName + ".files", f)
                                val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                                    type = "application/zip"
                                    putExtra(android.content.Intent.EXTRA_STREAM, uri)
                                    addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }
                                context.startActivity(android.content.Intent.createChooser(send, null))
                            }, last = android.os.Build.VERSION.SDK_INT < 29)
                            if (android.os.Build.VERSION.SDK_INT >= 29) {
                                ButtonRow(tr("Save to Downloads"), {
                                    scope.launch {
                                        val ok = runCatching { Downloads.saveToDownloads(context, f) }.getOrDefault(false)
                                        overlays.alert(if (ok) tr0(saved) else failed, null)
                                    }
                                }, last = true)
                            }
                        }
                    }
                }
            }
        }
    }

    private fun tr0(s: String) = s
}

/** Votes on a gallery's tags, and adds tags (E-Hentai tag voting). */
class EhTagVoteScreen(private val detail: EhGalleryDetail) : Screen() {
    private var adding by mutableStateOf("")
    private val voted = mutableStateMapOf<String, Boolean>()

    @Composable
    override fun Content() {
        val overlays = LocalOverlays.current
        val failed = tr("Couldn't Vote")
        fun vote(tags: String, up: Boolean, mark: String?) {
            scope.launch {
                try {
                    EhClient.voteTags(detail, tags, up)?.let { overlays.alert(failed, it) } ?: mark?.let { voted[it] = up }
                } catch (e: Exception) {
                    overlays.alert(failed, e.localizedMessage)
                }
            }
        }
        GroupedListScreen(tr("Vote on Tags")) {
            section("Add Tags", footer = { FooterText(tr("Separate tags with commas, with a namespace: female:glasses, artist:name. Adding votes for them.")) }) {
                FieldRow(tr("Tags"), adding) { adding = it }
                ButtonRow(tr("Add"), { vote(adding.trim(), true, null); adding = "" }, enabled = adding.isNotBlank(), last = true)
            }
            detail.tags.forEach { (namespace, tags) ->
                section(namespace) {
                    tags.forEachIndexed { i, tag ->
                        val full = "$namespace:${tag.name}"
                        ListRow(TagStore.displayName(tag.name, namespace), subtitle = tag.name.takeIf { TagStore.displayName(it, namespace) != it }, last = i == tags.lastIndex, trailing = {
                            Box(Modifier.size(34.dp).clickable { vote(full, true, full) }, contentAlignment = Alignment.Center) {
                                AppIcon(Icons.ArrowUp, tr("Vote Up"), tint = if (voted[full] == true) AppTheme.colors.green else AppTheme.colors.secondaryLabel, size = 17.dp)
                            }
                            Box(Modifier.size(34.dp).clickable { vote(full, false, full) }, contentAlignment = Alignment.Center) {
                                AppIcon(Icons.ArrowDown, tr("Vote Down"), tint = if (voted[full] == false) AppTheme.colors.red else AppTheme.colors.secondaryLabel, size = 17.dp)
                            }
                        })
                    }
                }
            }
        }
    }
}

/** A gallery's torrents (tap: download the .torrent; the account's link). */
class EhTorrentsScreen(private val gid: Long, private val token: String) : Screen() {
    private var torrents by mutableStateOf<List<com.atlanyuey.aenht.shared.eh.EhTorrent>?>(null)
    private var error by mutableStateOf<String?>(null)

    @Composable
    override fun Content() {
        val context = LocalContext.current
        LaunchedEffect(Unit) {
            try { torrents = EhClient.torrents(gid, token) } catch (e: Exception) { error = e.localizedMessage }
        }
        GroupedListScreen(tr("Torrents")) {
            val list = torrents
            when {
                error != null -> item { EmptyState(Icons.Warning, error ?: "") }
                list == null -> item { Box(Modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) { ActivityIndicator() } }
                list.isEmpty() -> item { EmptyState(Icons.ArrowDown, tr("No Torrents")) }
                else -> section(footer = { FooterText(tr("Torrent links are made for your account; don't share them.")) }) {
                    list.forEachIndexed { i, t ->
                        ListRow(
                            t.name,
                            subtitle = "${t.size} · ${tr("Seeds")} ${t.seeds} · ${tr("Peers")} ${t.peers} · ${t.posted}",
                            last = i == list.lastIndex,
                            onClick = { context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(t.url))) },
                        )
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
                        EhThumbnailImage(thumb, Modifier.fillMaxWidth().height(130.dp).readerThumb(detail.gid.toInt(), thumb.page - 1).clip(RoundedCornerShape(8.dp)))
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
