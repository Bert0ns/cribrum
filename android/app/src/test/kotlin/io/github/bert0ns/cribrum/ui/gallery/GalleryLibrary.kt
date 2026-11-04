package io.github.bert0ns.cribrum.ui.gallery

import io.github.bert0ns.cribrum.model.Category
import io.github.bert0ns.cribrum.model.ImageRef
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

private val TODAY = LocalDate.of(2026, 11, 2)
private val STAMP = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")
private val SCREENSHOT_STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
private val MESSAGE_DAY = DateTimeFormatter.ofPattern("yyyyMMdd")

/** When the n-th picture of a set was taken: spread over [days] days, the newest first, at different hours. */
fun taken(index: Int, days: Int = 6): LocalDateTime = TODAY.minusDays((index % days + 1).toLong()).atTime(8 + (index * 5) % 12, (index * 7) % 60, (index * 13) % 60)

fun cameraName(at: LocalDateTime) = "IMG_${STAMP.format(at)}.jpg"

const val CAMERA = "DCIM/Camera"
const val SCREENSHOTS = "Pictures/Screenshots"
const val WHATSAPP = "WhatsApp/Media/WhatsApp Images"
const val STICKERS = "WhatsApp/Media/WhatsApp Stickers"
const val DOWNLOAD = "Download"

/** How many candidates each category lists on the home, as on a library of a few thousand photos. */
val COUNTS = mapOf(
    Category.EXACT_DUPLICATE to 42,
    Category.NEAR_DUPLICATE to 217,
    Category.BURST to 64,
    Category.BLURRY to 128,
    Category.BAD_EXPOSURE to 31,
    Category.SCREENSHOT to 305,
    Category.DOCUMENT to 89,
    Category.FORWARDED to 412,
    Category.STICKER to 340,
)

/**
 * Fills the index with a library: the counts of [COUNTS] as rows, and, for the categories in [pictures], drawn files that
 * the review shows first. [others] adds the photos that are no candidates, for the map and Settings.
 */
