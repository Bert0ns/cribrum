// Zero-shot classes and their MobileCLIP prompts (docs/design.md §5). Mirrors lab/src/cribrum_lab/prompts.py.
package io.github.bert0ns.cribrum.prompts

import io.github.bert0ns.cribrum.mobileclip.MODEL_ID
import io.github.bert0ns.cribrum.mobileclip.zeroShot
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64

const val VERSION = 1

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

private val embeddings: Pair<List<String>, List<FloatArray>> by lazy {
    val stream = object {}.javaClass.getResourceAsStream("/prompt_embeddings.json")
        ?: error("Resource /prompt_embeddings.json not found on classpath")
    parse(stream.bufferedReader().use { it.readText() })
}

/** Probability of each class for one image embedding, with the class's best prompt. */
fun classify(embedding: FloatArray): Map<String, Pair<Float, String>> {
    val (texts, vectors) = embeddings
    val probabilities = zeroShot(embedding, vectors)
    return PROMPTS.mapValues { (_, classPrompts) ->
        val indices = classPrompts.map { texts.indexOf(it) }
        val best = indices.maxBy { probabilities[it] }
        indices.fold(0f) { sum, i -> sum + probabilities[i] } to texts[best]
    }
}

internal fun parse(jsonString: String): Pair<List<String>, List<FloatArray>> {
    val json = JSONObject(jsonString)
    val version = json.getInt("version")
    val model = json.getString("model")
    check(version == VERSION && model == MODEL_ID) {
        "prompt_embeddings.json is stale: version $version and model $model, expected $VERSION and $MODEL_ID"
    }
    val prompts = json.getJSONObject("prompts")
    val texts = prompts.keys().asSequence().toList()
    return texts to texts.map { decodeVector(prompts.getString(it)) }
}

private fun decodeVector(base64: String): FloatArray {
    val buffer = ByteBuffer.wrap(Base64.getDecoder().decode(base64)).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
    return FloatArray(buffer.remaining()).also { buffer.get(it) }
}
