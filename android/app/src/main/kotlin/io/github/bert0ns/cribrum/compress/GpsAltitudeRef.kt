// Reads the GPS altitude reference straight from a JPEG's Exif, for the files ExifInterface cannot read it from.
package io.github.bert0ns.cribrum.compress

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

private const val GPS_IFD_POINTER = 0x8825
private const val ALTITUDE_REF = 5
private const val MAX_EXIF = 0x10000
private const val MAX_SEGMENTS = 64

/**
 * The GPS altitude reference, 0 above sea level and 1 below it, read from the Exif APP1 of [file] whatever TIFF
 * format the camera stored it in (BYTE, UNDEFINED, SHORT, LONG or ASCII). ExifInterface drops the tag when the
 * format is not the one it expects (BYTE or UNDEFINED), and without it a depth reads as a height (docs/design.md §9).
 * Null when the file has no such tag, or an Exif that does not parse.
 */
internal fun rawGpsAltitudeRef(file: File): Int? = runCatching {
    val tiff = exifTiff(file) ?: return null
    val order = when (String(tiff, 0, 2, Charsets.ISO_8859_1)) {
        "II" -> ByteOrder.LITTLE_ENDIAN
        "MM" -> ByteOrder.BIG_ENDIAN
        else -> return null
    }
    val buffer = ByteBuffer.wrap(tiff).order(order)
    val gps = entry(buffer, buffer.getInt(4), GPS_IFD_POINTER)?.let { buffer.getInt(it + 8) } ?: return null
    val at = entry(buffer, gps, ALTITUDE_REF) ?: return null
    val value = when (buffer.getShort(at + 2).toInt()) {
        1, 7 -> buffer.get(at + 8).toInt()
        3 -> buffer.getShort(at + 8).toInt()
        4 -> buffer.getInt(at + 8)
        2 -> buffer.get(at + 8).toInt().let { if (it == '0'.code || it == '1'.code) it - '0'.code else it }
        else -> return null
    }
    value.takeIf { it == 0 || it == 1 }
}.getOrNull()

/** The offset of the entry for [tag] in the IFD at [ifd], or null. */
private fun entry(buffer: ByteBuffer, ifd: Int, tag: Int): Int? {
    val count = buffer.getShort(ifd).toInt() and 0xFFFF
    return (0 until count).map { ifd + 2 + it * 12 }.firstOrNull { (buffer.getShort(it).toInt() and 0xFFFF) == tag }
}

/** The TIFF part of the first Exif APP1 before the image data, or null. */
private fun exifTiff(file: File): ByteArray? = RandomAccessFile(file, "r").use { raf ->
    if (raf.read() != 0xFF || raf.read() != 0xD8) return null
    repeat(MAX_SEGMENTS) {
        var marker = raf.read()
        if (marker != 0xFF) return null
        while (marker == 0xFF) marker = raf.read()
        // SOS or EOI: no Exif further on. Markers without a length are only RSTn and TEM, which come after SOS.
        if (marker < 0 || marker == 0xDA || marker == 0xD9) return null
        val length = (raf.read() shl 8) or raf.read()
        if (length < 2) return null
        if (marker == 0xE1 && length - 2 in 6..MAX_EXIF) {
            val payload = ByteArray(length - 2)
            raf.readFully(payload)
            if (String(payload, 0, 6, Charsets.ISO_8859_1) == "Exif\u0000\u0000") return payload.copyOfRange(6, payload.size)
        } else {
            raf.seek(raf.filePointer + length - 2)
        }
    }
    null
}
