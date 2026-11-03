// The compression levels, the resolution each one gives a photo, and how many bytes it would save (docs/design.md §9).
package io.github.bert0ns.cribrum.compress

import kotlin.math.sqrt

/** The JPEG quality of each level and the most pixels its result may have (§9, #173). */
enum class CompressionLevel(val id: String, val quality: Int, val maxPixels: Long?) {
    HIGH("high", 90, null),
    BALANCED("balanced", 85, 50_000_000),
    SMALLEST("smallest", 80, 12_000_000),
}

/** The power of two the decoder samples by, and the size of the result after the scale. */
data class Target(val sampleSize: Int, val width: Int, val height: Int)

/**
 * The 1% rule of §9. A photo at or below the limit is not sampled or scaled. Otherwise the sample size is the largest
 * power of two `s` with `ceil(w / s) × ceil(h / s) ≥ 0.99 × limit`, and a result still above the limit is scaled to
 * `floor(w' × f) × floor(h' × f)`, `f = sqrt(limit / (w' × h'))`.
 */
fun target(width: Int, height: Int, level: CompressionLevel): Target {
    val limit = level.maxPixels
    if (limit == null || width.toLong() * height <= limit) return Target(1, width, height)
    fun sampled(s: Int) = ceilDiv(width, s).toLong() * ceilDiv(height, s)
    var s = 1
    while (sampled(s * 2) * 100 >= limit * 99) s *= 2
    val w = ceilDiv(width, s)
    val h = ceilDiv(height, s)
    if (w.toLong() * h <= limit) return Target(s, w, h)
    // floor(w × f) is the largest x with x² × h ≤ w × limit: integers, so 4080 × (4000 / 4080) does not round to 3999.
    fun floorScaled(side: Int, other: Int): Int {
        var x = sqrt(side.toDouble() * limit / other).toLong()
        while (x * x * other > side * limit) x--
        while ((x + 1) * (x + 1) * other <= side * limit) x++
        return x.toInt()
    }
    return Target(s, floorScaled(w, h), floorScaled(h, w))
}

private fun ceilDiv(a: Int, b: Int) = (a + b - 1) / b

/**
 * The bytes a photo would have at [level]: its size, times the share of pixels the level keeps, times how much
 * bigger a JPEG is at the level's quality than at the [quality] the photo was saved at. A photo with no quality
 * estimate keeps its size. See [SIZE_BY_QUALITY] for the calibration.
 */
fun estimatedBytes(size: Long, width: Int, height: Int, quality: Int?, level: CompressionLevel): Long {
    if (quality == null) return size
    val target = target(width, height, level)
    val pixels = target.width.toDouble() * target.height / (width.toDouble() * height)
    return (size * pixels * ENCODER_GAP * relativeSize(level.quality) / relativeSize(quality)).toLong()
}

/** What [level] would save. A level that would not make the file smaller saves nothing. */
fun estimatedSaving(size: Long, width: Int, height: Int, quality: Int?, level: CompressionLevel): Long = (size - estimatedBytes(size, width, height, quality, level)).coerceAtLeast(0)

/**
 * A JPEG's size at each quality, relative to its size at 90: the median over 12 camera photos (6 of 12 MP, 3 of
 * 48 MP, 3 of 200 MP) encoded with Pillow's libjpeg-turbo at 4:2:0, in the lab. The estimate's saving was off by a
 * median of 0 points and at most 20 points of the original size on those photos (§9).
 */
private val SIZE_BY_QUALITY = listOf(50 to 0.377, 60 to 0.417, 70 to 0.49, 75 to 0.548, 80 to 0.635, 85 to 0.74, 90 to 1.0, 95 to 1.391, 100 to 2.47)

/** On the same photos, Pillow at the camera file's own estimated quality made a file 0.934 times its size (median). */
private const val ENCODER_GAP = 0.934

/** [SIZE_BY_QUALITY] at [quality], linear between its points and flat outside them. */
private fun relativeSize(quality: Int): Double {
    val (first, firstSize) = SIZE_BY_QUALITY.first()
    if (quality <= first) return firstSize
    val (hiQ, hiSize) = SIZE_BY_QUALITY.first { it.first >= quality }
    val (loQ, loSize) = SIZE_BY_QUALITY.last { it.first < quality }
    return loSize + (hiSize - loSize) * (quality - loQ) / (hiQ - loQ)
}

/** A photo the compress page lists, with what its scan read. Width and height are as displayed. */
data class CompressibleImage(val path: String, val size: Long, val width: Int, val height: Int, val info: JpegInfo) {
    fun saving(level: CompressionLevel): Long = estimatedSaving(size, width, height, info.quality, level)
}
