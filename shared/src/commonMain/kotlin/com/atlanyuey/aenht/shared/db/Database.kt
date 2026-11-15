package com.atlanyuey.aenht.shared.db

import androidx.room.AutoMigration
import androidx.room.ColumnInfo
import kotlinx.serialization.Serializable

import androidx.room.ConstructedBy
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.RoomDatabaseConstructor
import androidx.room.Upsert
import androidx.sqlite.SQLiteDriver
import com.atlanyuey.aenht.shared.site.GalleryKey
import com.atlanyuey.aenht.shared.site.Site
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.Flow

/**
 * A gallery the user opened, on either site, with the last page read.
 * [key] is [GalleryKey.storageKey]; [site] repeats its site for filtering.
 */
@Serializable
@Entity(tableName = "history", indices = [Index("readAt"), Index("site")])
data class HistoryRecord(
    @androidx.room.PrimaryKey val key: String,
    val site: String,
    val galleryId: Long,
    val token: String?,
    /** nhentai's media id (image paths); empty for E-Hentai. */
    val mediaId: String,
    val title: String,
    val thumbnail: String?,
    val thumbWidth: Int,
    val thumbHeight: Int,
    val numPages: Int,
    val lastPage: Int,
    /** Epoch milliseconds. */
    val readAt: Long,
    /** nhentai tag ids, comma separated (language badge, card tags). */
    val tagIds: String = "",
    /** E-Hentai's rating when last seen (0: none), for the card. */
    @ColumnInfo(defaultValue = "0") val rating: Double = 0.0,
    /** E-Hentai: the cover's language badge ("JP", "ZH"…) and category label, for the card. */
    @ColumnInfo(defaultValue = "") val language: String = "",
    @ColumnInfo(defaultValue = "") val category: String = "",
) {
    val galleryKey: GalleryKey get() = GalleryKey(Site.fromKey(site) ?: Site.NHENTAI, galleryId, token)
    val tagIdList: List<Int> get() = tagIds.split(',').mapNotNull { it.toIntOrNull() }
}

/** A submitted search, shared by both sites (the site it was run on is kept). */
@Entity(tableName = "search_history", indices = [Index("usedAt")])
data class SearchHistoryRecord(
    @androidx.room.PrimaryKey val query: String,
    val site: String,
    val usedAt: Long,
)

/**
 * A local copy of an account favorite (both sites), or a local-only favorite.
 * nhentai doesn't say when a gallery was favorited: [favoritedAt] is then
 * estimated from the list order on the first sync ([exactTime] = false) and
 * exact for favorites added or first seen later.
 */
@Serializable
@Entity(tableName = "favorites", indices = [Index("favoritedAt"), Index("site")])
data class FavoriteRecord(
    @androidx.room.PrimaryKey val key: String,
    val site: String,
    val galleryId: Long,
    val token: String?,
    val mediaId: String,
    val title: String,
    val thumbnail: String?,
    val thumbWidth: Int,
    val thumbHeight: Int,
    val numPages: Int,
    /** E-Hentai favorite folder 0–9; 0 for nhentai. */
    val folder: Int,
    /** "account" (synced with the site) or "local". */
    val source: String,
    val favoritedAt: Long,
    val exactTime: Boolean,
    val note: String? = null,
    val tagIds: String = "",
    /** E-Hentai's rating when last seen (0: none), for the card. */
    @ColumnInfo(defaultValue = "0") val rating: Double = 0.0,
    /** E-Hentai: the cover's language badge ("JP", "ZH"…) and category label, for the card. */
    @ColumnInfo(defaultValue = "") val language: String = "",
    @ColumnInfo(defaultValue = "") val category: String = "",
)

@Dao
interface HistoryDao {
    @Query("SELECT * FROM history ORDER BY readAt DESC")
    fun observeAll(): Flow<List<HistoryRecord>>

