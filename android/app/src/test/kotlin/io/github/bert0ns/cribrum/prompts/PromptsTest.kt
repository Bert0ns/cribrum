// Tests for Prompts translated from lab/tests/test_zero_shot.py.
package io.github.bert0ns.cribrum.prompts

import io.github.bert0ns.cribrum.mobileclip.MODEL_ID
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

private val STORED: JSONObject by lazy {
    val stream = PromptsTest::class.java.getResourceAsStream("/prompt_embeddings.json")
        ?: error("Resource /prompt_embeddings.json not found on classpath")
    JSONObject(stream.bufferedReader().use { it.readText() })
}

/** An image embedding that sits exactly on a prompt: the class it belongs to wins. */
fun promptVector(text: String): FloatArray {
    return decodeVector(STORED.getJSONObject("prompts").getString(text))
}

@RunWith(RobolectricTestRunner::class)
class PromptsTest {
    @Test
    fun `stored embeddings cover exactly the current prompts`() {
        assertEquals(VERSION, STORED.getInt("version"))
        assertEquals(MODEL_ID, STORED.getString("model"))
        val expectedPrompts = PROMPTS.values.flatten()
        val storedPrompts = STORED.getJSONObject("prompts").keys().asSequence().toList()
        assertEquals(expectedPrompts, storedPrompts)
    }

    @Test
    fun `every prompt appears once`() {
        val every = PROMPTS.values.flatten()
        assertEquals(every.size, every.toSet().size)
    }

    @Test
    fun `class probabilities sum to one`() {
        val embedding = promptVector("a photo of a receipt")
        val classes = classify(embedding)

        val totalProbability = classes.values.fold(0f) { sum, (prob, _) -> sum + prob }
        assertEquals(1.0f, totalProbability, 1e-5f)
        assertEquals("a photo of a receipt", classes["document"]?.second)
    }

    @Test
    fun `refuses file with wrong version or model`() {
        assertThrows(IllegalStateException::class.java) {
            parse("""{"version": 999, "model": "$MODEL_ID", "prompts": {}}""")
        }
        assertThrows(IllegalStateException::class.java) {
            parse("""{"version": $VERSION, "model": "other_model", "prompts": {}}""")
        }
    }
}
