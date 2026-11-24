package com.atlanyuey.aenht.core

import android.content.Context
import com.atlanyuey.aenht.shared.app.Diagnostics
import java.io.File

/**
 * A crash's report kept across the crash: the uncaught exception is written
 * to a file before the app closes, and put in the diagnostics log (About ›
 * Diagnostics, secrets taken out) when the app opens again. The log itself
 * is in memory only, so without this a crash left nothing to share.
 */
object CrashLog {
    private const val FILE = "last-crash.txt"

    fun install(context: Context) {
        val file = File(context.filesDir, FILE)
        // The crash of the time before: into this session's log.
        runCatching {
            if (file.exists()) {
                Diagnostics.log("crash", "The app closed after an error last time:\n" + file.readText().take(6000))
                file.delete()
            }
        }
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                val report = buildString {
                    append("Thread: ").append(thread.name).append('\n')
                    append(android.util.Log.getStackTraceString(error))
                    // What the log had just before.
                    val recent = Diagnostics.entries().takeLast(20)
                    if (recent.isNotEmpty()) append("\nBefore it:\n").append(recent.joinToString("\n"))
                }
                file.writeText(Diagnostics.redact(report))
            }
            previous?.uncaughtException(thread, error)
        }
    }
}
