package com.atlanyuey.aenht.shared

import androidx.room.Room
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteDriver
import androidx.sqlite.SQLiteStatement
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.atlanyuey.aenht.shared.db.*
import com.atlanyuey.aenht.shared.net.buildHttpClient
import com.atlanyuey.aenht.shared.site.GalleryKey
import com.atlanyuey.aenht.shared.sync.SyncFile
import com.atlanyuey.aenht.shared.sync.WebDavSync
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertTrue

/** Actual Room + production repository/merge; fail only the tombstone SQL write. */
class PersistenceAtomicityTest {
    private class FaultDriver : SQLiteDriver {
        private val real = BundledSQLiteDriver()
        var refuseTombstone = false
        override fun open(fileName: String): SQLiteConnection {
            val connection = real.open(fileName)
            return object : SQLiteConnection by connection {
                override fun prepare(sql: String): SQLiteStatement {
                    if (refuseTombstone && sql.trimStart().startsWith("INSERT", true) && "sync_tombstones" in sql) {
                        throw IllegalStateException("audit: simulated failure writing the tombstone")
                    }
                    return connection.prepare(sql)
                }
            }
        }
    }

    @Test fun failedClearKeepsAllRowsAndDoesNotCommitAWildcard() = runTest {
        val driver = FaultDriver()
        val db = Room.inMemoryDatabaseBuilder<AenhtDatabase>().buildAenht(driver)
        try {
            val repository = HistoryRepository(db) { 200L }
            repository.record(GalleryKey.nh(980003), "Synthetic first")
            repository.record(GalleryKey.nh(980004), "Synthetic second")
            driver.refuseTombstone = true
            assertTrue(runCatching { repository.clear() }.isFailure)
            driver.refuseTombstone = false
            kotlin.test.assertEquals(setOf("nh:980003", "nh:980004"), repository.all().map { it.key }.toSet())
            assertTrue(db.tombstones().all().none { it.kind == Tombstone.HISTORY && it.key == Tombstone.ALL })
        } finally { db.close() }
    }

    @Test fun failedFavoriteDeletionKeepsItsMirrorRow() = runTest {
        val driver = FaultDriver()
        val db = Room.inMemoryDatabaseBuilder<AenhtDatabase>().buildAenht(driver)
        val http = buildHttpClient(MockEngine { respond("{}") })
        try {
            val favorite = FavoriteRecord(
                "nh:980005", "nh", 980005L, null, "9", "Synthetic favorite", null,
                0, 0, 1, 0, "account", 100L, true,
            )
            db.favorites().upsert(listOf(favorite))
            val mirror = FavoritesMirror(db,
                com.atlanyuey.aenht.shared.nh.NhApi(MemoryTokens(null, null), http),
                com.atlanyuey.aenht.shared.eh.EhApi(MemoryEhCredentials(), http)) { 200L }
            driver.refuseTombstone = true
            assertTrue(runCatching { mirror.removed(GalleryKey.nh(980005)) }.isFailure)
            driver.refuseTombstone = false
            kotlin.test.assertEquals(favorite, db.favorites().get(favorite.key))
            assertTrue(db.tombstones().all().none { it.key == favorite.key })
        } finally { http.close(); db.close() }
    }

    @Test fun aFailedTombstoneWriteMustNotCommitAHalfDeletion() = runTest {
        var refuseTombstone = false
        val real = BundledSQLiteDriver()
        val driver = object : SQLiteDriver by real {
            override fun open(fileName: String): SQLiteConnection {
                val connection = real.open(fileName)
                return object : SQLiteConnection by connection {
                    override fun prepare(sql: String): SQLiteStatement {
                        if (refuseTombstone && sql.trimStart().startsWith("INSERT", true) && "sync_tombstones" in sql) {
                            throw IllegalStateException("audit: simulated failure writing the tombstone")
                        }
                        return connection.prepare(sql)
                    }
                }
            }
        }
        val db = Room.inMemoryDatabaseBuilder<AenhtDatabase>().buildAenht(driver)
        val http = buildHttpClient(MockEngine { respond("{}") })
        try {
            val repository = HistoryRepository(db) { 200L }
            val key = GalleryKey.nh(980002)
            val old = repository.record(key, "Synthetic previously synced history")
            refuseTombstone = true
            val result = runCatching { repository.delete(key) }
            refuseTombstone = false
            val retained = db.history().get(key.storageKey) != null
            val marked = db.tombstones().all().any { it.kind == Tombstone.HISTORY && it.key == key.storageKey }
            WebDavSync(db, http) { 1_000L }.merge(SyncFile(history = listOf(old)), 0L)
            println("delete_failed=${result.isFailure} local_row_retained=$retained tombstone_present=$marked after_old_remote_merge=${db.history().get(key.storageKey) != null}")
            // Correct implementations may roll the deletion back, or commit it with a marker.
            // They must not leave a locally deleted record without its sync deletion marker.
            assertTrue(retained || marked, "Deleting history and its sync tombstone must be atomic")
        } finally { http.close(); db.close() }
    }
}
