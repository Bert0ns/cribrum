// Synthetic test images, written with Android's own encoders.
package io.github.bert0ns.cribrum.imaging

import android.graphics.Bitmap
import java.io.File
import kotlin.math.sqrt
import kotlin.random.Random

/** Luminance values row by row, as an editable image. */
class TestImage(val width: Int, val height: Int, val values: IntArray = IntArray(width * height)) {
    operator fun get(x: Int, y: Int) = values[y * width + x]
    operator fun set(x: Int, y: Int, value: Int) {
        values[y * width + x] = value.coerceIn(0, 255)
    }

    fun paste(other: TestImage, left: Int, top: Int) {
        for (y in 0 until other.height) for (x in 0 until other.width) this[left + x, top + y] = other[x, y]
    }

    /** Box blur repeated three times: close enough to a Gaussian of the same radius. */
    fun blurred(radius: Int): TestImage {
        if (radius == 0) return this
        var image = this
        repeat(3) {
            val out = TestImage(width, height)
            for (y in 0 until height) for (x in 0 until width) {
                var sum = 0
                var count = 0
                for (dy in -radius..radius) for (dx in -radius..radius) {
                    val sx = x + dx
                    val sy = y + dy
                    if (sx in 0 until width && sy in 0 until height) {
                        sum += image[sx, sy]
                        count++
                    }
                }
                out[x, y] = sum / count
            }
            image = out
        }
        return image
    }

    fun save(file: File, format: Bitmap.CompressFormat = Bitmap.CompressFormat.PNG): File {
        val pixels = IntArray(values.size) { values[it].let { v -> (0xff shl 24) or (v shl 16) or (v shl 8) or v } }
        val bitmap = Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
        file.outputStream().use { bitmap.compress(format, 95, it) }
        return file
    }

    companion object {
        fun filled(width: Int, height: Int, value: Int) = TestImage(width, height, IntArray(width * height) { value })

        /** Random 8 px blocks: plenty of detail for the blur measure. */
        fun texture(width: Int = 800, height: Int = 600, seed: Int = 0): TestImage {
            val random = Random(seed)
            val blocks = IntArray((width / 8 + 1) * (height / 8 + 1)) { random.nextInt(256) }
            return TestImage(width, height, IntArray(width * height) { i -> blocks[(i / width / 8) * (width / 8 + 1) + i % width / 8] })
        }

        fun noisy(level: Int, spread: Double = 8.0, seed: Int = 0): TestImage {
            val random = java.util.Random(seed.toLong())
            return TestImage(400, 300, IntArray(400 * 300) { (level + random.nextGaussian() * spread).toInt().coerceIn(0, 255) })
        }
    }
}

/** A 512-float vector with the given leading values, L2-normalized: a fake embedding. */
fun unit(vararg values: Float): FloatArray {
    val vector = FloatArray(512)
    var norm = 0f
    for (i in values.indices) {
        vector[i] = values[i]
        norm += values[i] * values[i]
    }
    val length = sqrt(norm)
    for (i in values.indices) {
        vector[i] /= length
    }
    return vector
}