fun GalleryWorld.library(pictures: Set<Category> = emptySet(), others: Boolean = false) {
    fun drawn(category: Category) = category in pictures

    // Blurry photos: soft copies of scenes. With pictures, the first 100 are files, every fourth one in an Instagram folder.
    val blurry = COUNTS.getValue(Category.BLURRY)
    for (index in 0 until blurry) {
        val at = taken(index)
        val signal = signal(Category.BLURRY, if (drawn(Category.BLURRY) && index < 100) 0.99f - index * 0.004f else 0.5f - (index % 20) * 0.002f)
        if (drawn(Category.BLURRY) && index < 100) {
            val folder = if (index % 4 == 0) "Pictures/Instagram" else CAMERA
            picture(folder, cameraName(at), GalleryPictures.blurred(GalleryPictures.scene(1_000 + index)), 3_200_000L + index * 41_000L, at, signal)
        } else {
            row(CAMERA, cameraName(at.minusMinutes(index.toLong() + 60)), 3_000_000L + index * 17_000L, at, signal)
        }
    }

    // Near duplicates: 16 groups of three fill the first page, then pairs.
    val near = COUNTS.getValue(Category.NEAR_DUPLICATE)
    var candidates = 0
    var group = 0
    while (candidates < near) {
        val big = drawn(Category.NEAR_DUPLICATE) && group < 16
        val copies = if (big) 2 else 1
        val at = taken(group)
        val best: ImageRef
        val inferior: List<Pair<ImageRef, Float>>
        if (big) {
            val scene = 2_000 + group
            best = picture(CAMERA, cameraName(at), GalleryPictures.scene(scene), 4_600_000L, at)
            inferior = (1..copies).map { copy ->
                picture(CAMERA, cameraName(at.plusSeconds(copy * 2L)), GalleryPictures.scene(scene, shift = copy * 6f), 4_300_000L - copy * 90_000L, at.plusSeconds(copy * 2L)) to 0.97f - group * 0.01f - copy * 0.003f
            }
        } else {
            best = row(CAMERA, cameraName(at.plusMinutes(group + 400L)), 4_500_000L, at)
            inferior = listOf(row(CAMERA, cameraName(at.plusMinutes(group + 400L).plusSeconds(3)), 4_400_000L, at) to 0.7f - (group % 20) * 0.01f)
        }
        group("near-$group", Category.NEAR_DUPLICATE, best, inferior)
        candidates += copies
        group++
    }

    // Exact copies and bursts: rows only.
    for (index in 0 until COUNTS.getValue(Category.EXACT_DUPLICATE)) {
        val at = taken(index + 10)
        val best = row(CAMERA, cameraName(at.plusHours(3)), 3_900_000L, at)
        group("exact-$index", Category.EXACT_DUPLICATE, best, listOf(row(DOWNLOAD, "IMG_${STAMP.format(at.plusHours(3))}(1).jpg", 3_900_000L, at) to 1f))
    }
    val shots = 5
    for (index in 0 until COUNTS.getValue(Category.BURST) / (shots - 1)) {
        val at = taken(index + 3)
        val best = row("$CAMERA/Burst", "BURST_${STAMP.format(at)}_COVER.jpg", 3_100_000L, at)
        group("burst-$index", Category.BURST, best, (1 until shots).map { shot -> row("$CAMERA/Burst", "BURST_${STAMP.format(at)}_$shot.jpg", 3_000_000L, at) to 0.8f }, "shots" to shots.toString())
    }

    // The rest of the categories: screenshots (some drawn), exposure, documents, forwarded pictures and stickers.
    for (index in 0 until COUNTS.getValue(Category.SCREENSHOT)) {
        val at = taken(index, 9)
        val name = "Screenshot_${SCREENSHOT_STAMP.format(at)}.png"
        val signal = signal(Category.SCREENSHOT, 0.97f - index * 0.002f, "path_match" to "Screenshot", "size" to "1080x2340", "screen_size" to "yes")
        if (drawn(Category.SCREENSHOT) && index < 48) {
            picture(SCREENSHOTS, name, GalleryPictures.screenshot(3_000 + index), 1_900_000L + index * 23_000L, at, signal)
        } else {
            row(SCREENSHOTS, name, 1_700_000L + index * 3_000L, at, signal)
        }
    }
    for (index in 0 until COUNTS.getValue(Category.BAD_EXPOSURE)) {
        val at = taken(index, 8)
        val dark = index % 3 != 0
        val signal = signal(Category.BAD_EXPOSURE, 0.95f - index * 0.01f, "case" to if (dark) "dark" else "bright")
        if (drawn(Category.BAD_EXPOSURE)) {
            val ref = picture(CAMERA, cameraName(at.plusHours(2)), GalleryPictures.exposure(4_000 + index, dark), 2_400_000L, at, signal)
            if (index == 4 || index == 13) favorites += ref.path
        } else {
            row(CAMERA, cameraName(at.plusHours(2)), 2_400_000L, at, signal)
        }
    }
    for (index in 0 until COUNTS.getValue(Category.DOCUMENT)) {
        val at = taken(index, 20)
        row(DOWNLOAD, "scan_${STAMP.format(at)}.jpg", 3_000_000L, at, signal(Category.DOCUMENT, 0.9f - index * 0.005f, "text_coverage" to "0.${20 + index % 40}", "probability" to "0.4"))
    }
    for (index in 0 until COUNTS.getValue(Category.FORWARDED)) {
        val at = taken(index, 30)
        row(WHATSAPP, "IMG-${MESSAGE_DAY.format(at)}-WA${(1000 + index).toString().padStart(4, '0')}.jpg", 850_000L, at, signal(Category.FORWARDED, 0.8f - (index % 30) * 0.004f, "messaging_folder" to "yes", "probability" to "0.6"))
    }
    for (index in 0 until COUNTS.getValue(Category.STICKER)) {
        val at = taken(index, 60)
        row(STICKERS, "${(500_000 + index * 37)}-$index.webp", 120_000L, at, signal(Category.STICKER, 1f))
    }

    if (others) {
        repeat(520) { index -> row(CAMERA, cameraName(taken(index, 90).minusHours(5)), 4_400_000L + index * 1_000L, taken(index, 90)) }
        repeat(70) { index -> row("Pictures/Family/Holidays 2026", "IMG_${index}_holiday.jpg", 3_800_000L, taken(index, 20)) }
        repeat(40) { index -> row("Pictures/Family/Birthday", "IMG_${index}_party.jpg", 3_500_000L, taken(index, 20)) }
        repeat(90) { index -> row("Pictures/Instagram", "IMG_$index.jpg", 900_000L, taken(index, 40)) }
        repeat(60) { index -> row("Telegram/Telegram Images", "photo_$index.jpg", 700_000L, taken(index, 40)) }
    }
}
