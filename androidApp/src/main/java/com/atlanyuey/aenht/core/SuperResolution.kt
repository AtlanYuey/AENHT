package com.atlanyuey.aenht.core

import android.content.Context
import android.graphics.Bitmap
import android.util.LruCache
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

// Super-resolution in the reader: low-resolution pages are enlarged to the
// screen's resolution with Real-ESRGAN (anime models, BSD-3-Clause) on the
// device, with ncnn (GPU through Vulkan, or the CPU). The page shows at
// once and turns sharper when it's ready.

/** When the reader sharpens pages (Reading settings › Display). */
enum class SrMode(val raw: String, val title: String, val threshold: Float?) {
    Off("off", "Off", null),
    Auto("auto", "Automatic", 1.25f),
    Always("always", "Always", 1.02f);

    companion object {
        fun of(raw: String?) = entries.firstOrNull { it.raw == raw } ?: Auto
    }
}

/** The model: Standard comes with the app, High Quality is downloaded. */
enum class SrModel(val raw: String, val title: String) {
    Standard("standard", "Standard"),
    High("high", "High Quality");

    companion object {
        fun of(raw: String?) = entries.firstOrNull { it.raw == raw } ?: Standard
    }
}

/** The native part (src/main/cpp/sr.cpp). */
internal object SrNative {
    val loaded: Boolean = runCatching {
        System.loadLibrary("ncnn")
        System.loadLibrary("aenhtsr")
    }.isSuccess

    @JvmStatic external fun loadAssets(assets: android.content.res.AssetManager, param: String, model: String): Long
    @JvmStatic external fun loadFiles(param: String, model: String): Long
    @JvmStatic external fun release(handle: Long)
    @JvmStatic external fun usesGpu(): Boolean
    @JvmStatic external fun tile(
        handle: Long, input: Bitmap,
        rx: Int, ry: Int, rw: Int, rh: Int,
        cx: Int, cy: Int, cw: Int, ch: Int,
        output: Bitmap, dx0: Int, dy0: Int, dx1: Int, dy1: Int,
    ): Boolean
}

object SuperResolution {
    private const val TILE = 256
    /** Context around each tile, cut off again (no seams). */
    private const val PAD = 12

    /** One page at a time: the GPU (or the CPU) is busy enough. */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val worker = Dispatchers.Default.limitedParallelism(1)
    private val engines = mutableMapOf<SrModel, Long>()
    private var writes = 0

    val available: Boolean get() = SrNative.loaded

    private val memory = object : LruCache<String, Bitmap>(96 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount
    }

    private fun cacheDir(context: Context) = File(context.cacheDir, "super_resolution")

    fun clearCache(context: Context) {
        memory.evictAll()
        cacheDir(context).deleteRecursively()
    }

    fun forget(model: SrModel) {
        engines.remove(model)?.let { handle -> CoroutineScope(worker).launch { SrNative.release(handle) } }
    }

    private fun engine(context: Context, model: SrModel): Long {
        engines[model]?.let { return it }
        val handle = when (model) {
            SrModel.Standard -> SrNative.loadAssets(context.assets, "sr/AnimeSR.param", "sr/AnimeSR.bin")
            SrModel.High -> {
                val files = SrModels.files(context)
                if (!SrModels.isReady(context)) 0L else SrNative.loadFiles(files.first.path, files.second.path)
            }
        }
        if (handle != 0L) engines[model] = handle
        return handle
    }

    /**
     * [bitmap] enlarged to fit [targetWidth] × [targetHeight] pixels, or null
     * when it's sharp enough already (or super-resolution is off).
     */
    suspend fun enhance(context: Context, bitmap: Bitmap, key: String, targetWidth: Int, targetHeight: Int,
                        mode: SrMode, model: SrModel): Bitmap? {
        if (!available) return null
        val plan = plan(bitmap.width, bitmap.height, key, targetWidth, targetHeight, mode, model) ?: return null
        memory.get(plan.name)?.let { return it }
        return withContext(worker) {
            // A reader request can queue behind a job that just prepared the same image.
            memory.get(plan.name)?.let { return@withContext it }
            val file = File(cacheDir(context), plan.name)
            if (file.exists()) {
                android.graphics.BitmapFactory.decodeFile(file.path)?.let {
                    memory.put(plan.name, it)
                    return@withContext it
                }
            }
            runCatching {
                val result = run(context, model, bitmap, plan.scale, plan.width, plan.height) ?: return@runCatching null
                coroutineContext.ensureActive()
                memory.put(plan.name, result)
                save(context, result, file)
                result
            }.getOrElse { if (it is kotlinx.coroutines.CancellationException) throw it else null }
        }
    }

