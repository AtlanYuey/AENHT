package com.atlanyuey.aenht.core

import java.io.File

/**
 * The type of a downloaded picture ("jpg", "png", "gif", "webp", "avif",
 * "heic", "bmp"), or null when the file is missing, isn't a picture, was
 * cut off (a download that stopped part way: the type's end marker or its
 * own stated length is missing), or has no picture in it (the system's
 * decoder finds no size, or one no page has).
 */
fun imageExtension(file: File): String? {
    val type = imageType(file) ?: return null
    return type.takeIf { hasPicture(file) }
}

/** The largest side a page may have (a bigger size is a broken file, not a page). */
private const val MAX_SIDE = 30_000

/** Whether the system's decoder reads a picture's size from [file] (its header only: nothing is decoded). */
private fun hasPicture(file: File): Boolean {
    // Unsupported formats are not verified downloads. Container shape alone
    // must never turn missing/invalid image data into a completed page.
    val options = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
    return try {
        android.graphics.BitmapFactory.decodeFile(file.path, options)
        options.outWidth in 1..MAX_SIDE && options.outHeight in 1..MAX_SIDE
    } catch (_: Exception) {
        false
    }
}

/** The type by the first bytes, when the file has its end (see [imageExtension]). */
internal fun imageType(file: File): String? = try {
    java.io.RandomAccessFile(file, "r").use { f ->
        val size = f.length()
        if (size < 12) return null
        fun read(offset: Long, count: Int): ByteArray {
            val bytes = ByteArray(count)
            f.seek(offset)
            f.readFully(bytes)
            return bytes
        }
        val head = read(0, 12)
        fun at(offset: Int, text: String) = text.indices.all { head[offset + it] == text[it].code.toByte() }
        val type = when {
            head[0] == 0xFF.toByte() && head[1] == 0xD8.toByte() && head[2] == 0xFF.toByte() -> "jpg"
            head[0] == 0x89.toByte() && at(1, "PNG") -> "png"
            at(0, "GIF8") -> "gif"
            at(0, "RIFF") && at(8, "WEBP") -> "webp"
            at(4, "ftyp") && (at(8, "avif") || at(8, "avis")) -> "avif"
            at(4, "ftyp") && (at(8, "heic") || at(8, "heix") || at(8, "mif1")) -> "heic"
            at(0, "BM") -> "bmp"
            else -> return null
        }
        type.takeIf { ImageEnds.complete(type, size, head, ::read) }
    }
} catch (_: Exception) {
    null
}

/** Whether a picture file has its end: the checks are the same on iOS (DownloadManager.isComplete). */
object ImageEnds {
    private fun littleEndian(bytes: ByteArray, at: Int, count: Int): Long =
        (0 until count).fold(0L) { value, i -> value or ((bytes[at + i].toLong() and 0xFF) shl (8 * i)) }

    private fun bigEndian(bytes: ByteArray, at: Int, count: Int): Long =
        (0 until count).fold(0L) { value, i -> (value shl 8) or (bytes[at + i].toLong() and 0xFF) }

    /**
     * ISO media boxes (AVIF, HEIC) one after the other, each within the file,
     * the last ending at its end, with the picture's data (`mdat`) among them.
     * A length is checked against what's left before it's added: a broken or
     * hostile one can't overflow.
     */
    private fun boxesEndTogether(size: Long, read: (Long, Int) -> ByteArray): Boolean {
        var offset = 0L
        var boxes = 0
        var data = false
        while (offset < size) {
            val left = size - offset
            if (left < 8 || ++boxes > 4096) return false
            val header = read(offset, 8)
            val type = String(header, 4, 4, Charsets.ISO_8859_1)
            val length = when (val small = bigEndian(header, 0, 4)) {
                0L -> left // To the end of the file.
                1L -> {
                    if (left < 16) return false
                    bigEndian(read(offset + 8, 8), 0, 8).takeIf { it >= 16 } ?: return false
                }
                else -> small.takeIf { it >= 8 } ?: return false
            }
            if (length > left) return false
            if (type == "mdat" && length > 16) data = true
            offset += length
        }
        return data
    }

    /**
     * A GIF has an image (an image descriptor, after the header, its colour
     * table and any extension blocks), not only a header and its trailer.
     */
    private fun gifHasImage(size: Long, read: (Long, Int) -> ByteArray): Boolean {
        if (size < 13 + 10 + 1) return false
        val screen = read(0, 13)
        var offset = 13L + if (screen[10].toInt() and 0x80 != 0) 3L * (1 shl ((screen[10].toInt() and 0x07) + 1)) else 0L
        var blocks = 0
        while (offset < size && ++blocks < 10_000) {
            when (read(offset, 1)[0].toInt() and 0xFF) {
                0x2C -> return offset + 10 < size // An image descriptor.
                0x21 -> {
                    // An extension: its label, then sub-blocks until an empty one.
                    offset += 2
                    while (true) {
                        if (offset >= size) return false
                        val length = read(offset, 1)[0].toInt() and 0xFF
                        offset += 1 + length
                        if (length == 0) break
                    }
                }
                else -> return false
            }
        }
        return false
    }

    fun complete(type: String, size: Long, head: ByteArray, read: (Long, Int) -> ByteArray): Boolean {
        fun tail(count: Int): ByteArray = read(maxOf(0L, size - count), minOf(count.toLong(), size).toInt())
        fun ByteArray.has(vararg marker: Int): Boolean =
            (0..this.size - marker.size).any { i -> marker.indices.all { this[i + it] == marker[it].toByte() } }
        return when (type) {
            // The end of image marker (some files carry a few bytes after it).
            "jpg" -> tail(512).has(0xFF, 0xD9)
            "png" -> tail(64).has(0x49, 0x45, 0x4E, 0x44, 0xAE, 0x42, 0x60, 0x82) // IEND and its CRC
            "gif" -> tail(1)[0] == 0x3B.toByte() && gifHasImage(size, read) // The trailer, and a picture before it.
            // The length the file says it has.
            "webp" -> littleEndian(head, 4, 4) + 8 <= size
            "bmp" -> littleEndian(head, 2, 4) in 26..size
            // Boxes one after the other, each within the file, the last ending at its end.
            "avif", "heic" -> boxesEndTogether(size, read)
            else -> true
        }
    }
}
