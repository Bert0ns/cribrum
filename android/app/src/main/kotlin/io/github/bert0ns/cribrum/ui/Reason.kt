// Why an image is a candidate, as one short line per signal (docs/design.md §8). Built only from
// the evidence the detectors already store.
package io.github.bert0ns.cribrum.ui

import android.content.res.Resources
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalResources
import io.github.bert0ns.cribrum.R
import io.github.bert0ns.cribrum.detectors.DocumentDetector
import io.github.bert0ns.cribrum.detectors.ForwardedDetector
import io.github.bert0ns.cribrum.model.Category
import io.github.bert0ns.cribrum.model.ROLE_KEY
import io.github.bert0ns.cribrum.model.Role
import io.github.bert0ns.cribrum.model.Signal
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * A string or plural resource and its arguments, so the line follows the locale and can be built
 * off the composition. With a [quantity] the resource is a plural and [args] include the number.
 */
class Reason(@StringRes @PluralsRes val id: Int, val quantity: Int? = null, vararg val args: Any) {
    fun text(resources: Resources): String = if (quantity == null) resources.getString(id, *args) else resources.getQuantityString(id, quantity, *args)
}

@Composable
fun Reason.text(): String = text(LocalResources.current)

/**
 * The reason a signal gives, or null for a protection, which is never one. A line never claims
 * more than the detector measured: where the evidence is missing or does not back a rule, the
 * line is the plain one of the category.
 */
fun reasonOf(signal: Signal): Reason? {
    val evidence = signal.evidence
    val best = evidence[ROLE_KEY] == Role.BEST.value
    fun number(key: String) = evidence[key]?.toFloatOrNull() ?: 0f
    fun yes(key: String) = evidence[key] == "yes"
    return when (signal.kind) {
        Category.EXACT_DUPLICATE -> Reason(if (best) R.string.reason_exact_duplicate_best else R.string.reason_exact_duplicate)
        Category.NEAR_DUPLICATE -> Reason(if (best) R.string.reason_near_duplicate_best else R.string.reason_near_duplicate)
        Category.BURST -> {
            val shots = evidence["shots"]?.toIntOrNull()
            when {
                shots == null -> Reason(if (best) R.string.reason_burst_best_plain else R.string.reason_burst_plain)
                else -> Reason(if (best) R.plurals.reason_burst_best else R.plurals.reason_burst, shots, shots)
            }
        }
        Category.BLURRY -> Reason(R.string.reason_blurry)
        Category.BAD_EXPOSURE -> Reason(
            when (evidence["case"]) {
                "dark" -> R.string.reason_dark
                "bright" -> R.string.reason_bright
                "flat" -> R.string.reason_flat
                else -> R.string.reason_exposure
            },
        )
        Category.DOCUMENT -> {
            val coverage = number("text_coverage")
            val textChance = DocumentDetector.TEXT_WEIGHT * min(1f, coverage / DocumentDetector.FULL_COVERAGE)
            val percent = (coverage * 100).roundToInt()
            when {
                textChance <= 0f || textChance < number("probability") -> Reason(R.string.reason_document)
                percent >= 1 -> Reason(R.string.reason_document_text, null, percent)
                else -> Reason(R.string.reason_document_little_text, null, 1)
            }
        }
        Category.SCREENSHOT -> {
            val match = evidence["path_match"].orEmpty()
            val size = evidence["size"].orEmpty().replace('x', '×')
            when {
                match.isNotEmpty() -> Reason(R.string.reason_screenshot_name, null, match)
                yes("screen_size") && size.isNotEmpty() -> Reason(R.string.reason_screenshot_size, null, size)
                yes("screen_size") -> Reason(R.string.reason_screenshot_size_plain)
                evidence["camera_exif"] == "no" -> Reason(R.string.reason_screenshot_exif)
                else -> Reason(R.string.reason_screenshot)
            }
        }
        Category.FORWARDED -> {
            // The rule that added the most to the score, with the detector's own weights.
            val contributions = listOf(
                R.string.reason_forwarded_look to ForwardedDetector.ZERO_SHOT_WEIGHT * number("probability"),
                R.string.reason_forwarded_folder to if (yes("messaging_folder")) ForwardedDetector.MESSAGING_FOLDER_WEIGHT else 0f,
                R.string.reason_forwarded_exif to if (evidence["camera_exif"] == "no") ForwardedDetector.NO_CAMERA_EXIF_WEIGHT else 0f,
            )
            val strongest = contributions.maxBy { it.second }
            Reason(if (strongest.second > 0f) strongest.first else R.string.reason_forwarded)
        }
        Category.STICKER -> Reason(R.string.reason_sticker)
        else -> null
    }
}
