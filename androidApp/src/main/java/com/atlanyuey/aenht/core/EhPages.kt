package com.atlanyuey.aenht.core

import android.content.Context
import androidx.compose.runtime.mutableStateMapOf
import coil3.ImageLoader
import coil3.decode.DataSource
import coil3.decode.ImageSource
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.SourceFetchResult
import coil3.key.Keyer
import coil3.request.Options
import com.atlanyuey.aenht.shared.eh.EhThumbnail
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Request
import okio.FileSystem
import okio.Path.Companion.toOkioPath
import java.io.File

/** A page of an E-Hentai gallery to show; its image address is looked up when loaded. */
data class EhPageRef(val gid: Long, val token: String, /** 1-based */ val page: Int)

/**
 * What the app knows about E-Hentai galleries' pages: their page links
 * (from the thumbnail pages, fetched as the reader gets to them), their
 * image sizes, and the images (cached on disk so that reading a page again
 * costs no request and no image quota).
 */
object EhPages {
    private lateinit var cacheDir: File
    private val links = mutableMapOf<Long, MutableMap<Int, String>>()
    private val perThumbnailPage = mutableMapOf<Long, Int>()
    private val locks = mutableMapOf<Long, Mutex>()
    private const val CACHE_LIMIT = 400L * 1024 * 1024

    /** width / height of pages whose image has been loaded ("gid-page"). */
    val ratios = mutableStateMapOf<String, Float>()

    fun init(context: Context) {
        cacheDir = File(context.cacheDir, "eh-pages")
    }

    /** Page links of the first thumbnail page (from the gallery page). */
    fun remember(gid: Long, thumbnails: List<EhThumbnail>) {
        if (thumbnails.isEmpty()) return
        synchronized(links) {
            val map = links.getOrPut(gid) { mutableMapOf() }
            thumbnails.forEach { map[it.page] = it.pageUrl }
            perThumbnailPage.putIfAbsent(gid, thumbnails.size)
        }
    }

    private fun lock(gid: Long) = synchronized(locks) { locks.getOrPut(gid) { Mutex() } }

    private suspend fun link(ref: EhPageRef): String {
        synchronized(links) { links[ref.gid]?.get(ref.page) }?.let { return it }
        return lock(ref.gid).withLock {
            synchronized(links) { links[ref.gid]?.get(ref.page) }?.let { return@withLock it }
            val per = synchronized(links) { perThumbnailPage[ref.gid] } ?: 20
            val thumbs = EhClient.thumbnails(ref.gid, ref.token, (ref.page - 1) / per)
            remember(ref.gid, thumbs)
            synchronized(links) { links[ref.gid]?.get(ref.page) } ?: throw NHException(L10n.string("This page isn't available."))
        }
    }

    private fun file(ref: EhPageRef) = File(cacheDir, "${ref.gid}/${ref.page}")

    /** The page's image on disk: from the cache, or downloaded (another node if the first fails). */
    suspend fun image(ref: EhPageRef): File {
        val file = file(ref)
        if (file.exists() && file.length() > 0) return file
        val pageLink = link(ref)
        var reloadKey: String? = null
        var lastError: Exception? = null
        repeat(3) {
            val page = EhClient.imagePage(pageLink, reloadKey)
            if (page.width > 0 && page.height > 0) ratios["${ref.gid}-${ref.page}"] = page.width.toFloat() / page.height
            try {
                if (download(page.imageUrl, file)) return file
            } catch (e: Exception) {
                lastError = e
            }
            reloadKey = page.reloadKey ?: return@repeat
        }
        throw lastError ?: NHException(L10n.string("Couldn't load the image."))
    }

    private suspend fun download(url: String, file: File): Boolean = withContext(Dispatchers.IO) {
        Net.client.newCall(Request.Builder().url(url).build()).execute().use { response ->
            if (response.code != 200) return@use false
            file.parentFile?.mkdirs()
            val temp = File(file.parentFile, file.name + ".part")
            temp.outputStream().use { out -> response.body.byteStream().copyTo(out) }
            (temp.length() > 0 && temp.renameTo(file)).also { if (it) trim() }
        }
    }

    /** Keeps the page cache under its size limit (oldest galleries go first). */
    private fun trim() {
        val dirs = cacheDir.listFiles()?.filter { it.isDirectory } ?: return
        var total = dirs.sumOf { d -> d.walkTopDown().filter { it.isFile }.sumOf { it.length() } }
        if (total <= CACHE_LIMIT) return
        dirs.sortedBy { it.lastModified() }.forEach { d ->
            if (total <= CACHE_LIMIT * 3 / 4) return
            total -= d.walkTopDown().filter { it.isFile }.sumOf { it.length() }
            d.deleteRecursively()
        }
    }

    fun clearCache() {
        cacheDir.deleteRecursively()
    }
}

/** Loads [EhPageRef]s for Coil (reader, previews of downloads). */
class EhPageFetcher(private val ref: EhPageRef) : Fetcher {
    override suspend fun fetch(): FetchResult {
        val file = EhPages.image(ref)
        return SourceFetchResult(
            source = ImageSource(file.toOkioPath(), FileSystem.SYSTEM),
            mimeType = null,
            dataSource = DataSource.NETWORK,
        )
    }

    class Factory : Fetcher.Factory<EhPageRef> {
        override fun create(data: EhPageRef, options: Options, imageLoader: ImageLoader) = EhPageFetcher(data)
    }
}

class EhPageKeyer : Keyer<EhPageRef> {
    override fun key(data: EhPageRef, options: Options) = "eh-page:${data.gid}:${data.page}"
}
