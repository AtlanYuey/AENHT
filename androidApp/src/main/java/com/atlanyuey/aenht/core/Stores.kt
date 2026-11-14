package com.atlanyuey.aenht.core

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonObject
import okhttp3.Request
import com.atlanyuey.aenht.shared.net.arr
import com.atlanyuey.aenht.shared.net.obj
import com.atlanyuey.aenht.shared.net.str
import com.atlanyuey.aenht.shared.net.int
import com.atlanyuey.aenht.shared.net.long
import com.atlanyuey.aenht.shared.net.bool
import java.io.File
import com.atlanyuey.aenht.shared.db.HistoryRecord
import com.atlanyuey.aenht.shared.db.HistoryRepository
import com.atlanyuey.aenht.shared.db.openDatabase
import com.atlanyuey.aenht.shared.site.GalleryKey
import com.atlanyuey.aenht.shared.site.Site
import com.atlanyuey.aenht.shared.site.GallerySummary
import com.atlanyuey.aenht.shared.eh.EhListSource
import com.atlanyuey.aenht.shared.eh.EhCategory
import com.atlanyuey.aenht.shared.eh.EhGalleryDetail
import com.atlanyuey.aenht.shared.eh.EhThumbnail
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Coroutine scope for app-wide work (saving files, downloads). */
val AppScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

// MARK: - History

/** A gallery the user opened (either site), with the last page read. */
@Serializable
data class HistoryEntry(
    val gid: Int,
    val mediaId: String,
    val title: String,
    val thumbnail: String?,
    val thumbWidth: Int,
    val thumbHeight: Int,
    val numPages: Int,
    val lastPage: Int,
    val lastRead: Long,
    /** For the language badge and tags on cards (nhentai). */
    val tagIds: List<Int> = emptyList(),
    val site: String = Site.NHENTAI.key,
    val token: String? = null,
) {
    val key: GalleryKey get() = GalleryKey(Site.fromKey(site) ?: Site.NHENTAI, gid.toLong(), token)

    val item: GalleryItem
        get() = GalleryItem(
            id = gid, mediaId = mediaId, englishTitle = title, thumbnail = thumbnail,
            thumbnailWidth = thumbWidth, thumbnailHeight = thumbHeight, numPages = numPages,
            tagIds = tagIds,
        )

    val summary: GallerySummary
        get() = if (site == Site.NHENTAI.key) GallerySummary.from(item) else GallerySummary(
            key = key, title = title, thumbnail = thumbnail, thumbWidth = thumbWidth, thumbHeight = thumbHeight,
            pages = numPages, languageBadge = null,
        )

    companion object {
        fun from(record: HistoryRecord) = HistoryEntry(
            gid = record.galleryId.toInt(), mediaId = record.mediaId, title = record.title,
            thumbnail = record.thumbnail, thumbWidth = record.thumbWidth, thumbHeight = record.thumbHeight,
            numPages = record.numPages, lastPage = record.lastPage, lastRead = record.readAt,
            tagIds = record.tagIdList, site = record.site, token = record.token,
        )
    }
}

/**
 * Reading history, kept in the shared database (both sites; this screen
 * shows nhentai until E-Hentai arrives). The list is updated at once; the
 * database is written in order in the background.
 */
object HistoryStore {
    private lateinit var repo: HistoryRepository
    val entries = mutableStateListOf<HistoryEntry>()
    private val writes = Mutex()

    fun init(context: Context) {
        repo = HistoryRepository(AppDb.get(context))
        AppScope.launch {
            val loaded = runCatching { repo.all() }.getOrDefault(emptyList()).map { HistoryEntry.from(it) }
            val known = entries.map { it.key }.toSet()
            entries.addAll(loaded.filter { it.key !in known })
            entries.sortByDescending { it.lastRead }
        }
    }

    /** Reads the history again (after WebDAV sync changed it). */
    suspend fun reloadFromDatabase() {
        val loaded = writes.withLock { runCatching { repo.all() }.getOrNull() } ?: return
        entries.clear()
        entries.addAll(loaded.map { HistoryEntry.from(it) })
    }

