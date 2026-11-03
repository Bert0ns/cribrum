// What the compress page needs from a JPEG: whether it can be compressed, the Cribrum marker, the quality it
// was saved at and what a compressed copy would lose (docs/design.md §9). It reads the header segments, the
// last bytes and the SEF directory, never the image data. Android only: the lab has no counterpart.
package io.github.bert0ns.cribrum.compress

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.Instant
import java.time.temporal.ChronoUnit

/** Bump when the rules below change what they read from a file, so the next scan reads every JPEG again. */
const val JPEG_RULES_VERSION = 1

/** Why a JPEG is or is not compressible, the first rule it fails in the order of §9. */
enum class JpegStatus(val id: String) {
    ELIGIBLE("eligible"),

    /** Not an 8-bit SOF0, SOF1 or SOF2 JPEG. */
    UNSUPPORTED("unsupported"),

    /** The XMP sets `MotionPhoto` or `MicroVideo` to 1. */
    MOTION_PHOTO("motion_photo"),

    /** A SEF trailer with a `MotionPhoto_Data` entry. */
    SEF_MOTION_PHOTO("sef_motion_photo"),

    /** An XMP `Container:Directory` item that is neither `Primary` nor `GainMap`. */
    CONTAINER("container"),

    /** Bytes after the image that are neither an MPF secondary image nor a SEF trailer. */
    TRAILING_DATA("trailing_data"),
    ;

    companion object {
        fun of(id: String): JpegStatus = entries.first { it.id == id }
    }
}

/** What a compressed copy loses (§9), so the compress summary can name counts. */
data class Losses(val reEdit: Boolean = false, val depthMap: Boolean = false, val xmp: Boolean = false, val c2pa: Boolean = false)

/** The Cribrum marker as the file holds it. A key it lacks is null; the marker still means the file was compressed. */
data class Marker(val version: Int?, val level: String?, val compressedAt: String?)

data class JpegInfo(
    val status: JpegStatus,
    /** The `hdrgm` XMP or the ISO 21496-1 APP2, with an MPF APP2: an Ultra HDR photo. */
    val gainMap: Boolean = false,
    /** The quality it was saved at, estimated from its quantization tables as ImageMagick's `identify` does. */
    val quality: Int? = null,
    val marker: Marker? = null,
    val losses: Losses = Losses(),
) {
    /** Ultra HDR needs API 34, where `Bitmap` has the gain map API. */
    fun compressible(sdk: Int): Boolean = status == JpegStatus.ELIGIBLE && (!gainMap || sdk >= GAIN_MAP_SDK)
}

const val GAIN_MAP_SDK = 34

/** Random access to a file's bytes, so a reader asks only for the parts it needs. */
interface Bytes {
    val size: Long

    /** Up to [length] bytes from [offset], fewer at the end. */
    fun read(offset: Long, length: Int): ByteArray
}

fun ByteArray.asBytes(): Bytes = object : Bytes {
    override val size get() = this@asBytes.size.toLong()
    override fun read(offset: Long, length: Int) = copyOfRange(offset.toInt().coerceIn(0, size.toInt()), (offset + length).coerceIn(0, size).toInt())
}

fun readJpeg(file: File): JpegInfo = RandomAccessFile(file, "r").use { raf ->
    readJpeg(
        object : Bytes {
            override val size = raf.length()
            override fun read(offset: Long, length: Int): ByteArray {
                val buffer = ByteArray((size - offset).coerceIn(0, length.toLong()).toInt())
                raf.seek(offset)
                raf.readFully(buffer)
                return buffer
            }
        },
    )
}

fun readJpeg(bytes: ByteArray): JpegInfo = readJpeg(bytes.asBytes())

