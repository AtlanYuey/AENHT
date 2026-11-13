package com.atlanyuey.aenht.shared.db

import android.content.Context
import androidx.room.Room
import androidx.sqlite.driver.AndroidSQLiteDriver

fun openDatabase(context: Context): AenhtDatabase {
    val app = context.applicationContext
    return Room.databaseBuilder<AenhtDatabase>(app, app.getDatabasePath(AenhtDatabase.FILE_NAME).absolutePath)
        .buildAenht(AndroidSQLiteDriver())
}
