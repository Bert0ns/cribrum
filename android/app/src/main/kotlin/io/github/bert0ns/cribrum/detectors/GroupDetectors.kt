package io.github.bert0ns.cribrum.detectors

import com.appmattus.crypto.Algorithm

internal fun dot(a: FloatArray, b: FloatArray): Float {
    var sum = 0f
    for (i in a.indices) {
        sum += a[i] * b[i]
    }
    return sum
}

internal fun groupId(path: String): String {
    val digest = Algorithm.XXH3_64().createDigest()
    val bytes = path.toByteArray()
    digest.update(bytes, 0, bytes.size)
    return digest.digest().joinToString("") { "%02x".format(it) }
}
