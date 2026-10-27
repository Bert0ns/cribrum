// A stop request must reach the group detectors: they may not swallow it as a per-image failure.
package io.github.bert0ns.cribrum.detectors

import io.github.bert0ns.cribrum.metadata.ImageMetadata
import io.github.bert0ns.cribrum.model.ImageRef
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

class GroupCancellationTest {
    private fun images(count: Int) = List(count) { ImageRef("/photos/$it.jpg", 1000, it.toLong()) }

    private fun stopped(): Nothing = throw CancellationException("stopped")

    @Test
    fun `the near duplicate detector lets a stop through a hash lookup`() {
        val context = AnalysisContext(perceptualHash = { stopped() })

        assertThrows(CancellationException::class.java) { runBlocking { NearDuplicateDetector().analyze(images(3), context) } }
    }

    @Test
    fun `the near duplicate detector lets a stop through an embedding lookup`() {
        val context = AnalysisContext(perceptualHash = { 0uL }, embedding = { stopped() })

        assertThrows(CancellationException::class.java) { runBlocking { NearDuplicateDetector().analyze(images(3), context) } }
    }

    @Test
    fun `the near duplicate detector lets a stop through a metadata lookup`() {
        // Equal hashes link the pair, so the detector reads the metadata to pick the best of the group.
        val context = AnalysisContext(perceptualHash = { 0b1010uL }, metadata = { stopped() })

        assertThrows(CancellationException::class.java) { runBlocking { NearDuplicateDetector().analyze(images(2), context) } }
    }

    /**
     * What [block] threw or returned. runBlocking rethrows when its own job was cancelled, even if the block
     * returned, so the outcome is recorded inside it.
     */
    private fun outcome(block: suspend () -> Any?): Result<Any?> {
        var outcome: Result<Any?>? = null
        runCatching { runBlocking { outcome = runCatching { block() } } }
        return outcome!!
    }

    private fun assertStopped(outcome: Result<Any?>) = assertTrue("returned ${outcome.getOrNull()}", outcome.exceptionOrNull() is CancellationException)

    @Test
    fun `the near duplicate detector checks for a stop in the hash pair loop`() {
        val photos = images(50)
        // The last lookup cancels the job without throwing, so only a check in the pair loop can notice.
        // No embeddings, so the embedding loop has no row to check.
        val context = AnalysisContext(
            perceptualHash = { image ->
                if (image == photos.last()) currentCoroutineContext().job.cancel()
                (image.mtimeNs + 1).toULong() shl 20 // not flat
            },
        )

        assertStopped(outcome { NearDuplicateDetector().analyze(photos, context) })
    }

    @Test
    fun `the near duplicate detector checks for a stop in the embedding pair loop`() {
        val photos = images(50)
        val vector = FloatArray(4) { 0.5f }
        // Flat hashes, so the hash loop has no row to check.
        val context = AnalysisContext(
            perceptualHash = { 0uL },
            embedding = { image ->
                if (image == photos.last()) currentCoroutineContext().job.cancel()
                vector
            },
        )

        assertStopped(outcome { NearDuplicateDetector().analyze(photos, context) })
    }

    @Test
    fun `the burst detector checks for a stop before it decodes a burst`() {
        val photos = images(3)
        val captured = LocalDateTime.of(2024, 5, 1, 10, 0, 0)
        // The last metadata lookup cancels the job without throwing; the three shots make one burst.
        val context = AnalysisContext(
            metadata = { image ->
                if (image == photos.last()) currentCoroutineContext().job.cancel()
                ImageMetadata(10, 10, null, "Google", "Pixel", captured.plusSeconds(image.mtimeNs))
            },
        )

        assertStopped(outcome { BurstDetector().analyze(photos, context) })
    }

    @Test
    fun `the burst detector lets a stop through a metadata lookup`() {
        val context = AnalysisContext(metadata = { stopped() })

        assertThrows(CancellationException::class.java) { runBlocking { BurstDetector().analyze(images(3), context) } }
    }

    @Test
    fun `the burst detector lets a stop through an embedding lookup`() {
        val captured = LocalDateTime.of(2024, 5, 1, 10, 0, 0)
        val context = AnalysisContext(metadata = { ImageMetadata(10, 10, null, "Google", "Pixel", captured) }, embedding = { stopped() })

        assertThrows(CancellationException::class.java) { runBlocking { BurstDetector().analyze(images(3), context) } }
    }
}
