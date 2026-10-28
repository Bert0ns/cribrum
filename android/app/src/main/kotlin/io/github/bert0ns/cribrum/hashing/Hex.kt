package io.github.bert0ns.cribrum.hashing

private val HEX = "0123456789abcdef".toCharArray()

/** Lowercase hex, two digits per byte, the same text as `"%02x".format` for each byte. */
internal fun ByteArray.toHex(): String {
    val out = CharArray(size * 2)
    for (i in indices) {
        val b = this[i].toInt() and 0xff
        out[2 * i] = HEX[b ushr 4]
        out[2 * i + 1] = HEX[b and 0xf]
    }
    return String(out)
}