fun readJpeg(bytes: Bytes): JpegInfo {
    val segments = segments(bytes, keep = { it in KEPT }) ?: return JpegInfo(JpegStatus.UNSUPPORTED)
    val xmp = xmp(segments)
    val app2 = segments.filter { it.marker == APP2 }
    val mpf = app2.firstOrNull { it.startsWith(MPF) }
    val gainMap = mpf != null && (HDRGM in xmp || app2.any { it.startsWith(ISO_21496) })
    val sef = sef(bytes)
    val status = when {
        !supported(segments) -> JpegStatus.UNSUPPORTED
        MOTION_ATTRIBUTE.containsMatchIn(xmp) || MOTION_ELEMENT.containsMatchIn(xmp) -> JpegStatus.MOTION_PHOTO
        sef?.names?.contains("MotionPhoto_Data") == true -> JpegStatus.SEF_MOTION_PHOTO
        foreignContainerItem(xmp) -> JpegStatus.CONTAINER
        !endsCleanly(bytes, sef?.start ?: bytes.size, mpf) -> JpegStatus.TRAILING_DATA
        else -> JpegStatus.ELIGIBLE
    }
    return JpegInfo(
        status = status,
        gainMap = gainMap,
        quality = quality(segments),
        marker = segments.firstOrNull { it.marker == APP15 && it.startsWith(MARKER_ID) }?.let { marker(it.payload!!) },
        losses = Losses(
            reEdit = sef?.names?.contains("PhotoEditor_Re_Edit_Data") == true,
            depthMap = sef?.names?.any { it.startsWith("DualShot_DepthMap") } == true,
            xmp = segments.any { it.marker == APP1 && (it.startsWith(XMP) || it.startsWith(XMP_EXTENSION)) },
            c2pa = segments.any { it.marker == APP11 && it.startsWith(JUMBF) },
        ),
    )
}

/**
 * The marker segment (§9): APP15, `cribrum\0`, then ASCII `key=value` lines, each ending in `\n`, with
 * no comment or date line. [compressedAt] is written in UTC to the second.
 */
fun markerSegment(level: CompressionLevel, compressedAt: Instant): ByteArray {
    val text = "version=1\nlevel=${level.id}\ncompressed_at=${compressedAt.truncatedTo(ChronoUnit.SECONDS)}\n"
    val payload = MARKER_ID + text.toByteArray(Charsets.US_ASCII)
    return byteArrayOf(0xFF.toByte(), APP15.toByte(), ((payload.size + 2) shr 8).toByte(), (payload.size + 2).toByte()) + payload
}

/**
 * [jpeg] with [marker] inserted where §9 puts it: directly after the Exif APP1, or with none directly after SOI,
 * or after an APP0 JFIF that follows SOI. Throws when that place is behind the MPF APP2, since a segment between
 * MPF and the gain map moves the gain map away from where MPF says it is.
 */
fun withMarker(jpeg: ByteArray, marker: ByteArray): ByteArray {
    val segments = requireNotNull(segments(jpeg.asBytes(), keep = { it == APP0 || it == APP1 || it == APP2 })) { "not a JPEG" }
    val exif = segments.firstOrNull { it.marker == APP1 && it.startsWith(EXIF) }
    val first = segments.firstOrNull()
    val at = when {
        exif != null -> exif.end
        first != null && first.marker == APP0 && first.startsWith(JFIF) -> first.end
        else -> 2L
    }
    val mpf = segments.firstOrNull { it.marker == APP2 && it.startsWith(MPF) }
    require(mpf == null || mpf.offset >= at) { "the marker would follow the MPF APP2" }
    return jpeg.copyOfRange(0, at.toInt()) + marker + jpeg.copyOfRange(at.toInt(), jpeg.size)
}

private class Segment(val marker: Int, val offset: Long, val length: Int, val payload: ByteArray?) {
    val end get() = offset + 2 + length

    fun startsWith(prefix: ByteArray) = payload != null && payload.size >= prefix.size && prefix.indices.all { payload[it] == prefix[it] }
}

private const val SOI = 0xD8
private const val EOI = 0xD9
private const val SOS = 0xDA
private const val DQT = 0xDB
private const val APP0 = 0xE0
private const val APP1 = 0xE1
private const val APP2 = 0xE2
private const val APP11 = 0xEB
private const val APP15 = 0xEF

/** SOF markers: C0 to CF but DHT (C4), JPG (C8) and DAC (CC). */
private val SOF = (0xC0..0xCF).toSet() - setOf(0xC4, 0xC8, 0xCC)
private val KEPT = SOF + setOf(DQT, APP1, APP2, APP11, APP15)

private fun ascii(text: String) = text.toByteArray(Charsets.ISO_8859_1)