    fun cached(width: Int, height: Int, key: String, targetWidth: Int, targetHeight: Int,
               mode: SrMode, model: SrModel): Bitmap? =
        plan(width, height, key, targetWidth, targetHeight, mode, model)?.let { memory.get(it.name) }

    private data class Plan(val scale: Float, val width: Int, val height: Int, val name: String)

    private fun plan(width: Int, height: Int, key: String, targetWidth: Int, targetHeight: Int,
                     mode: SrMode, model: SrModel): Plan? {
        val threshold = mode.threshold ?: return null
        if (width <= 16 || height <= 16) return null
        val fit = min(targetWidth.toFloat() / width, targetHeight.toFloat() / height)
        if (fit < threshold) return null
        // At most 4× (the model's), and at most 24 megapixels.
        val scale = min(min(fit, 4f), sqrt(24_000_000f / (width.toFloat() * height)))
        if (scale < threshold) return null
        val outWidth = (width * scale).roundToInt()
        val outHeight = (height * scale).roundToInt()

        return Plan(scale, outWidth, outHeight, cacheName(key, model, outWidth))
    }

    private suspend fun run(context: Context, model: SrModel, page: Bitmap, scale: Float,
                            outWidth: Int, outHeight: Int): Bitmap? {
        val handle = engine(context, model)
        if (handle == 0L) return null
        val input = if (page.config == Bitmap.Config.ARGB_8888) page else page.copy(Bitmap.Config.ARGB_8888, false) ?: return null
        val output = Bitmap.createBitmap(outWidth, outHeight, Bitmap.Config.ARGB_8888)
        val width = input.width
        val height = input.height
        val core = TILE - 2 * PAD
        var y0 = 0
        while (y0 < height) {
            var x0 = 0
            while (x0 < width) {
                coroutineContext.ensureActive()
                val cw = min(core, width - x0)
                val ch = min(core, height - y0)
                val rx = maxOf(0, x0 - PAD)
                val ry = maxOf(0, y0 - PAD)
                val rw = min(width, x0 + cw + PAD) - rx
                val rh = min(height, y0 + ch + PAD) - ry
                // Whole pixels, so neighbouring tiles meet exactly.
                val dx0 = (x0 * scale).roundToInt()
                val dx1 = ((x0 + cw) * scale).roundToInt().coerceAtMost(outWidth)
                val dy0 = (y0 * scale).roundToInt()
                val dy1 = ((y0 + ch) * scale).roundToInt().coerceAtMost(outHeight)
                if (dx1 > dx0 && dy1 > dy0) {
                    if (!SrNative.tile(handle, input, rx, ry, rw, rh, x0, y0, cw, ch, output, dx0, dy0, dx1, dy1)) {
                        output.recycle()
                        return null
                    }
                }
                x0 += core
            }
            y0 += core
        }
        return output
    }

    private fun cacheName(key: String, model: SrModel, width: Int): String {
        val digest = MessageDigest.getInstance("SHA-256").digest("$key|${model.raw}|$width".toByteArray())
        return digest.take(16).joinToString("") { "%02x".format(it) } + ".jpg"
    }

    private fun save(context: Context, bitmap: Bitmap, file: File) {
        runCatching {
            file.parentFile?.mkdirs()
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 92, it) }
            if (writes++ % 20 == 0) prune(cacheDir(context), 300L * 1024 * 1024)
        }
    }

    /** Keeps the newest results within [limit] bytes. */
    private fun prune(dir: File, limit: Long) {
        var total = 0L
        dir.listFiles()?.sortedByDescending { it.lastModified() }?.forEach { file ->
            total += file.length()
            if (total > limit) file.delete()
        }
    }
}

