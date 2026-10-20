package io.github.bert0ns.cribrum.golden

import io.github.bert0ns.cribrum.detectors.AnalysisContext
import io.github.bert0ns.cribrum.detectors.ExactDuplicateDetector
import io.github.bert0ns.cribrum.detectors.ScreenshotDetector
import io.github.bert0ns.cribrum.detectors.StickerDetector
import io.github.bert0ns.cribrum.hashing.contentHash
import io.github.bert0ns.cribrum.metadata.readMetadata
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Metadata, content hashes, SCREENSHOT, STICKER and EXACT_DUPLICATE against the lab's golden fixtures. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE) // Android's real decoders, not Robolectric's stand-ins
class GoldenMetadataTest {
    @Test
    fun `there are fixtures to compare with`() {
        assertTrue(Golden.images.size >= 10)
    }

    @Test
    fun `every fixture carries a unit-length embedding`() {
        for (expected in Golden.images) {
            assertEquals(expected.relative, 512, expected.embedding.size)
            assertEquals(expected.relative, 1f, expected.embedding.fold(0f) { sum, x -> sum + x * x }, 1e-4f)
        }
    }

    @Test
    fun `metadata matches exactly`() {
        for (expected in Golden.images) {
            val meta = readMetadata(File(expected.image.path))

            assertEquals(expected.relative, expected.width to expected.height, meta.width to meta.height)
            assertEquals(expected.relative, expected.hasCameraExif, meta.hasCameraExif)
        }
    }

    @Test
    fun `content hashes match exactly`() {
        for (expected in Golden.images) {
            assertEquals(expected.relative, expected.contentHash, contentHash(expected.image))
        }
    }

    @Test
    fun `screenshot scores match`() = runBlocking {
        val detector = ScreenshotDetector()
        for (expected in Golden.images) {
            val want = expected.signal(detector.id)!!
            val got = detector.analyze(expected.image, AnalysisContext()).single()

            assertEquals(expected.relative, want.version, got.detectorVersion)
            assertEquals(expected.relative, want.category, got.kind.value)
            assertEquals(expected.relative, want.score, got.score, Golden.scoreTolerance)
            assertEquals(expected.relative, want.evidence, got.evidence)
        }
    }

    @Test
    fun `sticker scores match exactly`() = runBlocking {
        val detector = StickerDetector()
        for (expected in Golden.images) {
            val want = expected.signal(detector.id)!!
            val got = detector.analyze(expected.image, AnalysisContext()).single()

            assertEquals(expected.relative, want.score, got.score)
            assertEquals(expected.relative, want.evidence, got.evidence)
        }
    }

    @Test
    fun `exact duplicate groups and roles match exactly`() = runBlocking {
        val detector = ExactDuplicateDetector()

        val found = detector.analyze(Golden.images.map { it.image }, AnalysisContext())

        for (expected in Golden.images) {
            val want = expected.signal(detector.id)
            val got = found[expected.image]?.single()
            assertEquals(expected.relative, want?.evidence, got?.evidence)
            assertEquals(expected.relative, want?.score, got?.score)
            if (want != null) assertEquals(expected.relative, want.version, got!!.detectorVersion)
        }
    }
}
