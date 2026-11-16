package com.atlanyuey.aenht.shared.db

import androidx.room.immediateTransaction
import androidx.room.useWriterConnection
import com.atlanyuey.aenht.shared.site.GalleryKey
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Clock

/** Reading history of both sites, newest first. */
class HistoryRepository internal constructor(private val db: AenhtDatabase, private val now: () -> Long) {
    constructor(db: AenhtDatabase) : this(db, { Clock.System.now().toEpochMilliseconds() })

    @Throws(CancellationException::class)
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
        rating: Double = 0.0,
        /** E-Hentai: the cover's language badge and category label (cards). */
        language: String = "",
        category: String = "",
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
            rating = if (rating > 0) rating else previous?.rating ?: 0.0,
            language = language.ifEmpty { previous?.language ?: "" },
            category = category.ifEmpty { previous?.category ?: "" },
        )
        db.history().upsert(record)
        return record
    }

    /**
     * Copies history kept elsewhere (the iOS app's own store) in, before a
     * WebDAV sync: a record replaces the one here only when it was read later.
     */
    @Throws(CancellationException::class)
    suspend fun put(records: List<HistoryRecord>) {
        for (record in records) {
            val mine = db.history().get(record.key)
            if (mine == null || record.readAt > mine.readAt) db.history().upsert(record)
        }
    }

    /** Deletes by storage key ("nh:1", "eh:2:token"), remembering it for WebDAV sync. */
    @Throws(CancellationException::class)
    suspend fun delete(storageKey: String) {
        db.useWriterConnection { writer -> writer.immediateTransaction {
            db.history().delete(storageKey)
            db.tombstones().upsert(listOf(Tombstone(Tombstone.HISTORY, storageKey, now())))
        } }
    }

    suspend fun delete(key: GalleryKey) = delete(key.storageKey)

    @Throws(CancellationException::class)
    suspend fun clear() {
        db.useWriterConnection { writer -> writer.immediateTransaction {
            db.history().clear()
            db.tombstones().upsert(listOf(Tombstone(Tombstone.HISTORY, Tombstone.ALL, now())))
        } }
    }
}