private val EXIF = ascii("Exif\u0000\u0000")
private val JFIF = ascii("JFIF\u0000")
private val XMP = ascii("http://ns.adobe.com/xap/1.0/\u0000")
private val XMP_EXTENSION = ascii("http://ns.adobe.com/xmp/extension/\u0000")
private val MPF = ascii("MPF\u0000")
private val ISO_21496 = ascii("urn:iso:std:iso:ts:21496:-1\u0000")
private val JUMBF = ascii("JP")
private val MARKER_ID = ascii("cribrum\u0000")
private const val HDRGM = "http://ns.adobe.com/hdr-gain-map/1.0/"

/**
 * The segments from SOI up to SOS, SOS included, with the payload of those [keep] picks, or null when the bytes do
 * not start with SOI. A file cut short ends the list where it is cut.
 */
private fun segments(bytes: Bytes, keep: (Int) -> Boolean): List<Segment>? {
    val head = bytes.read(0, 2)
    if (head.size < 2 || head.u8(0) != 0xFF || head.u8(1) != SOI) return null
    val segments = mutableListOf<Segment>()
    var pos = 2L
    while (true) {
        val header = bytes.read(pos, 4)
        if (header.size < 2 || header.u8(0) != 0xFF) break
        val marker = header.u8(1)
        when {
            marker == 0xFF -> pos++ // a fill byte before the marker
            marker == EOI || marker in 0xD0..0xD7 || marker == 0x01 -> pos += 2 // no length
            header.size < 4 -> break
            else -> {
                val length = (header.u8(2) shl 8) or header.u8(3)
                if (length < 2) break
                val payload = if (keep(marker)) bytes.read(pos + 4, length - 2) else null
                segments += Segment(marker, pos, length, payload)
                if (marker == SOS || pos + 2 + length > bytes.size) break
                pos += 2 + length
            }
        }
        if (marker == EOI) break
    }
    return segments
}

private fun ByteArray.u8(i: Int) = this[i].toInt() and 0xFF

/** The first SOF before SOS is SOF0, SOF1 or SOF2 with 8-bit samples. */
private fun supported(segments: List<Segment>): Boolean {
    val sof = segments.firstOrNull { it.marker in SOF } ?: return false
    return sof.marker in 0xC0..0xC2 && sof.payload!!.isNotEmpty() && sof.payload.u8(0) == 8
}

/** The main XMP and every Extended XMP chunk, in the order of their offsets, as one text. */
private fun xmp(segments: List<Segment>): String {
    val app1 = segments.filter { it.marker == APP1 }
    val main = app1.filter { it.startsWith(XMP) }.joinToString("") { String(it.payload!!, XMP.size, it.payload.size - XMP.size, Charsets.UTF_8) }
    // Extended XMP: the namespace, a 32-byte GUID, the full length and this chunk's offset (4 bytes each), then the chunk.
    val header = XMP_EXTENSION.size + 32 + 8
    val extended = app1.filter { it.startsWith(XMP_EXTENSION) && it.payload!!.size >= header }
        .sortedBy { ByteBuffer.wrap(it.payload!!, header - 4, 4).int.toUInt() }
        .joinToString("") { String(it.payload!!, header, it.payload.size - header, Charsets.UTF_8) }
    return main + extended
}

/** `MotionPhoto="1"` or `MicroVideo='1'`, under any namespace prefix or none. */
private val MOTION_ATTRIBUTE = Regex("""(?<![\w.-])(?:[\w.-]+:)?(?:MotionPhoto|MicroVideo)\s*=\s*(["'])\s*1\s*\1""")

/** `<Camera:MotionPhoto>1</Camera:MotionPhoto>`, the same flag as an element. */
private val MOTION_ELEMENT = Regex("""<(?:[\w.-]+:)?(?:MotionPhoto|MicroVideo)(?:\s[^>]*)?>\s*1\s*</""")

private val DIRECTORY = Regex("""<(?:[\w.-]+:)?Directory[\s>].*?</(?:[\w.-]+:)?Directory>""", RegexOption.DOT_MATCHES_ALL)
private val SEMANTIC_ATTRIBUTE = Regex("""(?<![\w.-])(?:[\w.-]+:)?Semantic\s*=\s*(["'])(.*?)\1""")
private val SEMANTIC_ELEMENT = Regex("""<(?:[\w.-]+:)?Semantic(?:\s[^>]*)?>\s*([^<]*?)\s*</""")