    @Query("SELECT * FROM history ORDER BY readAt DESC")
    suspend fun all(): List<HistoryRecord>

    @Query("SELECT * FROM history WHERE `key` = :key")
    suspend fun get(key: String): HistoryRecord?

    @Upsert
    suspend fun upsert(record: HistoryRecord)

    @Query("DELETE FROM history WHERE `key` = :key")
    suspend fun delete(key: String)

    @Query("DELETE FROM history")
    suspend fun clear()
}

@Dao
interface SearchHistoryDao {
    @Query("SELECT * FROM search_history ORDER BY usedAt DESC LIMIT :limit")
    suspend fun recent(limit: Int = 50): List<SearchHistoryRecord>

    @Upsert
    suspend fun upsert(record: SearchHistoryRecord)

    @Query("DELETE FROM search_history WHERE `query` = :query")
    suspend fun delete(query: String)

    @Query("DELETE FROM search_history")
    suspend fun clear()
}

@Dao
interface FavoriteDao {
    @Query("SELECT * FROM favorites ORDER BY favoritedAt DESC")
    fun observeAll(): Flow<List<FavoriteRecord>>

    @Query("SELECT * FROM favorites ORDER BY favoritedAt DESC")
    suspend fun all(): List<FavoriteRecord>

    @Query("SELECT * FROM favorites WHERE site = :site ORDER BY favoritedAt DESC")
    suspend fun bySite(site: String): List<FavoriteRecord>

    @Query("SELECT * FROM favorites WHERE `key` = :key")
    suspend fun get(key: String): FavoriteRecord?

    @Upsert
    suspend fun upsert(records: List<FavoriteRecord>)

    @Query("DELETE FROM favorites WHERE `key` = :key")
    suspend fun delete(key: String)
}

/**
 * Something deleted on this device, so that WebDAV sync doesn't bring it
 * back from another device: [kind] "history" (a gallery's history entry;
 * [key] "*" for clearing all) or "favorite" (a local favorite).
 */
@Serializable
@Entity(tableName = "sync_tombstones", primaryKeys = ["kind", "key"])
data class Tombstone(val kind: String, val key: String, val deletedAt: Long) {
    companion object {
        const val HISTORY = "history"
        const val FAVORITE = "favorite"
        const val ALL = "*"
    }
}

@Dao
interface TombstoneDao {
    @Query("SELECT * FROM sync_tombstones")
    suspend fun all(): List<Tombstone>

    @Upsert
    suspend fun upsert(records: List<Tombstone>)

    @Query("DELETE FROM sync_tombstones WHERE deletedAt < :before")
    suspend fun prune(before: Long)
}

@Database(
    entities = [HistoryRecord::class, SearchHistoryRecord::class, FavoriteRecord::class, Tombstone::class],
    version = 4,
    autoMigrations = [AutoMigration(from = 1, to = 2), AutoMigration(from = 2, to = 3), AutoMigration(from = 3, to = 4)],
)
@ConstructedBy(AenhtDatabaseConstructor::class)
abstract class AenhtDatabase : RoomDatabase() {
    abstract fun history(): HistoryDao
    abstract fun searchHistory(): SearchHistoryDao
    abstract fun favorites(): FavoriteDao
    abstract fun tombstones(): TombstoneDao

    companion object {
        const val FILE_NAME = "aenht.db"
    }
}

// Room generates the actual implementations.
@Suppress("KotlinNoActualForExpect")
expect object AenhtDatabaseConstructor : RoomDatabaseConstructor<AenhtDatabase> {
    override fun initialize(): AenhtDatabase
}

/** Finishes a platform builder with that platform's SQLite driver and the I/O dispatcher. */
internal fun RoomDatabase.Builder<AenhtDatabase>.buildAenht(driver: SQLiteDriver): AenhtDatabase =
    setDriver(driver)
        .setQueryCoroutineContext(Dispatchers.IO)
        .build()
