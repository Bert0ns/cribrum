// Zero-shot classes and their MobileCLIP prompts (docs/design.md §5). Mirrors lab/src/cribrum_lab/prompts.py.
package io.github.bert0ns.cribrum.prompts

import org.json.JSONObject
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64
import kotlin.math.exp

object Prompts {
    const val VERSION = 1
    const val MODEL_ID = "mobileclip2_s0"
    const val DIMENSIONS = 512
    const val LOGIT_SCALE = 64.33f

    val PROMPTS: Map<String, List<String>> = mapOf(
        "document" to listOf(
            "a photo of a projector slide in a classroom",
            "a photo of a presentation slide on a screen",
            "a photo of a whiteboard with writing",
            "a photo of a blackboard with writing",
            "a photo of handwritten notes on paper",
            "a photo of handwritten homework in a notebook",
            "a photo of math exercises on paper",
            "a photo of a printed page of text",
            "a photo of a page of a book",
            "a photo of a document",
            "a photo of a receipt",
            "a photo of a form or a contract",
            "a photo of a ticket or a bill",
            "a photo of a timetable or a notice board",
            "a photo of a computer monitor showing text",
        ),
        "forwarded" to listOf(
            "an internet meme",
            "a meme with a caption",
            "a funny picture with text on top",
            "a sticker",
            "a cartoon sticker",
            "an emoji sticker",
            "a good morning greeting image",
            "a good night greeting image",
            "a greeting card image with flowers and text",
            "a religious image with a quote",
            "a motivational quote on a background",
            "a birthday wishes image",
            "a viral image from social media",
        ),
        "screenshot" to listOf(
            "a screenshot of a phone screen",
            "a screenshot of a chat conversation",
            "a screenshot of a website",
        ),
        "photo" to listOf(
            "a photo of people",
            "a selfie",
            "a group photo of friends",
            "a photo of a family",
            "a photo of a child",
            "a photo of a pet",
            "a photo of a landscape",
            "a photo of a city",
            "a photo of a building",
            "a photo of food",
            "a photo at a party",
            "a photo of a concert",
            "a photo of an object",
            "a photo of a car",
            "a photo of nature",
            "a photo of the sea",
            "a photo of a sports event",
            "a photo of a room",
        ),
    )

    data class StoredPrompts(
        val version: Int,
        val model: String,
        val prompts: List<String>,
        val vectors: Array<FloatArray>,
    ) {
        private val promptToIndex: Map<String, Int> = prompts.mapIndexed { index, p -> p to index }.toMap()

        fun indexOf(prompt: String): Int =
            promptToIndex[prompt] ?: error("Unknown prompt: $prompt")
    }

    private val stored: StoredPrompts by lazy { load() }

    fun load(stream: InputStream = openResource()): StoredPrompts =
        parse(stream.bufferedReader().use { it.readText() })

    fun parse(jsonString: String): StoredPrompts {
        val json = JSONObject(jsonString)
        val version = json.getInt("version")
        check(version == VERSION) { "prompt_embeddings.json is stale: version is $version, expected $VERSION" }
        val model = json.getString("model")
        check(model == MODEL_ID) { "prompt_embeddings.json is stale: model is $model, expected $MODEL_ID" }

        val promptsObj = json.getJSONObject("prompts")
        val prompts = promptsObj.keys().asSequence().toList()
        val vectors = Array(prompts.size) { i ->
            decodeVector(promptsObj.getString(prompts[i]))
        }
        return StoredPrompts(version, model, prompts, vectors)
    }

    fun promptVector(prompt: String): FloatArray {
        val index = stored.indexOf(prompt)
        return stored.vectors[index]
    }

    /**
     * Probability of each class for one image embedding, with the class's best prompt.
     * Softmax over scaled cosine similarities with logit scale [LOGIT_SCALE], as in MobileCLIP zero-shot.
     */
    fun classify(embedding: FloatArray): Map<String, Pair<Float, String>> {
        require(embedding.size == DIMENSIONS) { "Expected embedding of size $DIMENSIONS, got ${embedding.size}" }
        val data = stored
        val logits = FloatArray(data.vectors.size)
        var maxLogit = Float.NEGATIVE_INFINITY
        for (i in data.vectors.indices) {
            val vec = data.vectors[i]
            var dot = 0f
            for (j in 0 until DIMENSIONS) {
                dot += embedding[j] * vec[j]
            }
            val logit = LOGIT_SCALE * dot
            logits[i] = logit
            if (logit > maxLogit) maxLogit = logit
        }

        val probabilities = FloatArray(logits.size)
        var sumExp = 0f
        for (i in logits.indices) {
            val p = exp(logits[i] - maxLogit)
            probabilities[i] = p
            sumExp += p
        }
        val invSum = 1f / sumExp
        for (i in probabilities.indices) {
            probabilities[i] *= invSum
        }

        return PROMPTS.mapValues { (_, classPrompts) ->
            var classProb = 0f
            var bestPrompt = classPrompts.first()
            var bestProb = -1f
            for (prompt in classPrompts) {
                val index = data.indexOf(prompt)
                val prob = probabilities[index]
                classProb += prob
                if (prob > bestProb) {
                    bestProb = prob
                    bestPrompt = prompt
                }
            }
            classProb to bestPrompt
        }
    }

    private fun openResource(): InputStream =
        javaClass.getResourceAsStream("/prompt_embeddings.json")
            ?: error("Resource /prompt_embeddings.json not found on classpath")

    private fun decodeVector(base64: String): FloatArray {
        val buffer = ByteBuffer.wrap(Base64.getDecoder().decode(base64)).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        return FloatArray(buffer.remaining()).also { buffer.get(it) }
    }
}

fun classify(embedding: FloatArray): Map<String, Pair<Float, String>> = Prompts.classify(embedding)
