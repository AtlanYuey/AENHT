package com.atlanyuey.aenht.shared.db

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSApplicationSupportDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSUserDomainMask

/** The database in Application Support (not visible in Files, included in backups). */
@OptIn(ExperimentalForeignApi::class)
fun openDatabase(): AenhtDatabase {
    val dir = NSFileManager.defaultManager.URLForDirectory(
        directory = NSApplicationSupportDirectory,
        inDomain = NSUserDomainMask,
        appropriateForURL = null,
        create = true,
        error = null,
    )
    val path = requireNotNull(dir?.path) { "No Application Support directory" } + "/" + AenhtDatabase.FILE_NAME
    return Room.databaseBuilder<AenhtDatabase>(name = path).buildAenht(BundledSQLiteDriver())
}

/** For Swift: the app's database, opened once. */
object IosDatabase {
    val shared: AenhtDatabase by lazy { openDatabase() }
}
