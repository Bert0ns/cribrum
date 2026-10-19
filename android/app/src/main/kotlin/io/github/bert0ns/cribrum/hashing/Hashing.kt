// Hashes used to find duplicates. Mirrors lab/src/cribrum_lab/hashing.py (docs/design.md §5).
package io.github.bert0ns.cribrum.hashing

import com.appmattus.crypto.Algorithm
import io.github.bert0ns.cribrum.model.ImageRef
import java.io.File

private const val CHUNK = 1 shl 20

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
    return digest.digest().joinToString("") { "%02x".format(it) }
}
