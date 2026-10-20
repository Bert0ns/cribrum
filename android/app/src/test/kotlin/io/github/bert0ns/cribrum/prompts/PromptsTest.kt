// Tests for Prompts translated from lab/tests/test_zero_shot.py.
package io.github.bert0ns.cribrum.prompts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PromptsTest {
    @Test
    fun `stored embeddings cover exactly the current prompts`() {
        val stored = Prompts.load()
        assertEquals(Prompts.VERSION, stored.version)
        assertEquals(Prompts.MODEL_ID, stored.model)
        val expectedPrompts = Prompts.PROMPTS.values.flatten()
        assertEquals(expectedPrompts, stored.prompts)
    }

    @Test
    fun `every prompt appears once`() {
        val every = Prompts.PROMPTS.values.flatten()
        assertEquals(every.size, every.toSet().size)
    }

    @Test
    fun `class probabilities sum to one`() {
        val embedding = Prompts.promptVector("a photo of a receipt")
        val classes = Prompts.classify(embedding)

        val totalProbability = classes.values.fold(0f) { sum, (prob, _) -> sum + prob }
        assertEquals(1.0f, totalProbability, 1e-5f)
        assertEquals("a photo of a receipt", classes["document"]?.second)
    }

    @Test
    fun `refuses file with wrong version or model`() {
        assertThrows(IllegalStateException::class.java) {
            Prompts.parse("""{"version": 999, "model": "${Prompts.MODEL_ID}", "prompts": {}}""")
        }
        assertThrows(IllegalStateException::class.java) {
            Prompts.parse("""{"version": ${Prompts.VERSION}, "model": "other_model", "prompts": {}}""")
        }
    }
}