/** The High Quality model: downloaded and checked on request. */
object SrModels {
    sealed interface State {
        data object Absent : State
        data class Downloading(val fraction: Float) : State
        data object Ready : State
        data class Failed(val message: String) : State
    }

    private const val BASE = "https://raw.githubusercontent.com/AtlanYuey/AENHT/e729c7862f525c274b4ee5f0721620cf792acdde/models/sr/"
    private const val PARAM_SHA = "2b8fb6e0ae4d2d85704ca08c119a2f5ea40add4f2ecd512eb7f4cd44b6127ed4"
    private const val BIN_SHA = "fe01c269cfd10cdef8e018ab66ebe750cf79c7af4d1f9c16c737e1295229bacc"
    const val SIZE_BYTES = 8_973_790L

    var state by mutableStateOf<State>(State.Absent)
        private set

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var job: Job? = null

    fun files(context: Context): Pair<File, File> {
        val dir = File(context.filesDir, "super_resolution")
        return File(dir, "AnimeSRPlus.param") to File(dir, "AnimeSRPlus.bin")
    }

    fun isReady(context: Context): Boolean = files(context).let { (p, b) -> p.exists() && b.exists() }

    fun refresh(context: Context) {
        if (job?.isActive != true) state = if (isReady(context)) State.Ready else State.Absent
    }

    fun download(context: Context) {
        if (job?.isActive == true || isReady(context)) return
        val app = context.applicationContext
        state = State.Downloading(0f)
        job = scope.launch {
            state = runCatching {
                withContext(Dispatchers.IO) { install(app) }
                State.Ready
            }.getOrElse { if (it is kotlinx.coroutines.CancellationException) State.Absent else State.Failed(it.message ?: it.toString()) }
        }
    }

    fun cancel() {
        job?.cancel()
    }

    fun delete(context: Context) {
        job?.cancel()
        files(context).let { (p, b) -> p.delete(); b.delete() }
        SuperResolution.forget(SrModel.High)
        if (AppSettings.superResolutionModel == SrModel.High) AppSettings.superResolutionModel = SrModel.Standard
        state = State.Absent
    }

    private suspend fun install(context: Context) {
        val (param, bin) = files(context)
        param.parentFile?.mkdirs()
        val paramPart = File(param.path + ".part")
        val binPart = File(bin.path + ".part")
        try {
            fetch("AnimeSRPlus.param", PARAM_SHA, paramPart) { }
            fetch("AnimeSRPlus.bin", BIN_SHA, binPart) { fraction ->
                scope.launch { if (state is State.Downloading) state = State.Downloading(fraction) }
            }
            check(paramPart.renameTo(param) && binPart.renameTo(bin)) { "Couldn't save the model" }
        } finally {
            paramPart.delete()
            binPart.delete()
        }
    }

    private suspend fun fetch(name: String, sha256: String, target: File, progress: (Float) -> Unit) {
        val connection = java.net.URI(BASE + name).toURL().openConnection() as java.net.HttpURLConnection
        connection.connectTimeout = 20_000
        connection.readTimeout = 30_000
        try {
            check(connection.responseCode == 200) { "HTTP ${connection.responseCode}" }
            val total = connection.contentLengthLong
            val digest = MessageDigest.getInstance("SHA-256")
            var done = 0L
            connection.inputStream.use { input ->
                target.outputStream().use { output ->
                    val buffer = ByteArray(1 shl 16)
                    while (true) {
                        coroutineContext.ensureActive()
                        val n = input.read(buffer)
                        if (n < 0) break
                        output.write(buffer, 0, n)
                        digest.update(buffer, 0, n)
                        done += n
                        if (total > 0) progress(done.toFloat() / total)
                    }
                }
            }
            val hex = digest.digest().joinToString("") { "%02x".format(it) }
            check(hex == sha256) { "The download is damaged" }
        } finally {
            connection.disconnect()
        }
    }
}
