// FORWARDED: memes, stickers and greeting images from messaging apps (docs/design.md §5).
// Mirrors lab/src/cribrum_lab/detectors/forwarded.py.
package io.github.bert0ns.cribrum.detectors

import io.github.bert0ns.cribrum.folders.isMessagingFolder
import io.github.bert0ns.cribrum.model.Category
import io.github.bert0ns.cribrum.model.ImageRef
import io.github.bert0ns.cribrum.model.Signal
import io.github.bert0ns.cribrum.prompts.Prompts
import java.util.Locale

class ForwardedDetector : Detector {
    override val id = "forwarded"
    override val version = 2
    override val category = Category.FORWARDED

    override suspend fun analyze(image: ImageRef, context: AnalysisContext): List<Signal> {
        val (probability, prompt) = Prompts.classify(context.embedding(image)).getValue("forwarded")
        val messaging = isMessagingFolder(image.path)
        val cameraExif = context.metadata(image).hasCameraExif
        val miss = (1f - ZERO_SHOT_WEIGHT * probability) *
            (1f - (if (messaging) MESSAGING_FOLDER_WEIGHT else 0f)) *
            (1f - (if (cameraExif) 0f else NO_CAMERA_EXIF_WEIGHT))
        val evidence = mapOf(
            "prompt" to prompt,
            "probability" to "%.3f".format(Locale.ROOT, probability),
            "messaging_folder" to if (messaging) "yes" else "no",
            "camera_exif" to if (cameraExif) "yes" else "no",
        )
        return listOf(Signal(id, version, Category.FORWARDED, 1f - miss, evidence))
    }

    companion object {
        const val ZERO_SHOT_WEIGHT = 0.9f
        const val MESSAGING_FOLDER_WEIGHT = 0.4f
        const val NO_CAMERA_EXIF_WEIGHT = 0.1f
    }
}
