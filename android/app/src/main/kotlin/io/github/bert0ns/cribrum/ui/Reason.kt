// Why an image is a candidate, as one short line per signal (docs/design.md §8). Built only from
// the evidence the detectors already store.
package io.github.bert0ns.cribrum.ui

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import io.github.bert0ns.cribrum.R
import io.github.bert0ns.cribrum.model.Category
import io.github.bert0ns.cribrum.model.ROLE_KEY
import io.github.bert0ns.cribrum.model.Role
import io.github.bert0ns.cribrum.model.Signal
import kotlin.math.roundToInt

/** A string resource and its arguments, so the line follows the locale and can be built off the composition. */
class Reason(@StringRes val id: Int, vararg val args: Any)

@Composable
fun Reason.text(): String = stringResource(id, *args)

/** The reason a signal gives, or null for a protection, which is never one. */
fun reasonOf(signal: Signal): Reason? {
    val evidence = signal.evidence
    val best = evidence[ROLE_KEY] == Role.BEST.value
    fun yes(key: String) = evidence[key] == "yes"
    return when (signal.kind) {
        Category.EXACT_DUPLICATE -> Reason(if (best) R.string.reason_exact_duplicate_best else R.string.reason_exact_duplicate)
        Category.NEAR_DUPLICATE -> Reason(if (best) R.string.reason_near_duplicate_best else R.string.reason_near_duplicate)
        Category.BURST -> evidence["shots"]?.toIntOrNull()?.let { Reason(if (best) R.string.reason_burst_best else R.string.reason_burst, it) }
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
            val percent = ((evidence["text_coverage"]?.toFloatOrNull() ?: 0f) * 100).roundToInt()
            if (percent >= 1) Reason(R.string.reason_document_text, percent) else Reason(R.string.reason_document)
        }
        Category.SCREENSHOT -> {
            val match = evidence["path_match"].orEmpty()
            when {
                match.isNotEmpty() -> Reason(R.string.reason_screenshot_name, match)
                yes("screen_size") -> Reason(R.string.reason_screenshot_size, evidence["size"].orEmpty().replace('x', '×'))
                else -> Reason(R.string.reason_screenshot_exif)
            }
        }
        Category.FORWARDED -> when {
            (evidence["probability"]?.toFloatOrNull() ?: 0f) >= 0.5f -> Reason(R.string.reason_forwarded_look)
            yes("messaging_folder") -> Reason(R.string.reason_forwarded_folder)
            else -> Reason(R.string.reason_forwarded_exif)
        }
        Category.STICKER -> Reason(R.string.reason_sticker)
        else -> null
    }
}
