// SCREENSHOT: path and file name, screen-sized image, no camera EXIF (docs/design.md §5).
// Mirrors lab/src/cribrum_lab/detectors/screenshot.py.
package io.github.bert0ns.cribrum.detectors

import io.github.bert0ns.cribrum.model.Category
import io.github.bert0ns.cribrum.model.ImageRef
import io.github.bert0ns.cribrum.model.Signal
import java.io.File

/**
 * Each rule is a separate piece of evidence. The score combines them as independent chances
 * (noisy OR), so any rule alone can make an image a candidate and each extra rule raises the
 * score. The weights are starting points, to be tuned for recall in the lab.
 */
class ScreenshotDetector : Detector {
    override val id = "screenshot"
    override val version = 1
    override val category = Category.SCREENSHOT

    override suspend fun analyze(image: ImageRef, context: AnalysisContext): List<Signal> {
        val meta = context.metadata(image)
        val pathMatch = pathParts(image.path).firstNotNullOfOrNull { PATTERN.find(it)?.value }.orEmpty()
        val isScreenSize = (minOf(meta.width, meta.height) to maxOf(meta.width, meta.height)) in context.screenSizes
        val weights = listOf(
            if (pathMatch.isNotEmpty()) PATH_WEIGHT else 0f,
            if (isScreenSize) SCREEN_SIZE_WEIGHT else 0f,
            if (meta.hasCameraExif) 0f else NO_CAMERA_EXIF_WEIGHT,
        )
        val miss = weights.fold(1f) { acc, weight -> acc * (1f - weight) }
        val evidence = mapOf(
            "path_match" to pathMatch,
            "size" to "${meta.width}x${meta.height}",
            "screen_size" to if (isScreenSize) "yes" else "no",
            "camera_exif" to if (meta.hasCameraExif) "yes" else "no",
        )
        return listOf(Signal(id, version, Category.SCREENSHOT, 1f - miss, evidence))
    }

    companion object {
        const val PATH_WEIGHT = 0.9f
        const val SCREEN_SIZE_WEIGHT = 0.6f

        /** Weak: forwarded and downloaded images have no camera EXIF either. */
        const val NO_CAMERA_EXIF_WEIGHT = 0.3f

        private val PATTERN = Regex(
            "screen[ _-]?shot|screencap|schermata|bildschirmfoto|capture d.[ée]cran" +
                "|captura de (?:pantalla|tela)|скриншот|снимок экрана|截屏|截图|スクリーンショット",
            RegexOption.IGNORE_CASE,
        )

        /** File name and its two nearest folders: higher folders say nothing about the image. */
        private const val PATH_PARTS = 3

        private fun pathParts(path: String): List<String> = generateSequence(File(path)) { it.parentFile }.map { it.name }.take(PATH_PARTS).toList().reversed()
    }
}
