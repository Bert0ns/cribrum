// Small synthetic JPEGs built byte by byte. The parser reads no image data, so the entropy-coded part is filler.
// The compressor's tests need JPEGs that decode, so [encodedJpeg] makes them with Bitmap.compress. Never image
// data from a phone (AGENTS.md).
package io.github.bert0ns.cribrum.compress

import android.graphics.Bitmap
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

fun ascii(text: String): ByteArray = text.toByteArray(Charsets.ISO_8859_1)

fun segment(marker: Int, payload: ByteArray): ByteArray = byteArrayOf(0xFF.toByte(), marker.toByte(), ((payload.size + 2) shr 8).toByte(), (payload.size + 2).toByte()) + payload

private val SOI = byteArrayOf(0xFF.toByte(), 0xD8.toByte())
private val EOI = byteArrayOf(0xFF.toByte(), 0xD9.toByte())

val EXIF: ByteArray = segment(0xE1, ascii("Exif\u0000\u0000II*\u0000\u0008\u0000\u0000\u0000\u0000\u0000\u0000\u0000\u0000\u0000"))
val JFIF: ByteArray = segment(0xE0, ascii("JFIF\u0000\u0001\u0001\u0000\u0000\u0001\u0000\u0001\u0000\u0000"))

fun xmp(body: String): ByteArray = segment(0xE1, ascii("http://ns.adobe.com/xap/1.0/\u0000") + body.toByteArray(Charsets.UTF_8))

fun rdf(description: String): String = """<x:xmpmeta xmlns:x="adobe:ns:meta/"><rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">$description</rdf:RDF></x:xmpmeta>"""

fun app15(text: String): ByteArray = segment(0xEF, ascii(text))

/** A SOF segment for one 16 × 16 component. */
fun sof(marker: Int = 0xC0, precision: Int = 8): ByteArray = segment(marker, byteArrayOf(precision.toByte(), 0, 16, 0, 16, 1, 1, 0x11, 0))

private val DQT = segment(0xDB, byteArrayOf(0) + ByteArray(64) { 2 })
private val SOS = segment(0xDA, byteArrayOf(1, 1, 0, 0, 63, 0))

/** SOI, [headers], the DQT, the SOF, SOS, [entropy] filler bytes and EOI. */
fun jpeg(vararg headers: ByteArray, sof: ByteArray = sof(), dqt: ByteArray = DQT, entropy: Int = 64): ByteArray = concat(listOf(SOI) + headers.toList() + listOf(dqt, sof, SOS, ByteArray(entropy) { 0x55 }, EOI))

fun concat(parts: List<ByteArray>): ByteArray = ByteArrayOutputStream().apply { parts.forEach(::write) }.toByteArray()

private fun le(size: Int, block: ByteBuffer.() -> Unit): ByteArray = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN).apply(block).array()

/**
 * An MPF APP2 for a primary image and the images that follow it, each (size, offset) with offsets from the MPF
 * TIFF header, as Ultra HDR writes it.
 */
fun mpf(images: List<Pair<Int, Int>>): ByteArray = segment(
    0xE2,
    ascii("MPF\u0000") + le(8 + 42 + 16 * images.size) {
        put(ascii("II*\u0000")).putInt(8)
        putShort(3)
        putShort(0xB000.toShort()).putShort(7).putInt(4).put(ascii("0100"))
        putShort(0xB001.toShort()).putShort(4).putInt(1).putInt(images.size)
        putShort(0xB002.toShort()).putShort(7).putInt(16 * images.size).putInt(50)
        putInt(0)
        for ((size, offset) in images) putInt(0).putInt(size).putInt(offset).putShort(0).putShort(0)
    },
)

val ISO_21496: ByteArray = segment(0xE2, ascii("urn:iso:std:iso:ts:21496:-1\u0000") + byteArrayOf(0, 0, 0, 0))

private const val HDR_XMP = """<rdf:Description xmlns:hdrgm="http://ns.adobe.com/hdr-gain-map/1.0/" xmlns:Container="http://ns.google.com/photos/1.0/container/" xmlns:Item="http://ns.google.com/photos/1.0/container/item/" hdrgm:Version="1.0"><Container:Directory><rdf:Seq><rdf:li rdf:parseType="Resource"><Container:Item Item:Semantic="Primary" Item:Mime="image/jpeg"/></rdf:li><rdf:li rdf:parseType="Resource"><Container:Item Item:Semantic="GainMap" Item:Mime="image/jpeg"/></rdf:li></rdf:Seq></Container:Directory></rdf:Description>"""

/**
 * An Ultra HDR file as a camera writes it: the primary image with the `hdrgm` XMP (or the ISO 21496-1 APP2) and an
 * MPF APP2 that points at the gain map image, which follows the primary's EOI. [primary] builds the primary image
 * from its header segments, which must follow SOI.
 */
