package io.github.bert0ns.cribrum.index

import io.github.bert0ns.cribrum.model.Category
import io.github.bert0ns.cribrum.model.GROUP_KEY
import io.github.bert0ns.cribrum.model.ImageRef
import io.github.bert0ns.cribrum.model.Kind
import io.github.bert0ns.cribrum.model.Protection
import io.github.bert0ns.cribrum.model.ROLE_KEY
import io.github.bert0ns.cribrum.model.Role
import io.github.bert0ns.cribrum.model.Signal
import java.util.Locale
import kotlin.random.Random

/** One image of the fixture and the signals each detector stored for it, keyed by detector id. */
class FixtureImage(val ref: ImageRef, val signals: Map<String, List<Signal>>)

/**
 * [count] images with evidence shaped like every detector's, with scores spread over the whole range.
 * Every third image is in an exact duplicate, a near-duplicate and a burst group of three.
 */
fun evidenceFixture(count: Int): List<FixtureImage> = List(count) { i ->
    val random = Random(i)
    fun score() = random.nextFloat()
    fun number(digits: Int) = "%.${digits}f".format(Locale.ROOT, random.nextDouble() * 100)
    val signals = mutableMapOf<String, List<Signal>>()
    fun put(id: String, kind: Kind, score: Float, evidence: Map<String, String>) {
        signals[id] = listOf(Signal(id, 1, kind, score, evidence))
    }
    put("blur", Category.BLURRY, score(), mapOf("global_variance" to number(1), "peak_variance" to number(1)))
    put(
        "exposure",
        Category.BAD_EXPOSURE,
        score(),
        mapOf("case" to "dark", "dark_score" to number(3), "bright_score" to number(3), "flat_score" to number(3)),
    )
    put(
        "screenshot",
        Category.SCREENSHOT,
        score(),
        mapOf("path_match" to "screenshots", "size" to "1080x2400", "screen_size" to "yes", "camera_exif" to "no"),
    )
    put(
        "forwarded",
        Category.FORWARDED,
        score(),
        mapOf("prompt" to "a forwarded image", "probability" to number(3), "messaging_folder" to "yes", "camera_exif" to "no"),
    )
    put("sticker", Category.STICKER, if (i % 50 == 0) 1f else 0f, mapOf("sticker_folder" to if (i % 50 == 0) "yes" else "no"))
    put("document", Category.DOCUMENT, score(), mapOf("prompt" to "a scanned page", "probability" to number(3), "text_coverage" to number(4)))
    if (i % 3 == 0) put("faces", Protection.FACES, score(), mapOf("faces" to "2", "largest_face" to number(4)))
    if (i % 3 == 0) {
        val group = i / 9 // three members each, so no group is a lone copy the review hides
        val role = if (group % 4 == 0 && i % 9 == 0) Role.BEST else Role.INFERIOR
        val roleScore = if (role == Role.BEST) 0f else score()
        val common = mapOf(GROUP_KEY to "g$group", ROLE_KEY to role.value)
        put("exact_duplicate", Category.EXACT_DUPLICATE, roleScore, common)
        put("near_duplicate", Category.NEAR_DUPLICATE, roleScore, common + mapOf("distance" to "4", "cosine" to number(3)))
        put("burst", Category.BURST, roleScore, common + mapOf("sharpness" to number(1), "shots" to "3"))
    }
    FixtureImage(ImageRef("/photos/${i % 100}/img_$i.jpg", 1000L + i, i.toLong()), signals)
}

/** Stores the fixture in the index. */
suspend fun ImageIndex.store(images: List<FixtureImage>) {
    recordAll(images.map { it.ref })
    val byDetector = images.flatMap { image -> image.signals.map { (id, signals) -> Triple(id, image.ref.path, signals) } }.groupBy { it.first }
    for ((id, rows) in byDetector) setSignalsOfMany(id, 1, rows.associate { it.second to it.third })
}
