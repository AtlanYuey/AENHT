package com.atlanyuey.aenht

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import com.atlanyuey.aenht.core.Downloads
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn
import kotlinx.coroutines.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The real download worker, controlled blocking HTTP and actual file commits; no server. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DownloadCancellationTest {
    private lateinit var client: okhttp3.OkHttpClient

    @Test fun anImmediateMetadataFailureDoesNotBlockTheNextResume() {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        com.atlanyuey.aenht.core.AppSettings.init(context)
        Downloads.init(context)
        Downloads.records.clear()
        val record = com.atlanyuey.aenht.core.DownloadRecord(
            id = 990014, mediaId = "", title = "Synthetic recoverable metadata", thumbnail = null,
            thumbWidth = 0, thumbHeight = 0, pages = emptyList(), completed = 0,
            state = "paused", added = 0L, site = "eh", token = null, pageCount = 1,
        )
        Downloads.records.add(record)
        fun waitForState(state: String) {
            val deadline = System.nanoTime() + 5_000_000_000L
            while (Downloads.record(record.key)?.state != state && System.nanoTime() < deadline) {
                org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
                Thread.sleep(10)
            }
            assertEquals(state, Downloads.record(record.key)?.state)
        }
        try {
            Downloads.resume(record.key) // Missing gallery token: failure before the first suspension.
            waitForState("failed")
            Downloads.records[0] = Downloads.records[0].copy(pageCount = 0)
            Downloads.resume(record.key) // Corrected metadata; no network pages are required.
            waitForState("finished")
        } finally { Downloads.delete(record.key) }
    }
    private fun picture(color: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(color)
        return ByteArrayOutputStream().use { stream ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
            bitmap.recycle()
            stream.toByteArray()
        }
    }

    // Reflection only exposes the unmodified suspend worker to the test.
    private suspend fun fetch(url: String, file: File): Boolean = suspendCoroutineUninterceptedOrReturn { continuation ->
        val method = Downloads::class.java.getDeclaredMethod("fetch", String::class.java, File::class.java, Int::class.javaPrimitiveType, okhttp3.OkHttpClient::class.java, Continuation::class.java)
        method.isAccessible = true
        method.invoke(Downloads, url, file, 1, client, continuation)
    }

    @Test fun aCanceledOldDownloadMustNotReplaceANewerCommittedPage() = runBlocking {
        val entered = CountDownLatch(1)
        val released = CountDownLatch(1)
        val old = picture(Color.RED)
        val newer = picture(Color.BLUE)
        client = okhttp3.OkHttpClient.Builder().addInterceptor { chain ->
            if (chain.request().url.encodedPath == "/old") {
                entered.countDown()
                check(released.await(10, TimeUnit.SECONDS)) { "audit controlled HTTP timeout" }
            }
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(old.toResponseBody("image/png".toMediaType())).build()
        }.build()
        val dir = Files.createTempDirectory("aenht-download-cancel-").toFile()
        try {
            val file = File(dir, "0001.png")
            assertTrue(fetch("https://fixture.invalid/control", file))
            assertArrayEquals(old, file.readBytes())
            file.delete()
            val waiting = launch(Dispatchers.Default) { fetch("https://fixture.invalid/old", file) }
            assertTrue(entered.await(10, TimeUnit.SECONDS))
            waiting.cancel() // Downloads.pause/delete cancels the worker's parent job in the same way.
            file.writeBytes(newer) // A newer run has committed its page before the old HTTP call returns.
            released.countDown()
            waiting.join()
            val changed = !newer.contentEquals(file.readBytes())
            println("old_job_cancelled=${waiting.isCancelled} newer_committed_page_overwritten=$changed")
            assertArrayEquals("Canceled HTTP work must not overwrite a newer run's committed page", newer, file.readBytes())
        } finally { released.countDown(); dir.deleteRecursively(); client.dispatcher.executorService.shutdown() }
    }
}