fun ultraHdr(iso: Boolean = false, gainMapBytes: ByteArray = jpeg(), primary: (List<ByteArray>) -> ByteArray = { jpeg(*it.toTypedArray()) }): ByteArray {
    fun primary(mpf: ByteArray) = primary(if (iso) listOf(EXIF, ISO_21496, mpf) else listOf(EXIF, xmp(rdf(HDR_XMP)), mpf))
    val probe = primary(mpf(listOf(0 to 0, 0 to 0)))
    val mpfAt = 2 + EXIF.size + (if (iso) ISO_21496.size else xmp(rdf(HDR_XMP)).size)
    val base = mpfAt + 4 + 4
    val first = primary(mpf(listOf(probe.size to 0, gainMapBytes.size to probe.size - base)))
    return first + gainMapBytes
}

/** A Samsung SEF trailer after [image], with an entry per name: type, name and a few bytes of data. */
fun withSef(image: ByteArray, vararg names: String): ByteArray {
    val blocks = names.map { name -> le(8) { putShort(0).putShort(0x0A01).putInt(name.length) } + ascii(name) + ByteArray(4) }
    val starts = blocks.runningFold(image.size) { at, block -> at + block.size }
    val directoryAt = starts.last()
    val directory = le(12 + 12 * names.size) {
        put(ascii("SEFH")).putInt(107).putInt(names.size)
        blocks.indices.forEach { putShort(0).putShort(0x0A01).putInt(directoryAt - starts[it]).putInt(blocks[it].size) }
    }
    return concat(listOf(image) + blocks + listOf(directory, le(4) { putInt(directory.size) }, ascii("SEFT")))
}

/** A JPEG that decodes: a gradient with fine detail, so a lower quality makes it clearly smaller. */
fun encodedJpeg(width: Int, height: Int, quality: Int): ByteArray {
    val pixels = IntArray(width * height) { i ->
        val x = i % width
        val y = i / width
        0xFF000000.toInt() or ((x * 7 + y) and 0xFF shl 16) or ((y * 5 + x * x) and 0xFF shl 8) or ((x xor y) and 0xFF)
    }
    val bitmap = Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
    return ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, quality, it) }.toByteArray().also { bitmap.recycle() }
}

/** An [ultraHdr] file whose primary image decodes, [width] × [height] at quality 100. */
fun decodableUltraHdr(width: Int, height: Int, gainMapBytes: ByteArray = jpeg()): ByteArray = ultraHdr(gainMapBytes = gainMapBytes) { headers -> withHeaders(encodedJpeg(width, height, 100), headers) }

/** [jpeg] with its APPn segments replaced by [headers]. */
fun withHeaders(jpeg: ByteArray, headers: List<ByteArray>): ByteArray {
    val body = jpeg.copyOfRange(headerSegments(jpeg).first { it.code !in 0xE0..0xEF }.start, jpeg.size)
    return concat(listOf(SOI) + headers + listOf(body))
}

/** [jpeg] with the frame header saying another size: enough for a bounds decode. */
fun withSize(jpeg: ByteArray, width: Int, height: Int): ByteArray {
    val sof = headerSegments(jpeg).first { it.code == 0xC0 }
    return jpeg.copyOf().also { ByteBuffer.wrap(it, sof.start + 5, 4).putShort(height.toShort()).putShort(width.toShort()) }
}

class JpegSegment(val code: Int, val start: Int, val end: Int, val payload: ByteArray) {
    fun startsWith(id: String) = String(payload, Charsets.ISO_8859_1).startsWith(id)
}

/** The segments before SOS, with where each starts and ends. */
fun headerSegments(jpeg: ByteArray): List<JpegSegment> {
    val found = mutableListOf<JpegSegment>()
    var position = 2
    while (jpeg[position + 1].toInt() and 0xFF != 0xDA) {
        val end = position + 2 + ((jpeg[position + 2].toInt() and 0xFF) shl 8 or (jpeg[position + 3].toInt() and 0xFF))
        found += JpegSegment(jpeg[position + 1].toInt() and 0xFF, position, end, jpeg.copyOfRange(position + 4, end))
        position = end
    }
    return found
}

/** Where the MPF APP2 says the last image (the gain map) starts, from the start of the file. */
fun gainMapOffset(jpeg: ByteArray): Int {
    val mpf = headerSegments(jpeg).first { it.code == 0xE2 && it.startsWith("MPF\u0000") }
    val tiffHeader = mpf.start + 4 + 4
    val tiff = ByteBuffer.wrap(mpf.payload, 4, mpf.payload.size - 4).slice()
    tiff.order(if (mpf.startsWith("MPF\u0000II")) ByteOrder.LITTLE_ENDIAN else ByteOrder.BIG_ENDIAN)
    val entries = tiff.getInt(8 + 2 + 2 * 12 + 8)
    val count = tiff.getInt(8 + 2 + 2 * 12 + 4) / 16
    return tiffHeader + tiff.getInt(entries + (count - 1) * 16 + 8)
}
