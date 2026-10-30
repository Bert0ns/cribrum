package io.github.bert0ns.cribrum.imaging

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorSpace
import android.graphics.ImageDecoder
import android.graphics.Paint
import android.media.MediaCodec
import android.media.MediaCodecInfo.CodecCapabilities
import android.media.MediaCodecInfo.CodecProfileLevel
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Build
import android.util.Log
import androidx.exifinterface.media.ExifInterface
import androidx.heifwriter.AvifWriter
import androidx.heifwriter.HeifWriter
import androidx.test.core.app.ApplicationProvider
import io.github.bert0ns.cribrum.metadata.readMetadata
import org.hamcrest.CoreMatchers.equalTo
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ErrorCollector
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs

private const val TAG = "cribrum-heic"
private const val WIDTH = 640
private const val HEIGHT = 480
private const val STOP_TIMEOUT_MS = 10_000L
private const val CODEC_TIMEOUT_US = 5_000_000L

/** MediaCodec's hidden BUFFER_FLAG_MUXER_DATA, which marks a sample as EXIF. HeifWriter passes the same value. */
private const val MUXER_DATA_FLAG = 16

/** What AvifWriter gives the muxer, since MediaFormat has no name for it below SDK 34. */
private const val AVIF_MIME = "image/avif"

/** The image as written, a row of quadrants each, so a wrong turn or a mirror shows in the pixels and the size. */
private val QUADRANTS = listOf("RG", "BW")
private val COLORS = mapOf('R' to Color.RED, 'G' to Color.GREEN, 'B' to Color.BLUE, 'W' to Color.WHITE)

/** The EXIF orientation that says the same as a container rotation of this many degrees clockwise. */
private val EXIF_OF_ROTATION = mapOf(0 to 1, 90 to 6, 180 to 3, 270 to 8)

private enum class Format(val extension: String) { HEIC("heic"), HEIC_10_BIT("heic"), AVIF("avif"), AVIF_10_BIT("avif") }

private class Case(val name: String, val rotation: Int = 0, val exif: Int? = null, val format: Format = Format.HEIC, val grid: Boolean = false) {
    /** A file whose EXIF contradicts its container has no right answer to assert: it is only reported. */
    val agrees = exif == null || exif == EXIF_OF_ROTATION[rotation]
}

/** What a reader sees: the size, and the quadrants read at the center of each, row by row. */
private data class Seen(val width: Int, val height: Int, val layout: String) {
    override fun toString() = "${width}x$height $layout"
}

private val CASES = listOf(
    Case("none"),
    Case("exif1", exif = 1),
    Case("exif6", exif = 6),
    Case("exif3", exif = 3),
    Case("irot90", rotation = 90),
    Case("irot180", rotation = 180),
    Case("irot270", rotation = 270),
    Case("irot90+exif6", rotation = 90, exif = 6),
    Case("irot180+exif3", rotation = 180, exif = 3),
    Case("irot270+exif8", rotation = 270, exif = 8),
    Case("irot90+exif1", rotation = 90, exif = 1),
    Case("irot180+exif6", rotation = 180, exif = 6),
    Case("grid", grid = true), // how a camera stores a photo, in tiles
    Case("grid+irot90+exif6", rotation = 90, exif = 6, grid = true),
    Case("heic10", format = Format.HEIC_10_BIT),
    Case("heic10+exif6", exif = 6, format = Format.HEIC_10_BIT),
    Case("heic10+irot90", rotation = 90, format = Format.HEIC_10_BIT),
    Case("heic10+irot90+exif6", rotation = 90, exif = 6, format = Format.HEIC_10_BIT),
    Case("avif", format = Format.AVIF),
    Case("avif+exif6", exif = 6, format = Format.AVIF),
    Case("avif+irot90", rotation = 90, format = Format.AVIF),
    Case("avif+irot90+exif6", rotation = 90, exif = 6, format = Format.AVIF),
    Case("avif10", format = Format.AVIF_10_BIT),
    Case("avif10+exif6", exif = 6, format = Format.AVIF_10_BIT),
    Case("avif10+irot90", rotation = 90, format = Format.AVIF_10_BIT),
    Case("avif10+irot90+exif6", rotation = 90, exif = 6, format = Format.AVIF_10_BIT),
)

