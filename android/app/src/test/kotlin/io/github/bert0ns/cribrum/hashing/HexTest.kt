package io.github.bert0ns.cribrum.hashing

import org.junit.Assert.assertEquals
import org.junit.Test

class HexTest {
    @Test
    fun `toHex matches the lowercase two digit format for every byte`() {
        val all = ByteArray(256) { it.toByte() }

        assertEquals(all.joinToString("") { "%02x".format(it) }, all.toHex())
        assertEquals("", ByteArray(0).toHex())
    }
}
