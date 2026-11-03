package io.github.bert0ns.cribrum.compress

import org.junit.Assert.assertEquals
import org.junit.Test

class SavingTest {
    /** The table of docs/design.md §9. */
    @Test
    fun `the 1 percent rule gives the target sizes of the design table`() {
        val table = mapOf(
            (16320 to 12240) to listOf(Target(1, 16320, 12240), Target(2, 8160, 6120), Target(4, 4000, 3000)),
            (12000 to 9000) to listOf(Target(1, 12000, 9000), Target(1, 8164, 6123), Target(2, 4000, 3000)),
            (8192 to 6144) to listOf(Target(1, 8192, 6144), Target(1, 8164, 6123), Target(2, 4000, 3000)),
            (8000 to 6000) to listOf(Target(1, 8000, 6000), Target(1, 8000, 6000), Target(2, 4000, 3000)),
            (4000 to 3000) to listOf(Target(1, 4000, 3000), Target(1, 4000, 3000), Target(1, 4000, 3000)),
        )
        for ((size, targets) in table) {
            assertEquals("$size", targets, CompressionLevel.entries.map { target(size.first, size.second, it) })
        }
    }

    /** #283 measured 28 to 31%, 81 to 86% and 95 to 96% on three 200 MP camera photos, which estimate at 95. */
    @Test
    fun `the estimate for a 200 MP camera photo is near what the phone saved at each level`() {
        val saved = CompressionLevel.entries.map { estimatedSaving(36_000_000, 16320, 12240, 95, it) / 36_000_000.0 }

        assertEquals(0.295, saved[0], 0.05)
        assertEquals(0.835, saved[1], 0.05)
        assertEquals(0.955, saved[2], 0.05)
    }

    @Test
    fun `a photo already saved below a level's quality at its size saves nothing there`() {
        assertEquals(0L, estimatedSaving(2_000_000, 4000, 3000, 70, CompressionLevel.HIGH))
        assertEquals(0L, estimatedSaving(2_000_000, 4000, 3000, 70, CompressionLevel.BALANCED))
        assertEquals(0L, estimatedSaving(2_000_000, 4000, 3000, null, CompressionLevel.SMALLEST))
    }
}