/** A `Container:Directory` item whose semantic is neither `Primary` nor `GainMap`, under any prefix. */
private fun foreignContainerItem(xmp: String): Boolean = DIRECTORY.findAll(xmp).any { directory ->
    val semantics = SEMANTIC_ATTRIBUTE.findAll(directory.value).map { it.groupValues[2] } + SEMANTIC_ELEMENT.findAll(directory.value).map { it.groupValues[1] }
    semantics.any { it.trim() !in setOf("Primary", "GainMap") }
}

/** A Samsung SEF trailer: where its first data entry starts, and the names of its entries. */
private class Sef(val start: Long, val names: List<String>)

/**
 * The trailer ends in a 4-byte little-endian directory length and `SEFT`. The directory starts with `SEFH`, a version
 * and an entry count, then 12 bytes per entry, whose offset counts back from the directory to the entry's data. The
 * data starts with 4 bytes, a 4-byte name length and the name. Null when there is none or it does not parse.
 */
private fun sef(bytes: Bytes): Sef? {
    if (bytes.size < 8) return null
    val tail = bytes.read(bytes.size - 8, 8)
    if (String(tail, 4, 4, Charsets.ISO_8859_1) != "SEFT") return null
    val directory = bytes.size - 8 - tail.le32(0)
    if (directory < 0) return null
    val header = bytes.read(directory, 12)
    if (header.size < 12 || String(header, 0, 4, Charsets.ISO_8859_1) != "SEFH") return null
    val count = header.le32(8)
    if (count < 0 || directory + 12 + count * 12 > bytes.size - 8) return null
    val entries = bytes.read(directory + 12, (count * 12).toInt())
    var start = directory
    val names = (0 until count.toInt()).map { i ->
        val data = directory - entries.le32(i * 12 + 4)
        if (data < 0) return null
        start = minOf(start, data)
        val head = bytes.read(data, 8)
        if (head.size < 8) return null
        String(bytes.read(data + 8, head.le32(4).coerceAtMost(MAX_SEF_NAME).toInt()), Charsets.ISO_8859_1)
    }
    return Sef(start, names)
}

private const val MAX_SEF_NAME = 256L

private fun ByteArray.le32(i: Int): Long = ByteBuffer.wrap(this, i, 4).order(ByteOrder.LITTLE_ENDIAN).int.toUInt().toLong()

/**
 * The bytes up to [end] (the file, with any SEF trailer cut off) end in EOI, and when MPF lists secondary images,
 * the last of them ends exactly at [end].
 */
private fun endsCleanly(bytes: Bytes, end: Long, mpf: Segment?): Boolean {
    if (end < 2) return false
    val last = bytes.read(end - 2, 2)
    if (last.size < 2 || last.u8(0) != 0xFF || last.u8(1) != EOI) return false
    val images = mpf?.let(::mpfImages) ?: return true
    if (images.size < 2) return true
    // MPF offsets count from its TIFF header, right after `MPF\0`, and the primary image's offset is 0.
    val (size, offset) = images.last()
    return mpf.offset + 4 + MPF.size + offset + size == end
}

/** The MP entries of an MPF APP2 as (size, offset) pairs, or null when they do not parse. */
private fun mpfImages(mpf: Segment): List<Pair<Long, Long>>? {
    val tiff = ByteBuffer.wrap(mpf.payload!!, MPF.size, mpf.payload.size - MPF.size).slice()
    if (tiff.remaining() < 8) return null
    tiff.order(
        when (String(mpf.payload, MPF.size, 2, Charsets.ISO_8859_1)) {
            "II" -> ByteOrder.LITTLE_ENDIAN
            "MM" -> ByteOrder.BIG_ENDIAN
            else -> return null
        },
    )
    return try {
        val ifd = tiff.getInt(4)
        val entries = tiff.getShort(ifd).toInt() and 0xFFFF
        val entry = (0 until entries).map { ifd + 2 + it * 12 }.firstOrNull { (tiff.getShort(it).toInt() and 0xFFFF) == MP_ENTRY } ?: return null
        val at = tiff.getInt(entry + 8)
        (0 until tiff.getInt(entry + 4) / 16).map { i ->
            val size = tiff.getInt(at + i * 16 + 4).toUInt().toLong()
            val offset = tiff.getInt(at + i * 16 + 8).toUInt().toLong()
            size to offset
        }
    } catch (_: IndexOutOfBoundsException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }
}