    /** Records that a gallery of either site was opened (or read up to [page]). */
    fun record(item: GallerySummary, page: Int? = null) {
        val key = item.key
        val index = entries.indexOfFirst { it.key == key }
        val previous = entries.getOrNull(index)
        val entry = HistoryEntry(
            gid = key.id.toInt(),
            mediaId = item.mediaId.ifEmpty { previous?.mediaId ?: "" },
            title = item.title.ifEmpty { previous?.title ?: "" },
            thumbnail = item.thumbnail ?: previous?.thumbnail,
            thumbWidth = if (item.thumbWidth > 0) item.thumbWidth else previous?.thumbWidth ?: 0,
            thumbHeight = if (item.thumbHeight > 0) item.thumbHeight else previous?.thumbHeight ?: 0,
            numPages = if (item.pages > 0) item.pages else previous?.numPages ?: 0,
            lastPage = page ?: previous?.lastPage ?: 0,
            lastRead = System.currentTimeMillis(),
            tagIds = item.nhTagIds.ifEmpty { previous?.tagIds ?: emptyList() },
            site = key.site.key,
            token = key.token ?: previous?.token,
        )
        if (index >= 0) entries.removeAt(index)
        entries.add(0, entry)
        write {
            repo.record(
                entry.key, entry.title, entry.mediaId, entry.thumbnail,
                entry.thumbWidth, entry.thumbHeight, entry.numPages, entry.lastPage, entry.tagIds,
            )
        }
    }

    fun record(item: GalleryItem, title: String, numPages: Int, page: Int? = null) =
        record(GallerySummary.from(item).copy(title = title, pages = numPages), page)

    fun record(detail: GalleryDetail, page: Int? = null) =
        record(detail.asItem, detail.displayTitle, detail.numPages, page)

    fun lastReadPage(key: GalleryKey): Int? = entries.firstOrNull { it.key == key }?.lastPage
    fun lastReadPage(gid: Int): Int? = lastReadPage(GalleryKey.nh(gid))

    fun delete(key: GalleryKey) {
        entries.removeAll { it.key == key }
        write { repo.delete(key) }
    }

    fun delete(gid: Int) = delete(GalleryKey.nh(gid))

    fun clear() {
        entries.clear()
        write { repo.clear() }
    }

    private fun write(block: suspend () -> Unit) {
        AppScope.launch {
            writes.withLock { runCatching { block() } }
        }
    }
}

// MARK: - Downloads

@Serializable
data class DownloadPage(
    val number: Int,
    /** nhentai: the image URL. E-Hentai: the page link (`/s/…`), resolved when downloading. */
    val remote: String,
    val file: String,
    val width: Int,
    val height: Int,
)

@Serializable
data class DownloadRecord(
    val id: Int,
    val mediaId: String,
    val title: String,
    val thumbnail: String?,
    val thumbWidth: Int,
    val thumbHeight: Int,
    val pages: List<DownloadPage>,
    val completed: Int,
    val state: String,
    val added: Long,
    /** For the language badge and tags on cards (null for downloads from before 2.1.0). */
    val tagIds: List<Int>? = null,
    /** "nh" or "eh". */
    val site: String = Site.NHENTAI.key,
    /** E-Hentai gallery token. */
    val token: String? = null,
    /** E-Hentai: total pages (the page links are filled in while downloading). */
    val pageCount: Int = 0,
    val category: String? = null,
    val language: String? = null,
) {
    val key: GalleryKey get() = GalleryKey(Site.fromKey(site) ?: Site.NHENTAI, id.toLong(), token)
    val isEh: Boolean get() = site == Site.EHENTAI.key
    val totalPages: Int get() = maxOf(pages.size, pageCount)
    val progress: Float get() = if (totalPages == 0) 0f else completed.toFloat() / totalPages

    val item: GalleryItem
        get() = GalleryItem(
            id = id, mediaId = mediaId, englishTitle = title, thumbnail = thumbnail,
            thumbnailWidth = thumbWidth, thumbnailHeight = thumbHeight, numPages = pages.size,
            tagIds = tagIds ?: emptyList(),
        )

    val summary: GallerySummary
        get() = if (isEh) {
            GallerySummary(
                key = key, title = title, thumbnail = thumbnail, thumbWidth = thumbWidth, thumbHeight = thumbHeight,
                pages = totalPages, languageBadge = GallerySummary.ehLanguageBadge(language),
                category = category?.let(EhCategory::fromLabel),
            )
        } else {
            GallerySummary.from(item)
        }

    companion object {
        const val QUEUED = "queued"
        const val DOWNLOADING = "downloading"
        const val PAUSED = "paused"
        const val FINISHED = "finished"
        const val FAILED = "failed"
    }
}