/**
 * Whether a rotated HEIC is turned twice (#220). Writes synthetic images on the device, checks with a parser
 * that each file holds the rotation and the EXIF it should, logs what every reader makes of it, and asserts
 * that decode() plus orientation() and readMetadata() give what ImageDecoder shows. A file whose EXIF
 * contradicts its container is reported and not asserted, and a format the device cannot encode is logged.
 *
 * The app under test has the same id as an installed Cribrum, so build it under another id and run it by hand:
 *
 *     ./gradlew -PapplicationIdSuffix=.heiccheck assembleDebug assembleDebugAndroidTest
 *     adb install -t app/build/outputs/apk/debug/app-debug.apk
 *     adb install -t app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
 *     adb shell am instrument -w -e class io.github.bert0ns.cribrum.imaging.HeicRotationDeviceTest \
 *         io.github.bert0ns.cribrum.heiccheck.test/androidx.test.runner.AndroidJUnitRunner
 *     adb logcat -d -s cribrum-heic:I
 *     adb uninstall io.github.bert0ns.cribrum.heiccheck.test
 *     adb uninstall io.github.bert0ns.cribrum.heiccheck
 *
 * Do not use connectedDebugAndroidTest: it uninstalls the app when it ends, and without the suffix that is Cribrum.
 */
class HeicRotationDeviceTest {
    private val folder = File(ApplicationProvider.getApplicationContext<Context>().cacheDir, "heic-rotation-test").apply { mkdirs() }

    @get:Rule
    val errors = ErrorCollector()

    @After
    fun cleanUp() {
        folder.deleteRecursively()
    }

    @Test
    fun turnsAnImageOnce() {
        Log.i(TAG, "device ${Build.MODEL}, Android ${Build.VERSION.RELEASE}, patch ${Build.VERSION.SECURITY_PATCH}")
        for (case in CASES) {
            val file = File(folder, "${case.name}.${case.format.extension}")
            try {
                write(case, file)
            } catch (e: Exception) {
                Log.i(TAG, "${case.name} | no fixture: $e")
                continue
            }
            val (irot, exif) = boxes(file)
            val items = items(file)
            val decoded = bitmapFactory(file)
            val orientation = ExifInterface(file).getAttributeInt(ExifInterface.TAG_ORIENTATION, -1)
            val displayed = imageDecoder(file)
            val gray = loadGrayscale(file, maxOf(WIDTH, HEIGHT))
            val pipeline = Seen(gray.width, gray.height, layout(gray.width, gray.height) { x, y -> gray[x, y] })
            val metadata = readMetadata(file)
            Log.i(TAG, "${case.name} | boxes irot=$irot exif=$exif items=$items | BitmapFactory $decoded | ExifInterface $orientation | ImageDecoder $displayed | decode+orientation ${orientation(file)} $pipeline | readMetadata ${metadata.width}x${metadata.height} ${metadata.mime}")
            errors.checkThat(case.name, irot, equalTo((360 - case.rotation) / 90 % 4))
            errors.checkThat(case.name, exif, equalTo(case.exif))
            if (case.grid) errors.checkThat(case.name, "grid" in items, equalTo(true))
            if (case.agrees) {
                errors.checkThat(case.name, pipeline, equalTo(displayed))
                errors.checkThat(case.name, metadata.width to metadata.height, equalTo(displayed.width to displayed.height))
            }
        }
    }

    private fun write(case: Case, file: File) {
        val exif = case.exif?.let(::exifBlock)
        when (case.format) {
            Format.HEIC -> HeifWriter.Builder(file.path, WIDTH, HEIGHT, HeifWriter.INPUT_MODE_BITMAP).setRotation(case.rotation).setGridEnabled(case.grid).build().use {
                val bitmap = quadrants()
                it.start()
                it.addBitmap(bitmap)
                exif?.let { block -> it.addExifData(0, block, 0, block.size) }
                it.stop(STOP_TIMEOUT_MS)
                bitmap.recycle()
            }
            Format.AVIF -> AvifWriter.Builder(file.path, WIDTH, HEIGHT, AvifWriter.INPUT_MODE_BITMAP).setRotation(case.rotation).setGridEnabled(false).build().use {
                val bitmap = quadrants()
                it.start()
                it.addBitmap(bitmap)
                exif?.let { block -> it.addExifData(0, block, 0, block.size) }
                it.stop(STOP_TIMEOUT_MS)
                bitmap.recycle()
            }
            Format.HEIC_10_BIT -> write10Bit(file, MediaFormat.MIMETYPE_VIDEO_HEVC, CodecProfileLevel.HEVCProfileMain10, MediaFormat.MIMETYPE_IMAGE_ANDROID_HEIC, case.rotation, exif)
            Format.AVIF_10_BIT -> write10Bit(file, MediaFormat.MIMETYPE_VIDEO_AV1, CodecProfileLevel.AV1ProfileMain10, AVIF_MIME, case.rotation, exif)
        }
    }

