// Helpers shared by the group detectors: embedding similarity and group ids (xxh3 of the best
// copy's path, as in lab/src/cribrum_lab/detectors/).
package io.github.bert0ns.cribrum.detectors

import com.appmattus.crypto.Algorithm
import io.github.bert0ns.cribrum.hashing.toHex

internal fun dot(a: FloatArray, b: FloatArray): Float {
    var sum = 0f
    for (i in a.indices) {
        sum += a[i] * b[i]
    }
    return sum
}

internal fun groupId(path: String): String = pathDigest(path).toHex()

/** The big-endian xxh3 digest of the path, 8 bytes. */
internal fun pathDigest(path: String): ByteArray {
    val digest = Algorithm.XXH3_64().createDigest()
    val bytes = path.toByteArray()
    digest.update(bytes, 0, bytes.size)
    return digest.digest()
}