private const val MP_ENTRY = 0xB002

private fun marker(payload: ByteArray): Marker {
    val values = String(payload, MARKER_ID.size, payload.size - MARKER_ID.size, Charsets.US_ASCII).lines()
        .mapNotNull { line -> line.split('=', limit = 2).takeIf { it.size == 2 }?.let { it[0] to it[1] } }.toMap()
    return Marker(values["version"]?.toIntOrNull(), values["level"], values["compressed_at"])
}

/** Where each zigzag position of a DQT table sits in natural (row by row) order. */
private val NATURAL_ORDER = intArrayOf(
    0, 1, 8, 16, 9, 2, 3, 10, 17, 24, 32, 25, 18, 11, 4, 5, 12, 19, 26, 33, 40, 48, 41, 34, 27, 20, 13, 6, 7, 14, 21, 28,
    35, 42, 49, 56, 57, 50, 43, 36, 29, 22, 15, 23, 30, 37, 44, 51, 58, 59, 52, 45, 38, 31, 39, 46, 53, 60, 61, 54, 47, 55, 62, 63,
)

/** The IJG tables of the JPEG standard (Annex K), in natural order. */
private val LUMA = intArrayOf(
    16, 11, 10, 16, 24, 40, 51, 61, 12, 12, 14, 19, 26, 58, 60, 55, 14, 13, 16, 24, 40, 57, 69, 56, 14, 17, 22, 29, 51, 87, 80, 62,
    18, 22, 37, 56, 68, 109, 103, 77, 24, 35, 55, 64, 81, 104, 113, 92, 49, 64, 78, 87, 103, 121, 120, 101, 72, 92, 95, 98, 112, 100, 103, 99,
)
private val CHROMA = intArrayOf(
    17, 18, 24, 47, 99, 99, 99, 99, 18, 21, 26, 66, 99, 99, 99, 99, 24, 26, 56, 99, 99, 99, 99, 99, 47, 66, 99, 99, 99, 99, 99, 99,
) + IntArray(32) { 99 }

/** The standard tables at an IJG quality, as libjpeg scales them for a baseline file. */
private fun scaled(table: IntArray, quality: Int): IntArray {
    val scale = if (quality < 50) 5000 / quality else 200 - 2 * quality
    return IntArray(64) { ((table[it] * scale + 50) / 100).coerceIn(1, 255) }
}

/**
 * ImageMagick's estimate (`JPEGSetImageQuality` in coders/jpeg.c). Its hash and sum tables are those of the standard
 * tables at each quality from 1 to 100, so they are computed here instead of copied. Null without the tables it reads.
 */
private fun quality(segments: List<Segment>): Int? {
    val tables = arrayOfNulls<IntArray>(4)
    for (dqt in segments.filter { it.marker == DQT }) {
        val payload = dqt.payload!!
        var i = 0
        while (i < payload.size) {
            val wide = payload.u8(i) shr 4 != 0
            val id = payload.u8(i) and 0x0F
            val bytes = if (wide) 128 else 64
            if (id > 3 || i + 1 + bytes > payload.size) break
            tables[id] = IntArray(64).also { table ->
                for (k in 0 until 64) table[NATURAL_ORDER[k]] = if (wide) (payload.u8(i + 1 + 2 * k) shl 8) or payload.u8(i + 2 + 2 * k) else payload.u8(i + 1 + k)
            }
            i += 1 + bytes
        }
    }
    val luma = tables[0] ?: return null
    val chroma = tables[1]
    val sum = tables.sumOf { it?.sum() ?: 0 }
    val value = luma[2] + luma[53] + (chroma?.let { it[0] + it[63] } ?: 0)
    for (i in 0 until 100) {
        val l = scaled(LUMA, i + 1)
        val c = scaled(CHROMA, i + 1)
        val hash = l[2] + l[53] + if (chroma != null) c[0] + c[63] else 0
        val sums = l.sum() + if (chroma != null) c.sum() else 0
        if (value < hash && sum < sums) continue
        if ((value <= hash && sum <= sums) || i >= 50) return i + 1
    }
    return null
}