    private fun quadrants(): Bitmap {
        val bitmap = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        for (y in 0..1) {
            for (x in 0..1) {
                val paint = Paint().apply { color = COLORS.getValue(QUADRANTS[y][x]) }
                canvas.drawRect(x * WIDTH / 2f, y * HEIGHT / 2f, (x + 1) * WIDTH / 2f, (y + 1) * HEIGHT / 2f, paint)
            }
        }
        return bitmap
    }

    /** Limited range BT.601 Y, U and V of the pixel, in 16-bit samples with the 10 bits at the top, as P010 stores them. */
    private fun yuvAt(x: Int, y: Int): List<Short> {
        val color = COLORS.getValue(QUADRANTS[y * 2 / HEIGHT][x * 2 / WIDTH])
        val red = Color.red(color).toFloat()
        val blue = Color.blue(color).toFloat()
        val luma = luma(red, Color.green(color).toFloat(), blue)
        return listOf(16 + 219 * luma / 255, 128 + 224 * (blue - luma) / (1.772f * 255), 128 + 224 * (red - luma) / (1.402f * 255)).map { (it.toInt() shl 8).toShort() }
    }

    /**
     * HeifWriter has no 10-bit mode, and AvifWriter's scrambles a P010 buffer and fails on a bitmap with eglCreateContext.
     * So this does what they do by hand: one frame of a 10-bit profile, muxed as an image.
     */
    private fun write10Bit(file: File, codecMime: String, profile: Int, imageMime: String, rotation: Int, exif: ByteArray?) {
        val format = MediaFormat.createVideoFormat(codecMime, WIDTH, HEIGHT).apply {
            setInteger(MediaFormat.KEY_PROFILE, profile)
            setInteger(MediaFormat.KEY_COLOR_FORMAT, CodecCapabilities.COLOR_FormatYUVP010)
            setInteger(MediaFormat.KEY_BIT_RATE, 4_000_000)
            setInteger(MediaFormat.KEY_FRAME_RATE, 30)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 0)
        }
        val name = MediaCodecList(MediaCodecList.REGULAR_CODECS).findEncoderForFormat(format) ?: error("no 10-bit encoder for $codecMime")
        val codec = MediaCodec.createByCodecName(name)
        val muxer = MediaMuxer(file.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_HEIF)
        try {
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
            val input = codec.dequeueInputBuffer(CODEC_TIMEOUT_US)
            check(input >= 0) { "no input buffer" }
            val written = fillP010(codec, input)
            codec.queueInputBuffer(input, 0, written, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            val info = MediaCodec.BufferInfo()
            var track = -1
            while (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM == 0) {
                val output = codec.dequeueOutputBuffer(info, CODEC_TIMEOUT_US)
                check(output != MediaCodec.INFO_TRY_AGAIN_LATER) { "encoder timed out" }
                if (output == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val muxed = codec.outputFormat.apply { setString(MediaFormat.KEY_MIME, imageMime) }
                    if (rotation > 0) muxer.setOrientationHint(rotation)
                    track = muxer.addTrack(muxed)
                    muxer.start()
                    exif?.let {
                        val sample = MediaCodec.BufferInfo().apply { set(0, it.size, 0, MUXER_DATA_FLAG) }
                        muxer.writeSampleData(track, ByteBuffer.wrap(it), sample)
                    }
                } else if (output >= 0) {
                    if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) muxer.writeSampleData(track, codec.getOutputBuffer(output)!!, info)
                    codec.releaseOutputBuffer(output, false)
                }
            }
            muxer.stop()
        } finally {
            codec.release()
            muxer.release()
        }
    }

    /** Copies the frame into the codec's own layout, whose stride only it knows. Returns the bytes it spans. */
    private fun fillP010(codec: MediaCodec, input: Int): Int {
        val image = codec.getInputImage(input)!!
        val (luma, u, v) = image.planes
        for (y in 0 until HEIGHT) {
            for (x in 0 until WIDTH) luma.buffer.order(ByteOrder.LITTLE_ENDIAN).putShort(y * luma.rowStride + x * luma.pixelStride, yuvAt(x, y)[0])
        }
        for (y in 0 until HEIGHT / 2) {
            for (x in 0 until WIDTH / 2) {
                val (_, cb, cr) = yuvAt(x * 2, y * 2)
                u.buffer.order(ByteOrder.LITTLE_ENDIAN).putShort(y * u.rowStride + x * u.pixelStride, cb)
                v.buffer.order(ByteOrder.LITTLE_ENDIAN).putShort(y * v.rowStride + x * v.pixelStride, cr)
            }
        }
        return codec.getInputBuffer(input)!!.capacity()
    }

    /** A little-endian TIFF with one tag, the orientation, behind the header HeifWriter asks for. */
    private fun exifBlock(orientation: Int): ByteArray {
        val tiff = ByteBuffer.allocate(26).order(ByteOrder.LITTLE_ENDIAN)
            .put("II".toByteArray()).putShort(42).putInt(8)
            .putShort(1).putShort(0x0112).putShort(3).putInt(1).putShort(orientation.toShort()).putShort(0)
            .putInt(0)
        return EXIF_HEADER.toByteArray() + tiff.array()
    }

    /** What the file really holds: the `irot` angle in quarter turns counterclockwise, 0 when it has none, and the EXIF orientation. */
    private fun boxes(file: File): Pair<Int, Int?> {
        val bytes = file.readBytes()
        val exif = String(bytes, Charsets.ISO_8859_1).indexOf(EXIF_HEADER + "II").takeIf { it >= 0 }?.let { start ->
            ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).getShort(start + EXIF_HEADER.length + 18).toInt()
        }
        return irot(ByteBuffer.wrap(bytes), 0, bytes.size) to exif
    }

    /** The type of each item, from its `infe` box: a tiled image has a `grid` item over its tiles. */
    private fun items(file: File): Map<String, Int> {
        val text = String(file.readBytes(), Charsets.ISO_8859_1)
        return Regex("infe").findAll(text).map { text.substring(it.range.first + 12, it.range.first + 16) }.groupingBy { it }.eachCount()
    }

    private fun irot(bytes: ByteBuffer, start: Int, end: Int): Int {
        var at = start
        while (at + 8 <= end) {
            val type = String(ByteArray(4) { bytes.get(at + 4 + it) }, Charsets.ISO_8859_1)
            val size = when (val short = bytes.getInt(at)) {
                0 -> end - at // to the end of the file
                1 -> bytes.getLong(at + 8).toInt() // a 64-bit size follows the type
                else -> short
            }
            when (type) {
                "irot" -> return bytes.get(at + 8).toInt() and 3
                "meta" -> irot(bytes, at + 12, at + size).let { if (it != 0) return it } // a full box: version and flags come first
                "iprp", "ipco" -> irot(bytes, at + 8, at + size).let { if (it != 0) return it }
            }
            at += size
        }
        return 0
    }

    private fun bitmapFactory(file: File): String {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        val bitmap = BitmapFactory.decodeFile(file.path)
        val seen = "${bounds.outMimeType} bounds ${bounds.outWidth}x${bounds.outHeight} pixels ${seen(bitmap)} ${bitmap.config}"
        bitmap.recycle()
        return seen
    }

    /** The platform's own view of the image: what a gallery built on it would show. */
    private fun imageDecoder(file: File): Seen {
        val bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(file)) { decoder, _, _ -> decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE }
        return seen(bitmap).also { bitmap.recycle() }
    }

    private fun seen(bitmap: Bitmap): Seen {
        val srgb = ColorSpace.get(ColorSpace.Named.SRGB)
        return Seen(
            bitmap.width,
            bitmap.height,
            layout(bitmap.width, bitmap.height) { x, y ->
                val color = bitmap.getColor(x, y).convert(srgb)
                luma(color.red() * 255, color.green() * 255, color.blue() * 255)
            },
        )
    }

    private fun layout(width: Int, height: Int, gray: (Int, Int) -> Float): String = listOf(1, 3).joinToString("/") { y ->
        listOf(1, 3).joinToString("") { x -> nearest(gray(width * x / 4, height * y / 4)) }
    }

    /** The quadrant with the closest luminance, which is all [loadGrayscale] leaves. */
    private fun nearest(gray: Float): String = COLORS.minBy { (_, color) ->
        abs(luma(Color.red(color).toFloat(), Color.green(color).toFloat(), Color.blue(color).toFloat()) - gray)
    }.key.toString()

    private fun luma(red: Float, green: Float, blue: Float) = 0.299f * red + 0.587f * green + 0.114f * blue

    private companion object {
        const val EXIF_HEADER = "Exif\u0000\u0000"
    }
}