/** Downloads gallery pages of both sites for offline reading (three at a time). */
object Downloads {
    private lateinit var root: File
    val records = mutableStateListOf<DownloadRecord>()
    private val jobs = mutableMapOf<String, Job>()
    private var lastSave = 0L

    fun init(context: Context) {
        root = File(context.filesDir, "Downloads")
        val loaded = try {
            Net.json.decodeFromString(ListSerializer(DownloadRecord.serializer()), File(root, "index.json").readText())
        } catch (_: Exception) {
            emptyList()
        }
        // Downloads interrupted by the app being closed resume as paused.
        records.addAll(loaded.map {
            if (it.state == DownloadRecord.DOWNLOADING || it.state == DownloadRecord.QUEUED) {
                it.copy(state = DownloadRecord.PAUSED)
            } else {
                it
            }
        })
    }

    fun record(key: GalleryKey): DownloadRecord? =
        records.firstOrNull { it.site == key.site.key && it.id.toLong() == key.id }

    fun record(id: Int): DownloadRecord? = record(GalleryKey.nh(id))

    fun folder(key: GalleryKey) = File(root, if (key.site == Site.EHENTAI) "eh-${key.id}" else "${key.id}")
    fun folder(id: Int) = folder(GalleryKey.nh(id))

    /** Local file of a page if it has been downloaded. */
    fun localFile(key: GalleryKey, index: Int): File? {
        val record = record(key) ?: return null
        val page = record.pages.getOrNull(index) ?: return null
        return File(folder(key), page.file).takeIf { it.exists() }
    }

    fun localFile(galleryId: Int, index: Int): File? = localFile(GalleryKey.nh(galleryId), index)

    private fun detailFile(key: GalleryKey) = File(folder(key), "gallery.json")

    /**
     * The page of a downloaded gallery without the network: the gallery's
     * JSON saved with the download, or what the download record knows.
     */
    fun offlineDetail(id: Int): GalleryDetail? {
        val record = record(id) ?: return null
        val saved = runCatching {
            Net.json.parseToJsonElement(detailFile(GalleryKey.nh(id)).readText()).obj()?.let(GalleryDetail::parse)
        }.getOrNull()
        return saved ?: galleryDetailFrom(record)
    }

    /** The saved page of a downloaded E-Hentai gallery (offline). */
    fun offlineEhDetail(key: GalleryKey): EhGalleryDetail? = runCatching {
        Net.json.decodeFromString(EhGalleryDetail.serializer(), detailFile(key).readText())
    }.getOrNull()

    /** Keeps the gallery's [json] (as the API sent it) with its download, for its page offline. */
    fun saveDetail(json: JsonObject, detail: GalleryDetail) {
        val record = record(detail.id) ?: return
        if (record.tagIds == null) {
            update(record.key) { it.copy(tagIds = detail.tags.map { tag -> tag.id }) }
            saveIndex()
        }
        writeDetail(GalleryKey.nh(detail.id), json.toString())
    }

    private fun writeDetail(key: GalleryKey, text: String) {
        val file = detailFile(key)
        AppScope.launch(Dispatchers.IO) {
            runCatching {
                file.parentFile?.mkdirs()
                file.writeText(text)
            }
        }
    }

    /** Downloads [detail]; [json] is its JSON, kept for its page offline. */
    fun start(detail: GalleryDetail, json: JsonObject? = null) {
        if (record(detail.id) == null) {
            val item = detail.asItem
            val pages = detail.pages.mapIndexed { offset, page ->
                val ext = page.path.substringAfterLast('.', "").ifEmpty { "jpg" }
                DownloadPage(
                    number = page.number,
                    remote = page.imageUrl ?: "",
                    file = String.format(java.util.Locale.ROOT, "%04d.%s", offset + 1, ext),
                    width = page.width,
                    height = page.height,
                )
            }
            records.add(
                0,
                DownloadRecord(
                    id = detail.id, mediaId = detail.mediaId, title = detail.displayTitle,
                    thumbnail = item.thumbnail, thumbWidth = item.thumbnailWidth,
                    thumbHeight = item.thumbnailHeight, pages = pages, completed = 0,
                    state = DownloadRecord.QUEUED, added = System.currentTimeMillis(),
                    tagIds = detail.tags.map { it.id },
                ),
            )
            saveIndex()
        }
        json?.let { saveDetail(it, detail) }
        resume(GalleryKey.nh(detail.id))
    }

