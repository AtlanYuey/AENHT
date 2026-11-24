package com.atlanyuey.aenht

import android.content.Intent
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import coil3.ImageLoader
import coil3.SingletonImageLoader
import com.atlanyuey.aenht.core.Net
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.random.Random

/**
 * The whole app on a real Android, under a long run of random input (like
 * Android's monkey): taps anywhere, on the tab bar (once and twice), swipes,
 * long presses, back. The sites are the tests' sample ones (no network).
 * A crash ends the test process: its stack trace is in the log CI keeps.
 */
@RunWith(AndroidJUnit4::class)
class DeviceStressTest {
    private val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
    private val pkg = "com.atlanyuey.ENHentai"

    @Before fun sampleSites() {
        if (FakeApi !in Net.interceptors) Net.interceptors.add(FakeApi)
        SingletonImageLoader.setUnsafe(ImageLoader.Builder(ApplicationProvider.getApplicationContext()).components { add(FakeImages) }.build())
    }

    private fun launch() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val intent = context.packageManager.getLaunchIntentForPackage(pkg)!!.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        device.wait(Until.hasObject(By.pkg(pkg).depth(0)), 10_000)
    }

    private fun run(seed: Int, events: Int) {
        launch()
        val random = Random(seed)
        val w = device.displayWidth
        val h = device.displayHeight
        // The tab bar: four buttons along the bottom.
        val barY = h - (h * 0.06).toInt()
        val tabXs = listOf(0.2, 0.4, 0.6, 0.8).map { (w * it).toInt() }
        repeat(events) { step ->
            if (device.currentPackageName != pkg) {
                // Left the app (back on the first page, a share sheet, a browser…): back in.
                device.pressBack()
                if (device.currentPackageName != pkg) launch()
            }
            val roll = random.nextInt(100)
            val kind = when {
                roll < 40 -> { device.click(random.nextInt(w), random.nextInt((h * 0.12).toInt(), (h * 0.9).toInt())); "tap" }
                roll < 55 -> { device.click(tabXs[random.nextInt(4)], barY); "tab" }
                roll < 62 -> { val x = tabXs[random.nextInt(4)]; device.click(x, barY); device.click(x, barY); "tab twice" }
                roll < 80 -> {
                    val x = random.nextInt(w / 5, w * 4 / 5)
                    if (random.nextBoolean()) device.swipe(x, (h * 0.7).toInt(), x, (h * 0.3).toInt(), 8)
                    else device.swipe((w * 0.8).toInt(), h / 2, (w * 0.2).toInt(), h / 2, 8)
                    "swipe"
                }
                roll < 88 -> { device.pressBack(); "back" }
                roll < 93 -> { device.swipe(random.nextInt(w), random.nextInt(h / 4, h * 3 / 4), random.nextInt(w), random.nextInt(h / 4, h * 3 / 4), 120); "long press" }
                else -> { device.swipe(w / 2, (h * 0.3).toInt(), w / 2, (h * 0.75).toInt(), 10); "pull" }
            }
            if (step % 100 == 0) Log.i("AENHT-STRESS", "seed $seed step $step ($kind)")
            Thread.sleep(random.nextLong(40, 260))
        }
        Log.i("AENHT-STRESS", "seed $seed done")
        assertTrue("the app is still running", device.wait(Until.hasObject(By.pkg(pkg)), 5000) || device.currentPackageName == pkg)
    }

    @Test fun randomInputSeed1() = run(seed = 1, events = 1200)

    @Test fun randomInputSeed2() = run(seed = 2, events = 1200)
}
