package io.github.bert0ns.cribrum.pipeline

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.exifinterface.media.ExifInterface
import io.github.bert0ns.cribrum.detectors.BlurDetector
import io.github.bert0ns.cribrum.detectors.ExposureDetector
import io.github.bert0ns.cribrum.detectors.FaceDetector
import io.github.bert0ns.cribrum.detectors.ScreenshotDetector
import io.github.bert0ns.cribrum.detectors.StickerDetector
import io.github.bert0ns.cribrum.detectors.YuNet
import io.github.bert0ns.cribrum.golden.Golden
import io.github.bert0ns.cribrum.hashing.PHASH_DECODE_SIDE
import io.github.bert0ns.cribrum.hashing.PHASH_SIZE
import io.github.bert0ns.cribrum.imaging.Argb
import io.github.bert0ns.cribrum.imaging.Gray
import io.github.bert0ns.cribrum.imaging.MAX_PIXELS
import io.github.bert0ns.cribrum.imaging.decode
import io.github.bert0ns.cribrum.imaging.draftScale
import io.github.bert0ns.cribrum.imaging.resampleChannels
import io.github.bert0ns.cribrum.imaging.resize
import io.github.bert0ns.cribrum.imaging.thumbnail
import io.github.bert0ns.cribrum.imaging.transpose
import io.github.bert0ns.cribrum.imaging.turnedSize
import io.github.bert0ns.cribrum.index.memoryIndex
import io.github.bert0ns.cribrum.metadata.UnreadableImage
import io.github.bert0ns.cribrum.model.Category
import io.github.bert0ns.cribrum.model.Protection
import io.github.bert0ns.cribrum.model.Signal
import io.github.bert0ns.cribrum.runtime.ModelRuntime
import io.github.bert0ns.cribrum.runtime.OnnxRuntime
import io.github.bert0ns.cribrum.runtime.Tensor
import io.github.bert0ns.cribrum.sources.FilesystemImageSource
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.IOException
import java.nio.FloatBuffer
import java.nio.IntBuffer
import java.util.Collections
import java.util.Locale
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.round
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * The detectors phase as it was before #353, copied from 5ed8d21: each step decodes the image, reads its EXIF and
 * computes its grayscale on its own. Every function #353 changed is copied here, and the unchanged ones it calls
 * (thumbnail, transpose, resize, resampleChannels, turnedSize, draftScale, YuNet.detect's decoding) are called.
 * Do not optimize it.
 */
private object Before {
    fun decode(file: File, draftSide: Int, maxPixels: Int = MAX_PIXELS): Argb {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw UnreadableImage(file)
        val options = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inPremultiplied = false
            inSampleSize = if (bounds.outMimeType == "image/jpeg") {
                draftScale(bounds.outWidth, bounds.outHeight, draftSide)
            } else {
                sampleFor(bounds.outWidth, bounds.outHeight, maxPixels)
            }
        }
        val bitmap = BitmapFactory.decodeFile(file.path, options) ?: throw UnreadableImage(file)
        try {
            val argb = IntArray(bitmap.width * bitmap.height)
            if (bitmap.config == Bitmap.Config.ARGB_8888 && bitmap.colorSpace?.isSrgb == false) {
                bitmap.copyPixelsToBuffer(IntBuffer.wrap(argb))
                for (i in argb.indices) argb[i] = (argb[i] and 0xff00ff00.toInt()) or (argb[i] shl 16 and 0xff0000) or (argb[i] shr 16 and 0xff)
            } else {
                bitmap.getPixels(argb, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            }
            return Argb(bitmap.width, bitmap.height, argb)
        } finally {
            bitmap.recycle()
        }
    }

    private fun sampleFor(width: Int, height: Int, maxPixels: Int): Int = generateSequence(1) { it * 2 }.first { width.toLong() * height / (it.toLong() * it) <= maxPixels }

    private fun luminance(width: Int, height: Int, argb: IntArray): Gray {
        val pixels = FloatArray(argb.size) { i ->
            val c = argb[i]
            (((c shr 16 and 0xff) * 19595 + (c shr 8 and 0xff) * 38470 + (c and 0xff) * 7471 + 0x8000) shr 16).toFloat()
        }
        return Gray(width, height, pixels)
    }

    fun orientation(file: File): Int = try {
        ExifInterface(file).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
    } catch (_: IOException) {
        ExifInterface.ORIENTATION_NORMAL
    }

    fun loadGrayscale(file: File, maxSide: Int): Gray {
        val (width, height, argb) = decode(file, maxSide)
        return thumbnail(transpose(luminance(width, height, argb), orientation(file)), maxSide)
    }

    fun blur(file: File): List<Signal> {
        val (globalVariance, peakVariance) = measure(loadGrayscale(file, BlurDetector.MAX_SIDE)) ?: return emptyList()
        val score = (BlurDetector.HALF_SCORE_VARIANCE / (BlurDetector.HALF_SCORE_VARIANCE + peakVariance)).toFloat()
        val evidence = mapOf(
            "global_variance" to "%.1f".format(Locale.ROOT, globalVariance),
            BlurDetector.PEAK_VARIANCE_KEY to "%.1f".format(Locale.ROOT, peakVariance),
        )
        return listOf(Signal("blur", 1, Category.BLURRY, score, evidence))
    }

    private fun measure(gray: Gray): Pair<Double, Double>? {
        val w = gray.width - 2
        val h = gray.height - 2
        if (w <= 0 || h <= 0) return null
        val laplacian = DoubleArray(w * h) { i ->
            val x = i % w + 1
            val y = i / w + 1
            (gray[x, y - 1] + gray[x, y + 1] + gray[x - 1, y] + gray[x + 1, y] - 4 * gray[x, y]).toDouble()
        }
        var peak = 0.0
        for (rows in split(h, BlurDetector.TILES)) {
            for (columns in split(w, BlurDetector.TILES)) {
                if (rows.isEmpty() || columns.isEmpty()) continue
                peak = maxOf(peak, variance(rows.flatMap { y -> columns.map { x -> laplacian[y * w + x] } }))
            }
        }
        return variance(laplacian.asList()) to peak
    }

    private fun split(n: Int, parts: Int): List<IntRange> {
        var start = 0
        return (0 until parts).map { i ->
            val size = n / parts + if (i < n % parts) 1 else 0
            (start until start + size).also { start += size }
        }
    }

    private fun variance(values: List<Double>): Double {
        val mean = values.sum() / values.size
        return values.sumOf { (it - mean) * (it - mean) } / values.size
    }

    fun exposure(file: File): Signal {
        val sorted = loadGrayscale(file, ExposureDetector.MAX_SIDE).pixels.sortedArray()
        val (p1, p5, p95, p99) = listOf(1.0, 5.0, 95.0, 99.0).map { percentile(sorted, it) }
        val cases = linkedMapOf(
            "dark" to fade(p95 - ExposureDetector.DARK_LEVEL),
            "bright" to fade(ExposureDetector.BRIGHT_LEVEL - p5),
            "flat" to fade(p99 - p1),
        )
        val case = cases.maxBy { it.value }.key
        val evidence = mapOf("case" to case) +
            cases.map { (name, value) -> "${name}_score" to "%.3f".format(Locale.ROOT, value) }
        return Signal("exposure", 1, Category.BAD_EXPOSURE, cases.getValue(case).toFloat(), evidence)
    }

    private fun percentile(sorted: FloatArray, q: Double): Double {
        val rank = q / 100 * (sorted.size - 1)
        val low = rank.toInt()
        val high = minOf(low + 1, sorted.size - 1)
        return sorted[low] + (sorted[high] - sorted[low]) * (rank - low)
    }

    private fun fade(distance: Double): Double = (1.0 - distance / ExposureDetector.RAMP).coerceIn(0.0, 1.0)

    fun perceptualHash(file: File): ULong {
        val gray = loadGrayscale(file, PHASH_DECODE_SIDE)
        val small = resize(gray, PHASH_SIZE, PHASH_SIZE)

        val intermediate = DoubleArray(PHASH_SIZE * PHASH_SIZE)
        for (i in 0 until PHASH_SIZE) {
            val iOffset = i * PHASH_SIZE
            for (j in 0 until PHASH_SIZE) {
                var sum = 0.0
                for (k in 0 until PHASH_SIZE) {
                    sum += DCT[iOffset + k] * small[j, k]
                }
                intermediate[iOffset + j] = sum
            }
        }

        val low = DoubleArray(64)
        for (i in 0 until 8) {
            val iOffset = i * PHASH_SIZE
            for (j in 0 until 8) {
                val jOffset = j * PHASH_SIZE
                var sum = 0.0
                for (k in 0 until PHASH_SIZE) {
                    sum += intermediate[iOffset + k] * DCT[jOffset + k]
                }
                low[i * 8 + j] = round(sum * 1e6) / 1e6
            }
        }

        val sorted = low.clone().apply { sort() }
        val median = (sorted[31] + sorted[32]) / 2.0

        var hash = 0uL
        for (i in 0 until 64) {
            if (low[i] > median) {
                hash = hash or (1uL shl (63 - i))
            }
        }
        return hash
    }

    private val DCT = dctMatrix(PHASH_SIZE)

    private fun dctMatrix(n: Int): DoubleArray {
        val m = DoubleArray(n * n)
        val scale = sqrt(2.0 / n)
        for (k in 0 until n) {
            for (i in 0 until n) {
                var v = cos(Math.PI * (2 * i + 1) * k / (2.0 * n)) * scale
                if (k == 0) v /= sqrt(2.0)
                m[k * n + i] = v
            }
        }
        return m
    }

    /** YuNet's input, and the image's size in it. */
    class Letterbox(val pixels: FloatArray, val width: Int, val height: Int)

    fun letterbox(file: File): Letterbox {
        val decoded = decode(file, YuNet.INPUT_SIDE)
        val orientation = orientation(file)
        val (turnedW, turnedH) = turnedSize(decoded, orientation)
        val scale = YuNet.INPUT_SIDE.toDouble() / max(turnedW, turnedH)
        val width = max(1, Math.rint(turnedW * scale).toInt())
        val height = max(1, Math.rint(turnedH * scale).toInt())
        val (red, green, blue) = resampleChannels(decoded, orientation, width, height)
        val canvas = FloatArray(3 * YuNet.INPUT_SIDE * YuNet.INPUT_SIDE)
        for ((c, plane) in listOf(blue, green, red).withIndex()) {
            for (y in 0 until height) plane.pixels.copyInto(canvas, (c * YuNet.INPUT_SIDE + y) * YuNet.INPUT_SIDE, y * width, (y + 1) * width)
        }
        return Letterbox(canvas, width, height)
    }

    fun faces(boxed: Letterbox, runtime: ModelRuntime): Signal {
        val faces = YuNet.detect(runtime, FloatBuffer.wrap(boxed.pixels))
        val evidence = mutableMapOf("faces" to faces.size.toString())
        var score = 0f
        if (faces.isNotEmpty()) {
            score = faces.maxOf { it.confidence }
            val area = faces.maxOf { it.width * it.height }
            evidence["largest_face"] = "%.4f".format(Locale.ROOT, area / (boxed.width.toDouble() * boxed.height))
        }
        return Signal("faces", 1, Protection.FACES, score, evidence)
    }
}

/** A photo-like JPEG: colour gradients, sharp blocks and noise, so every measure has something to read. */
private fun photo(file: File, width: Int, height: Int, seed: Int, orientation: Int? = null): File {
    val random = Random(seed)
    val pixels = IntArray(width * height) { i ->
        val x = i % width
        val y = i / width
        val block = if ((x / 97 + y / 61) % 3 == 0) 90 else 0
        val r = (x * 255 / width + block + random.nextInt(-12, 13)).coerceIn(0, 255)
        val g = (y * 255 / height - block + random.nextInt(-12, 13)).coerceIn(0, 255)
        val b = ((x + y) * 255 / (width + height) + random.nextInt(-12, 13)).coerceIn(0, 255)
        (0xff shl 24) or (r shl 16) or (g shl 8) or b
    }
    val bitmap = Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
    file.parentFile!!.mkdirs()
    file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
    orientation?.let { ExifInterface(file).apply { setAttribute(ExifInterface.TAG_ORIENTATION, "$it") }.saveAttributes() }
    return file
}

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SharedDecodeTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val index = memoryIndex()

    @After
    fun close() = index.close()

    @Test
    fun `the detectors phase stores the signals, evidence and hashes of one decode per step`() = runBlocking {
        val root = File(folder.root, "device")
        for (expected in Golden.images) File(expected.image.path).copyTo(File(root, expected.relative))
        // The golden JPEGs are at most 640 x 480 and never draft (#231). These do: three sample sizes, then two, turned.
        val large = photo(File(root, "DCIM/Camera/large.jpg"), 1600, 1200, seed = 1)
        val turned = photo(File(root, "DCIM/Camera/turned.jpg"), 1800, 1300, seed = 2, orientation = 6)
        assertEquals(listOf(1600, 800, 400), listOf(640, 512, 256).map { decode(large, it).width })
        assertEquals(listOf(900, 900, 450), listOf(640, 512, 256).map { decode(turned, it).width })

        val yunet = OnnxRuntime(javaClass.getResourceAsStream("/face_detection_yunet_2023mar.onnx")!!.use { it.readBytes() }, threads = 1)
        val seen = Collections.synchronizedList(mutableListOf<FloatArray>())
        val recording = object : ModelRuntime {
            override fun run(inputs: Map<String, Tensor>): Map<String, Tensor> {
                val input = inputs.getValue("input").values
                seen += FloatArray(input.limit()) { input[it] }
                return yunet.run(inputs)
            }
        }
        val detectors = listOf(BlurDetector(), ExposureDetector(), ScreenshotDetector(), FaceDetector(recording), StickerDetector())

        val stats = Pipeline(index, detectors, emptyList()).run(FilesystemImageSource(root))

        assertEquals(emptyList<Failure>(), stats.failures)
        val files = root.walk().filter { it.isFile }.toList()
        assertEquals(Golden.images.size + 2, files.size)
        for (file in files) {
            val name = file.relativeTo(root).path
            val stored = index.signals(file.path).associateBy { it.detectorId }
            // The detectors ran by draft side, but the index lists their signals in the detectors' own order, as before.
            assertEquals(name, detectors.map { it.id }.filter { it in stored }, index.signals(file.path).map { it.detectorId }.distinct())
            assertEquals(name, Before.blur(file), listOfNotNull(stored["blur"]))
            assertEquals(name, Before.exposure(file), stored["exposure"])
            assertEquals(name, Before.perceptualHash(file), index.perceptualHash(file.path))
            // No golden has a face, so the faces score is 0 everywhere: what YuNet read is the stronger check.
            val boxed = Before.letterbox(file)
            assertTrue(name, seen.any { it.contentEquals(boxed.pixels) })
            assertEquals(name, Before.faces(boxed, yunet), stored["faces"])
        }
        assertEquals(files.size, seen.size)
    }
}
