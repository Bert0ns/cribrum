package io.github.bert0ns.cribrum.golden

import io.github.bert0ns.cribrum.mobileclip.MobileClip
import io.github.bert0ns.cribrum.mobileclip.VISION_MODEL_ASSET
import io.github.bert0ns.cribrum.runtime.OnnxRuntime
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** MobileCLIP2-S0 embeddings of the golden fixtures, decoded by Android, against the lab's. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class GoldenEmbeddingTest {
    @Test
    fun `embeddings match the lab's`() {
        val model = MobileClip { OnnxRuntime(File("build/generated/mobileclip", VISION_MODEL_ASSET).readBytes()) }
        val embeddings = model.embedImages(Golden.images.map { File(it.image.path) })

        for ((expected, got) in Golden.images.zip(embeddings)) {
            val cosine = expected.embedding.zip(got) { a, b -> a * b }.sum()
            assertTrue("${expected.relative}: cosine $cosine", cosine >= MIN_COSINE)
        }
    }

    private companion object {
        const val MIN_COSINE = 0.999f
    }
}
