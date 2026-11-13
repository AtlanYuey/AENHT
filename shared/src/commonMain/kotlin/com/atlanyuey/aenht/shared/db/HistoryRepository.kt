package com.atlanyuey.aenht.shared.db

import com.atlanyuey.aenht.shared.site.GalleryKey
import kotlin.time.Clock

/** Reading history of both sites, newest first. */
class HistoryRepository(private val db: AenhtDatabase, private val now: () -> Long = { Clock.System.now().toEpochMilliseconds() }) {
    suspend fun all(): List<HistoryRecord> = db.history().all()

    fun observe() = db.history().observeAll()

    /**
     * Records that a gallery was opened (or read up to [page]). Fields the
     * caller doesn't know (0 / null / empty) keep their previous values.
     */
    suspend fun record(
        key: GalleryKey,
        title: String,
        mediaId: String = "",
        thumbnail: String? = null,
        thumbWidth: Int = 0,
        thumbHeight: Int = 0,
        numPages: Int = 0,
        page: Int? = null,
        tagIds: List<Int> = emptyList(),
    ): HistoryRecord {
        val previous = db.history().get(key.storageKey)
        val record = HistoryRecord(
            key = key.storageKey,
            site = key.site.key,
            galleryId = key.id,
            token = key.token,
            mediaId = mediaId.ifEmpty { previous?.mediaId ?: "" },
            title = title.ifEmpty { previous?.title ?: "" },
            thumbnail = thumbnail ?: previous?.thumbnail,
            thumbWidth = if (thumbWidth > 0) thumbWidth else previous?.thumbWidth ?: 0,
            thumbHeight = if (thumbHeight > 0) thumbHeight else previous?.thumbHeight ?: 0,
            numPages = if (numPages > 0) numPages else previous?.numPages ?: 0,
            lastPage = page ?: previous?.lastPage ?: 0,
            readAt = now(),
            tagIds = tagIds.takeIf { it.isNotEmpty() }?.joinToString(",") ?: previous?.tagIds ?: "",
        )
        db.history().upsert(record)
        return record
    }

    suspend fun delete(key: GalleryKey) = db.history().delete(key.storageKey)

    suspend fun clear() = db.history().clear()
}
