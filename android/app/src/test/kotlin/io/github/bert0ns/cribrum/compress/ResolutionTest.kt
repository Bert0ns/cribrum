package io.github.bert0ns.cribrum.compress

import android.graphics.BitmapFactory
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The sizes the decoder gives at the sample size of each row of docs/design.md §9, before any scale. */
private val DECODED = mapOf(
    (16320 to 12240) to listOf(16320 to 12240, 8160 to 6120, 4080 to 3060),
    (12000 to 9000) to listOf(12000 to 9000, 12000 to 9000, 6000 to 4500),
    (8192 to 6144) to listOf(8192 to 6144, 8192 to 6144, 4096 to 3072),
    (8000 to 6000) to listOf(8000 to 6000, 8000 to 6000, 4000 to 3000),
    (4000 to 3000) to listOf(4000 to 3000, 4000 to 3000, 4000 to 3000),
)

/** [target] assumes the decoder rounds a sampled side up. These check that it does. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36])
class ResolutionTest {
    @Test
    fun `the decoder rounds a sampled size as the table does`() {
        val small = encodedJpeg(64, 48, 90)
        for ((size, decoded) in DECODED) {
            val header = withSize(small, size.first, size.second)
            for ((level, expected) in CompressionLevel.entries.zip(decoded)) {
                val bounds = BitmapFactory.Options().apply {
                    inJustDecodeBounds = true
                    inSampleSize = target(size.first, size.second, level).sampleSize
                }
                BitmapFactory.decodeByteArray(header, 0, header.size, bounds)
                assertEquals("$size $level", expected, bounds.outWidth to bounds.outHeight)
            }
        }
    }

    @Test
    fun `a real decode of an odd size rounds up`() {
        val odd = encodedJpeg(1001, 751, 90)
        for ((sample, size) in listOf(2 to (501 to 376), 4 to (251 to 188), 8 to (126 to 94))) {
            val bitmap = BitmapFactory.decodeByteArray(odd, 0, odd.size, BitmapFactory.Options().apply { inSampleSize = sample })
            assertEquals(size, bitmap.width to bitmap.height)
        }
    }
}