    /** Downloads an E-Hentai gallery; its page is kept for offline use. */
    fun start(detail: EhGalleryDetail, summary: GallerySummary?) {
        val key = detail.key
        if (record(key) == null) {
            records.add(
                0,
                DownloadRecord(
                    id = detail.gid.toInt(), mediaId = "", title = detail.title,
                    thumbnail = summary?.thumbnail ?: detail.cover,
                    thumbWidth = summary?.thumbWidth ?: 0, thumbHeight = summary?.thumbHeight ?: 0,
                    pages = ehPages(detail.thumbnails), completed = 0,
                    state = DownloadRecord.QUEUED, added = System.currentTimeMillis(),
                    site = Site.EHENTAI.key, token = detail.token, pageCount = detail.pages,
                    category = detail.category?.label, language = detail.language?.lowercase()?.substringBefore(' '),
                ),
            )
            saveIndex()
        }
        writeDetail(key, Net.json.encodeToString(EhGalleryDetail.serializer(), detail))
        resume(key)
    }

    private fun ehPages(thumbs: List<EhThumbnail>) = thumbs.map { t ->
        DownloadPage(t.page, t.pageUrl, String.format(java.util.Locale.ROOT, "%04d.img", t.page), 0, 0)
    }

    fun resume(key: GalleryKey) {
        val record = record(key) ?: return
        if (jobs[key.storageKey] != null || record.state == DownloadRecord.FINISHED) return
        update(key) { it.copy(state = DownloadRecord.DOWNLOADING) }
        saveIndex()
        jobs[key.storageKey] = AppScope.launch { run(key) }
    }

    fun resume(id: Int) = resume(GalleryKey.nh(id))

    fun pause(key: GalleryKey) {
        jobs.remove(key.storageKey)?.cancel()
        update(key) { it.copy(state = DownloadRecord.PAUSED) }
        saveIndex()
    }

    fun pause(id: Int) = pause(GalleryKey.nh(id))

    fun delete(key: GalleryKey) {
        jobs.remove(key.storageKey)?.cancel()
        records.removeAll { it.site == key.site.key && it.id.toLong() == key.id }
        val folder = folder(key)
        AppScope.launch(Dispatchers.IO) { folder.deleteRecursively() }
        saveIndex()
    }

    fun delete(id: Int) = delete(GalleryKey.nh(id))

    private suspend fun run(key: GalleryKey) {
        if (record(key)?.isEh == true && !completeEhPages(key)) {
            jobs.remove(key.storageKey)
            update(key) { it.copy(state = DownloadRecord.FAILED) }
            saveIndex()
            return
        }
        val record = record(key) ?: return
        val folder = folder(key)
        val pending = withContext(Dispatchers.IO) {
            folder.mkdirs()
            record.pages.filter { !File(folder, it.file).exists() }
        }
        update(key) { it.copy(completed = it.pages.size - pending.size) }
        var failures = 0
        // E-Hentai: fewer at a time (each page also loads its page link).
        val semaphore = Semaphore(if (record.isEh) 2 else 3)
        coroutineScope {
            pending.map { page ->
                async {
                    val ok = semaphore.withPermit {
                        if (record.isEh) fetchEh(page, File(folder, page.file)) else fetch(page.remote, File(folder, page.file))
                    }
                    if (ok) update(key) { it.copy(completed = it.completed + 1) } else failures++
                    if (System.currentTimeMillis() - lastSave > 2000) saveIndex()
                }
            }.awaitAll()
        }
        jobs.remove(key.storageKey)
        update(key) {
            it.copy(
                state = if (failures == 0 && it.completed >= it.totalPages) DownloadRecord.FINISHED
                else DownloadRecord.FAILED,
            )
        }
        saveIndex()
    }

