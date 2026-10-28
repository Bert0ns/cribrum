// Hashes used to find duplicates. Mirrors lab/src/cribrum_lab/hashing.py (docs/design.md §5).
package io.github.bert0ns.cribrum.hashing

import com.appmattus.crypto.Algorithm
import io.github.bert0ns.cribrum.imaging.loadGrayscale
import io.github.bert0ns.cribrum.imaging.resize
import io.github.bert0ns.cribrum.model.ImageRef
import java.io.File
import kotlin.math.cos
import kotlin.math.round
import kotlin.math.sqrt

private const val CHUNK = 1 shl 20
const val PHASH_SIZE = 32
const val PHASH_DECODE_SIDE = 256

/** xxHash3 64-bit digest of the whole file, as hex. Throws an IOException if it can't be read. */
fun contentHash(image: ImageRef): String {
    val digest = Algorithm.XXH3_64().createDigest()
    val buffer = ByteArray(CHUNK)
    File(image.path).inputStream().use { input ->
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
    }
    return digest.digest().toHex()
}

/**
 * 64-bit pHash: the signs of the 8x8 lowest frequencies of a 32x32 DCT against their median.
 *
 * Resizing and recompression barely change it: near-duplicates differ in a few bits.
 * [PHASH_DECODE_SIDE] and the resampling are part of the hash: changing them means
 * bumping the version of every detector that uses it.
 */
fun perceptualHash(image: ImageRef): ULong {
    val gray = loadGrayscale(File(image.path), PHASH_DECODE_SIDE)
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

/**
 * True for a hash that is 0 or has only the DC bit set, which has no AC energy to tell it apart.
 * A uniform image gives one of them, since every AC term rounds to 0: black hashes to 0 and any
 * other flat image to the DC bit, all within a bit of each other. A few simple shapes give the
 * same hash and are treated the same way.
 */
fun isFlatHash(hash: ULong): Boolean = hash == 0uL || hash == (1uL shl 63)

private val DCT = dctMatrix(PHASH_SIZE)

/** Orthonormal DCT-II matrix: `M @ x` is the DCT of the column vector x. */
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
