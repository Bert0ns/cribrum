// STICKER: stickers saved by messaging apps (docs/design.md §5). Mirrors
// lab/src/cribrum_lab/detectors/sticker.py.
package io.github.bert0ns.cribrum.detectors

import io.github.bert0ns.cribrum.folders.isStickerFolder
import io.github.bert0ns.cribrum.model.Category
import io.github.bert0ns.cribrum.model.ImageRef
import io.github.bert0ns.cribrum.model.Signal

/** Nearly deterministic: WhatsApp keeps every sticker it receives in its sticker folders, and nothing else goes there. */
class StickerDetector : Detector {
    override val id = "sticker"
    override val version = 1

    override suspend fun analyze(image: ImageRef, context: AnalysisContext): List<Signal> {
        val inFolder = isStickerFolder(image.path)
        val evidence = mapOf("sticker_folder" to if (inFolder) "yes" else "no")
        return listOf(Signal(id, version, Category.STICKER, if (inFolder) 1f else 0f, evidence))
    }
}