    /** Fills in the page links of an E-Hentai download from its thumbnail pages. */
    private suspend fun completeEhPages(key: GalleryKey): Boolean {
        var record = record(key) ?: return false
        if (record.pages.size >= record.pageCount) return true
        val token = record.token ?: return false
        var thumbPage = 1
        while (record.pages.size < record.pageCount) {
            val thumbs = try {
                EhClient.thumbnails(key.id, token, thumbPage)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
                return false
            }
            val known = record.pages.map { it.number }.toSet()
            val added = ehPages(thumbs.filter { it.page !in known })
            if (thumbs.isEmpty()) break
            update(key) { it.copy(pages = (it.pages + added).sortedBy { p -> p.number }) }
            record = record(key) ?: return false
            thumbPage++
        }
        saveIndex()
        return record.pages.size >= record.pageCount
    }

    /** An E-Hentai page: its image link first, then the image (another node when it fails). */
    private suspend fun fetchEh(page: DownloadPage, file: File): Boolean {
        var reloadKey: String? = null
        repeat(3) { attempt ->
            val image = try {
                EhClient.imagePage(page.remote, reloadKey)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
                delay(2000L * (attempt + 1))
                return@repeat
            }
            if (fetch(image.imageUrl, file, attempts = 1)) return true
            reloadKey = image.reloadKey
        }
        return false
    }

    private suspend fun fetch(url: String, file: File, attempts: Int = 3): Boolean = withContext(Dispatchers.IO) {
        repeat(attempts) { attempt ->
            if (!isActive) return@withContext false
            try {
                val request = Request.Builder().url(url).build()
                val ok = Net.client.newCall(request).execute().use { response ->
                    if (response.code != 200) return@use false
                    val temp = File(file.parentFile, file.name + ".part")
                    temp.outputStream().use { out -> response.body.byteStream().copyTo(out) }
                    temp.length() > 0 && temp.renameTo(file)
                }
                if (ok) return@withContext true
            } catch (_: Exception) {
            }
            if (attempt < attempts - 1) delay(1000L * (attempt + 1))
        }
        false
    }

    private fun update(key: GalleryKey, change: (DownloadRecord) -> DownloadRecord) {
        val index = records.indexOfFirst { it.site == key.site.key && it.id.toLong() == key.id }
        if (index >= 0) records[index] = change(records[index])
    }

    private fun saveIndex() {
        lastSave = System.currentTimeMillis()
        val snapshot = records.toList()
        AppScope.launch(Dispatchers.IO) {
            runCatching {
                root.mkdirs()
                File(root, "index.json").writeText(
                    Net.json.encodeToString(ListSerializer(DownloadRecord.serializer()), snapshot),
                )
            }
        }
    }

    /** Bytes used by downloads. */
    fun diskUsage(): Long = root.walkTopDown().filter { it.isFile }.sumOf { it.length() }
}

// MARK: - Tags

@Serializable
data class TagName(val name: String, val type: String)

@Serializable
private data class TagSnapshot(
    val tags: Map<Int, TagName>,
    val translations: Map<String, Map<String, String>>,
    val date: Long,
)

/**
 * Local tag database: maps the tag ids of list items to names and
 * translates tag names to Chinese (EhTagTranslation). Downloaded once and
 * refreshed weekly; jsDelivr is tried first because GitHub is often slow.
 */
object TagStore {
    private lateinit var cacheFile: File
    private var tags: Map<Int, TagName> = emptyMap()
    private var translations: Map<String, Map<String, String>> = emptyMap()
    private var prepared = false

    var isUpdating by mutableStateOf(false)
        private set
    var lastUpdated by mutableStateOf<Long?>(null)
        private set
    var tagCount by mutableIntStateOf(0)
        private set
    var translationCount by mutableIntStateOf(0)
        private set
    /** Bumped when the data changes, so views that read tags refresh. */
    var revision by mutableIntStateOf(0)
        private set

    private val builtIn = mapOf(
        6346 to TagName("japanese", "language"),
        12227 to TagName("english", "language"),
        29963 to TagName("chinese", "language"),
        17249 to TagName("translated", "language"),
        33173 to TagName("manga", "category"),
    )

    fun init(context: Context) {
        cacheFile = File(File(context.filesDir, "Tags"), "tags.json")
    }

    fun tag(id: Int): TagName? = tags[id] ?: builtIn[id]

    /** The name to show for a tag (translated when the setting is on). */
    fun displayName(name: String, type: String): String {
        revision
        if (!AppSettings.translateTags) return name
        return translate(name, type) ?: name
    }

