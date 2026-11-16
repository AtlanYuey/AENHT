package com.atlanyuey.aenht.core

import java.io.File

/**
 * The type of a picture file by its first bytes ("jpg", "png", "gif",
 * "webp", "avif", "heic", "bmp"), or null when it is missing, isn't a
 * picture, or was cut off (a download that stopped part way: the type's
 * end marker or its own stated length is missing).
 */
fun imageExtension(file: File): String? = try {
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

    fun complete(type: String, size: Long, head: ByteArray, read: (Long, Int) -> ByteArray): Boolean {
        fun tail(count: Int): ByteArray = read(maxOf(0L, size - count), minOf(count.toLong(), size).toInt())
        fun ByteArray.has(vararg marker: Int): Boolean =
            (0..this.size - marker.size).any { i -> marker.indices.all { this[i + it] == marker[it].toByte() } }
        return when (type) {
            // The end of image marker (some files carry a few bytes after it).
            "jpg" -> tail(512).has(0xFF, 0xD9)
            "png" -> tail(64).has(0x49, 0x45, 0x4E, 0x44, 0xAE, 0x42, 0x60, 0x82) // IEND and its CRC
            "gif" -> tail(1)[0] == 0x3B.toByte() // The trailer.
            // The length the file says it has.
            "webp" -> littleEndian(head, 4, 4) + 8 <= size
            "bmp" -> littleEndian(head, 2, 4) in 26..size
            // Boxes one after the other, each within the file, the last ending at its end.
            "avif", "heic" -> {
                var offset = 0L
                var boxes = 0
                while (offset < size) {
                    if (size - offset < 8 || ++boxes > 4096) return false
                    val header = read(offset, 8)
                    var length = bigEndian(header, 0, 4)
                    when (length) {
                        0L -> return true // To the end of the file.
                        1L -> {
                            if (size - offset < 16) return false
                            length = bigEndian(read(offset + 8, 8), 0, 8)
                            if (length < 16) return false
                        }
                        else -> if (length < 8) return false
                    }
                    offset += length
                }
                offset == size
            }
            else -> true
        }
    }
}
