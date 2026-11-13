package com.atlanyuey.aenht.shared.db

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver

/** In memory (tests). */
fun openInMemoryDatabase(): AenhtDatabase = Room.inMemoryDatabaseBuilder<AenhtDatabase>().buildAenht(BundledSQLiteDriver())