    fun translate(name: String, type: String): String? {
        val namespaces = when (type) {
            "tag" -> listOf("female", "male", "mixed", "other", "location", "cosplayer")
            "category" -> listOf("reclass")
            else -> listOf(type)
        }
        for (namespace in namespaces) {
            translations[namespace]?.get(name)?.takeIf { it.isNotEmpty() }?.let { return it }
        }
        return null
    }

    /** E-Hentai namespaces that have a tag called [name] (search syntax conversion). */
    fun ehNamespaces(name: String): List<String> {
        revision
        return translations.filter { (_, names) -> names.containsKey(name) }.keys.toList()
    }

    /**
     * E-Hentai tag suggestions for a typed word: tag names that start with
     * it, or whose translation contains it, as (namespace, name).
     */
    fun suggestEh(word: String, limit: Int = 12): List<Pair<String, String>> {
        revision
        val w = word.lowercase()
        if (w.length < 2) return emptyList()
        val skip = setOf("rows", "reclass")
        val starts = mutableListOf<Pair<String, String>>()
        val contains = mutableListOf<Pair<String, String>>()
        for ((ns, names) in translations) {
            if (ns in skip) continue
            for ((name, translated) in names) {
                when {
                    name.startsWith(w) -> starts += ns to name
                    translated.contains(word, ignoreCase = true) || name.contains(w) -> contains += ns to name
                }
                if (starts.size >= limit) break
            }
        }
        return (starts.sortedBy { it.second.length } + contains.sortedBy { it.second.length }).distinct().take(limit)
    }

    /** Tags shown on a card: languages first, then content tags. */
    fun cardTags(ids: List<Int>, limit: Int = 8): List<TagName> {
        revision
        val languages = mutableListOf<TagName>()
        val others = mutableListOf<TagName>()
        for (id in ids) {
            val tag = tag(id) ?: continue
            if (tag.type == "language") languages += tag else if (tag.type == "tag") others += tag
        }
        return (languages + others).take(limit)
    }

    /** Loads the cached database and refreshes it when missing or old. */
    suspend fun prepare() {
        if (prepared) return
        prepared = true
        withContext(Dispatchers.IO) { readCache() }?.let(::apply)
        val stale = lastUpdated?.let { System.currentTimeMillis() - it > 7L * 24 * 3600 * 1000 } ?: true
        if (stale || tags.isEmpty()) update()
    }

    suspend fun update() {
        if (isUpdating) return
        isUpdating = true
        try {
            withContext(Dispatchers.IO) { download() }?.let(::apply)
        } finally {
            isUpdating = false
        }
    }

    private fun apply(snapshot: TagSnapshot) {
        if (snapshot.tags.isNotEmpty()) tags = snapshot.tags
        if (snapshot.translations.isNotEmpty()) translations = snapshot.translations
        lastUpdated = snapshot.date
        tagCount = tags.size
        translationCount = translations.values.sumOf { it.size }
        revision++
    }

    private fun readCache(): TagSnapshot? = try {
        Net.json.decodeFromString(TagSnapshot.serializer(), cacheFile.readText())
    } catch (_: Exception) {
        null
    }

    private fun fetch(urls: List<String>): String? {
        for (url in urls) {
            try {
                Net.client.newCall(Request.Builder().url(url).build()).execute().use {
                    if (it.code == 200) return it.body.string()
                }
            } catch (_: Exception) {
            }
        }
        return null
    }

    private fun download(): TagSnapshot? {
        val lists = listOf(
            "tag" to "tags", "artist" to "artists", "group" to "groups",
            "parody" to "parodies", "character" to "characters",
        )
        val result = HashMap<Int, TagName>()
        for ((type, file) in lists) {
            val text = fetch(
                listOf(
                    "https://fastly.jsdelivr.net/gh/honjow/nhentai_tags_fetcher@master/assets/$file.json",
                    "https://raw.githubusercontent.com/honjow/nhentai_tags_fetcher/master/assets/$file.json",
                ),
            ) ?: return null
            val array = runCatching { Net.json.parseToJsonElement(text).arr() }.getOrNull() ?: return null
            for (element in array) {
                val o = element as? JsonObject ?: continue
                val id = o.int("id") ?: continue
                val name = o.str("name") ?: continue
                result[id] = TagName(name, type)
            }
        }
        val translated = HashMap<String, Map<String, String>>()
        fetch(
            listOf(
                "https://fastly.jsdelivr.net/gh/EhTagTranslation/DatabaseReleases/db.text.json",
                "https://cdn.jsdelivr.net/gh/EhTagTranslation/DatabaseReleases/db.text.json",
            ),
        )?.let { text ->
            runCatching {
                val data = Net.json.parseToJsonElement(text).obj()?.get("data").arr() ?: return@runCatching
                for (element in data) {
                    val namespace = element.obj() ?: continue
                    val key = namespace.str("namespace") ?: continue
                    val items = namespace["data"].obj() ?: continue
                    translated[key] = items.mapValues { (_, v) -> v.obj()?.str("name")?.trim() ?: "" }
                }
            }
        }
        if (translated.isEmpty()) readCache()?.translations?.let { translated.putAll(it) }
        val snapshot = TagSnapshot(result, translated, System.currentTimeMillis())
        runCatching {
            cacheFile.parentFile?.mkdirs()
            cacheFile.writeText(Net.json.encodeToString(TagSnapshot.serializer(), snapshot))
        }
        return snapshot
    }
}

