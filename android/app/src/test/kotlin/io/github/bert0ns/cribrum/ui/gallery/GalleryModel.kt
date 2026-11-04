package io.github.bert0ns.cribrum.ui.gallery

import io.github.bert0ns.cribrum.compress.BatchResult
import io.github.bert0ns.cribrum.compress.BatchState
import io.github.bert0ns.cribrum.compress.CompressionLevel
import io.github.bert0ns.cribrum.model.ImageRef
import io.github.bert0ns.cribrum.ui.CompressBatches
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** A phone and a language, a theme and a size of text: one way to look at a scene. */
data class Variant(val language: String, val dark: Boolean, val device: String, val width: Int, val height: Int, val fontScale: Float = 1f) {
    val theme get() = if (dark) "dark" else "light"
    val qualifiers get() = "$language-w${width}dp-h${height}dp-${if (dark) "night" else "notnight"}-xhdpi"
    val suffix get() = "${language}_${theme}_$device"

    companion object {
        private fun phone(language: String, dark: Boolean = false, fontScale: Float = 1f, device: String = "phone") = Variant(language, dark, device, 411, 891, fontScale)

        val italian = phone("it")
        val english = phone("en")
        val italianDark = phone("it", dark = true)
        val italianLargeText = phone("it", fontScale = 1.5f, device = "phone-font15")
        val italianFold = Variant("it", false, "fold", 673, 841)

        /** Italian and English on a phone, in the light theme: every scene. */
        val base = listOf(italian, english)

        /** The base, the larger text and the unfolded Fold: the main screens. */
        val wide = base + italianLargeText + italianFold

        /** The wide set and the dark theme. */
        val full = wide + italianDark
    }
}

/**
 * One screen in one state. [run] drives the app to it, and the test takes the picture. [title] is the screen and state
 * in the index, [canDo] what the user can do from there.
 */
class Scene(val id: String, val title: String, val canDo: String, val variants: List<Variant>, val run: Gallery.() -> Unit)

/** A scene in one variant: one image. */
class Shot(val scene: Scene, val variant: Variant) {
    val fileName get() = "${scene.id}_${variant.suffix}.png"

    fun entry() = "$fileName\t${scene.title}\t${scene.canDo}\n"

    override fun toString() = fileName.removeSuffix(".png")
}

/** One line per image, the way index.md lists them. */
fun indexOf(entries: List<String>): String = buildString {
    appendLine("# Cribrum UI gallery")
    appendLine()
    appendLine("${entries.size} images. Each line gives the file, the screen and its state, and what the user can do from there.")
    appendLine()
    appendLine(NOTES)
    appendLine()
    for (entry in entries) {
        val (file, title, canDo) = entry.trimEnd('\n').split('\t')
        appendLine("- `$file`: $title. $canDo")
    }
}

/** Stands in for the background batch: it holds the state the scene wants and does nothing else. */
class GalleryBatches(initial: BatchState = BatchState.Idle) : CompressBatches {
    override val state: StateFlow<BatchState> = MutableStateFlow(initial)

    override fun start(paths: List<String>, level: CompressionLevel) = false

    override fun cancel() = Unit

    override suspend fun undo(result: BatchResult): List<ImageRef> = emptyList()
}