// MARK: - Feeds

/** A paginated list of galleries with refresh and infinite scroll. */
/** One page of a feed; [next] is the cursor of the next page (null: no more). */
class FeedPage(val items: List<GallerySummary>, val next: String?, val total: Int? = null)

/** A feed of nhentai pages (page numbers as cursors); blacklisted galleries are left out. */
fun nhFeed(fetch: suspend (Int) -> PageResponse<GalleryItem>): suspend (String?) -> FeedPage = { cursor ->
    val page = cursor?.toIntOrNull() ?: 1
    val r = fetch(page)
    FeedPage(
        r.result.filter { !it.blacklisted }.map(GallerySummary::from),
        if (page < r.numPages) "${page + 1}" else null,
        r.total,
    )
}

/** A feed of an E-Hentai list (gallery-id cursors, or page numbers for toplists). */
fun ehFeed(source: () -> EhListSource): suspend (String?) -> FeedPage = { cursor ->
    val s = source()
    val r = EhClient.list(s, cursor)
    val next = if (s is EhListSource.Toplist) {
        val page = cursor?.toIntOrNull() ?: 0
        if (r.galleries.isNotEmpty() && (r.pageCount == null || page + 1 < r.pageCount!!)) "${page + 1}" else null
    } else {
        r.nextCursor
    }
    FeedPage(r.galleries.map(GallerySummary::from), next)
}

/** An endless list of galleries of either site, loaded page by page. */
class GalleryFeed(
    private val scope: CoroutineScope,
    var fetch: suspend (String?) -> FeedPage,
    /** Browsing lists leave out galleries hidden by the filter rules (not favorites). */
    private val filtered: Boolean = true,
) {
    val items = mutableStateListOf<GallerySummary>()
    var isLoading by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var hasLoaded by mutableStateOf(false)
        private set
    var total by mutableStateOf<Int?>(null)
        private set

    private var next: String? = null
    private val seen = HashSet<String>()
    private var generation = 0
    private var job: Job? = null

    val canLoadMore: Boolean get() = hasLoaded && next != null

    fun reload(): Job {
        if (filtered) Filters.refreshNhBlacklist()
        generation++
        val current = generation
        job?.cancel()
        isLoading = true
        error = null
        return scope.launch {
            try {
                val response = fetch(null)
                if (current != generation) return@launch
                next = response.next
                total = response.total
                seen.clear()
                items.clear()
                items.addAll(unique(response.items))
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                if (current == generation) error = e.localizedMessage
            }
            if (current == generation) {
                isLoading = false
                hasLoaded = true
            }
        }.also { job = it }
    }

    fun loadMoreIfNeeded(index: Int) {
        if (index >= items.size - 8) loadMore()
    }

    fun loadMore() {
        val cursor = next
        if (!canLoadMore || isLoading || cursor == null) return
        val current = generation
        isLoading = true
        job = scope.launch {
            try {
                val response = fetch(cursor)
                if (current != generation) return@launch
                next = response.next
                items.addAll(unique(response.items))
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                if (current == generation) error = e.localizedMessage
            }
            if (current == generation) isLoading = false
        }
    }

    private fun unique(list: List<GallerySummary>): List<GallerySummary> {
        val fresh = list.filter { seen.add(it.key.storageKey) }
        return if (filtered) Filters.visible(fresh) else fresh
    }
}
