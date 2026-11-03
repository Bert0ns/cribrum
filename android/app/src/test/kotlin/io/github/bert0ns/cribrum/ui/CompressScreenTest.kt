package io.github.bert0ns.cribrum.ui

import android.text.format.Formatter
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.click
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pinch
import androidx.compose.ui.test.swipe
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import io.github.bert0ns.cribrum.CribrumApp
import io.github.bert0ns.cribrum.compress.BatchResult
import io.github.bert0ns.cribrum.compress.BatchState
import io.github.bert0ns.cribrum.compress.CompressibleImage
import io.github.bert0ns.cribrum.compress.CompressionLevel
import io.github.bert0ns.cribrum.compress.JpegInfo
import io.github.bert0ns.cribrum.compress.JpegStatus
import io.github.bert0ns.cribrum.compress.Losses
import io.github.bert0ns.cribrum.compress.Marker
import io.github.bert0ns.cribrum.compress.Refusal
import io.github.bert0ns.cribrum.model.ImageRef
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** Keeps what the screen asks of the batch job. */
class RecordingBatches(initial: BatchState = BatchState.Idle) : CompressBatches {
    val mutableState = MutableStateFlow(initial)
    override val state: StateFlow<BatchState> = mutableState
    val started = mutableListOf<Pair<List<String>, CompressionLevel>>()
    var cancels = 0
    val undone = mutableListOf<BatchResult>()
    var stayed: List<ImageRef> = emptyList()

    /** Holds an undo until the test lets it go, as a running scan does. */
    var undoGate: CompletableDeferred<Unit>? = null

    override fun start(paths: List<String>, level: CompressionLevel): Boolean {
        started += paths to level
        return true
    }

    override fun cancel() {
        cancels++
    }

    override suspend fun undo(result: BatchResult): List<ImageRef> {
        undone += result
        undoGate?.await()
        mutableState.value = BatchState.Idle
        return stayed
    }
}

private fun jpeg(path: String, size: Long, width: Int = 4000, height: Int = 3000, info: JpegInfo = JpegInfo(JpegStatus.ELIGIBLE, quality = 92)) = CompressibleImage(path, size, width, height, info)

private const val COMPARE = "Original on the left, compressed at the chosen level on the right"
private const val SLIDER = "Comparison line. The original is on its left, the compressed copy on its right."

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w360dp-h740dp")
class CompressScreenTest {
    @get:Rule
    val compose = createComposeRule()

    @get:Rule
    val folder = TemporaryFolder()

    private val app = ApplicationProvider.getApplicationContext<CribrumApp>()
    private val big = jpeg("/sdcard/DCIM/IMG_1.jpg", 24_000_000, 16320, 12240, JpegInfo(JpegStatus.ELIGIBLE, quality = 94, losses = Losses(reEdit = true, xmp = true)))
    private val middle = jpeg("/sdcard/DCIM/IMG_2.jpg", 6_200_000)
    private val before = jpeg("/sdcard/DCIM/IMG_3.jpg", 4_100_000, info = JpegInfo(JpegStatus.ELIGIBLE, quality = 85, marker = Marker(1, "balanced", "2026-11-01T10:00:00Z")))
    private val batches = RecordingBatches()
    private val haptics = RecordingHaptics()
    private val previewFile by lazy { File(folder.root, "preview.jpg").apply { writeBytes(ByteArray(1500)) } }
    private val refusals = mutableMapOf<String, Refusal>()

    private fun model(vararg photos: CompressibleImage, favorites: Set<String> = emptySet()) = CompressViewModel(
        app,
        batches,
        photos = { photos.toList() },
        favorites = { favorites },
        retentionDays = { 30 },
        preview = { original, _, out ->
            previewFile.copyTo(out, overwrite = true)
            refusals[original.name]
        },
        previewFiles = List(MAX_COMPARED) { File(folder.root, "out$it.jpg") },
    )

    private fun show(model: CompressViewModel) {
        compose.setContent { CompositionLocalProvider(LocalHapticFeedback provides haptics) { CompressScreen(model, scanning = false) } }
    }

    private fun awaitText(text: String, substring: Boolean = false) = compose.waitUntil(5_000) {
        compose.onAllNodesWithText(text, substring = substring).fetchSemanticsNodes().isNotEmpty()
    }

    private fun size(bytes: Long) = Formatter.formatShortFileSize(app, bytes)

    private fun tile(name: String) = compose.onNodeWithContentDescription(name, substring = true)

    @Test
    fun `nothing is preselected, and the photos come largest first with their size and resolution`() {
        show(model(big, middle))
        awaitText("2 photos, largest first")

        tile("IMG_1.jpg").assertIsNotSelected()
        tile("IMG_2.jpg").assertIsNotSelected()
        for (level in listOf("High quality", "Balanced", "Smallest")) compose.onNodeWithText(level).assertIsNotSelected()
        compose.onNodeWithText("Continue with 0 photos").assertIsNotEnabled()
        compose.onNodeWithText("Select the photos to compress.").assertExists()
        // The index gives them largest first, and the page keeps that order.
        assertTrue(tile("IMG_1.jpg").fetchSemanticsNode().boundsInRoot.left < tile("IMG_2.jpg").fetchSemanticsNode().boundsInRoot.left)
        compose.onNodeWithContentDescription("IMG_1.jpg, 16320 × 12240 pixels, ${size(24_000_000)}. Not selected").assertExists()
        compose.onNodeWithContentDescription("IMG_2.jpg, 4000 × 3000 pixels, ${size(6_200_000)}. Not selected").assertExists()
    }

    @Test
    fun `the levels say their exact numbers`() {
        show(model(big))
        awaitText("1 photo, largest first")

        compose.onNodeWithText("JPEG 90, full resolution").assertExists()
        compose.onNodeWithText("JPEG 85, up to 50 MP").assertExists()
        compose.onNodeWithText("JPEG 80, up to 12 MP").assertExists()
    }

    @Test
    fun `continuing needs photos and a level, and the levels show their saving once photos are selected`() {
        show(model(big, middle))
        awaitText("2 photos, largest first")
        compose.onAllNodesWithText("Saves about", substring = true).assertCountEquals(0)

        tile("IMG_1.jpg").performClick()
        compose.onNodeWithText("Choose a level.").assertExists()
        compose.onNodeWithText("Continue with 1 photo").assertIsNotEnabled()
        compose.onAllNodesWithText("Saves about", substring = true).assertCountEquals(3)
        compose.onNodeWithText("Balanced").performClick()

        compose.onNodeWithText("Balanced").assertIsSelected()
        compose.onNodeWithText("Continue with 1 photo").assertIsEnabled()
    }

    @Test
    fun `select all picks the photos not compressed yet and leaves the compressed ones`() {
        show(model(big, middle, before))
        awaitText("2 photos, largest first")

        compose.onNodeWithText("Select all on this page").performClick()

        tile("IMG_1.jpg").assertIsSelected()
        tile("IMG_2.jpg").assertIsSelected()
        compose.onNodeWithText("Deselect all on this page").assertExists()
        compose.onNodeWithText("Already compressed (1)").performClick()
        tile("IMG_3.jpg").assertIsNotSelected()
    }

    @Test
    fun `the compressed photos are collapsed, and show the level from the marker`() {
        show(model(big, before))
        awaitText("Already compressed (1)")
        compose.onAllNodesWithText("You already compressed these images once.", substring = true).assertCountEquals(0)
        compose.onAllNodesWithContentDescription("IMG_3.jpg").assertCountEquals(0)

        compose.onNodeWithText("Already compressed (1)").performClick()

        compose.onNodeWithText("You already compressed these images once. If you want, you can compress them again.").assertExists()
        tile("IMG_3.jpg").assertExists()
        compose.onNodeWithContentDescription("IMG_3.jpg, 4000 × 3000 pixels, ${size(4_100_000)}. Compressed before at Balanced. Not selected").assertExists()
    }

    @Test
    fun `the supported formats line opens a sheet that says what is not supported and why`() {
        compose.setContent { CompressContent(CompressState(loaded = true), BatchState.Idle, hdrSupported = true, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}) }
        compose.onNodeWithText("Supported formats: JPEG. Motion Photos are not compressed.").assertExists()
        compose.onAllNodesWithText("HDR photos are not compressed.", substring = true).assertCountEquals(0)

        compose.onNodeWithText("Supported formats", substring = true).performClick()

        compose.onNodeWithText("Motion Photos. The video inside them would be lost.").assertExists()
        compose.onNodeWithText("JPEGs with other data after the image", substring = true).assertExists()
    }

    @Test
    fun `below Android 14 the line also says HDR photos are not compressed`() {
        compose.setContent { CompressContent(CompressState(loaded = true), BatchState.Idle, hdrSupported = false, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}) }

        compose.onNodeWithText("Supported formats: JPEG. Motion Photos are not compressed. HDR photos are not compressed.").assertExists()
    }

    @Test
    fun `the summary names the loss, counts the photos that have it, and shows a real before and after`() {
        show(model(big, middle))
        awaitText("2 photos, largest first")
        compose.onNodeWithText("Select all on this page").performClick()
        compose.onNodeWithText("Smallest").performClick()

        compose.onNodeWithText("Continue with 2 photos").performClick()

        awaitText("Compress 2 photos?")
        compose.onNodeWithText("The date, the place and the camera details are kept. The compressed copy loses:").assertExists()
        compose.onNodeWithText("• Samsung Gallery's Revert, for 1 edited photo").assertExists()
        compose.onNodeWithText("• The edits, ratings, keywords, captions and people tags that other apps saved, in 1 photo").assertExists()
        compose.onAllNodesWithText("Re-blurring", substring = true).assertCountEquals(0)
        compose.onAllNodesWithText("Content credentials", substring = true).assertCountEquals(0)
        compose.onNodeWithText("• The shooting mode info").assertExists()
        compose.onNodeWithText("All of it comes back only by restoring the original from the Cribrum trash, within 30 days.").assertExists()
        compose.onNodeWithText("Smallest: JPEG 80, up to 12 MP").assertExists()
        compose.onNodeWithText("Estimated saving: about", substring = true).assertExists()
        // The largest selected photo, compressed for real: the preview is its size 24 MB / 1.5 kB and the level's resolution.
        awaitText("Compressed • ${size(1500)} • 4000 × 3000")
        compose.onNodeWithText("Original • ${size(24_000_000)} • 16320 × 12240").assertExists()
    }

    @Test
    fun `a photo that cannot be compressed has no preview but the summary stays`() {
        refusals["IMG_1.jpg"] = Refusal.NOT_SMALLER
        show(model(big))
        awaitText("1 photo, largest first")
        tile("IMG_1.jpg").performClick()
        compose.onNodeWithText("High quality").performClick()

        compose.onNodeWithText("Continue with 1 photo").performClick()

        awaitText("This photo cannot be compressed at this level, so there is no preview. It would be left as it is.")
        compose.onNodeWithText("Compress 1 photo").assertIsEnabled()
    }

    @Test
    fun `the preview is one image under a line, and a pinch zooms both layers`() {
        show(model(big))
        awaitText("1 photo, largest first")
        tile("IMG_1.jpg").performClick()
        compose.onNodeWithText("Balanced").performClick()
        compose.onNodeWithText("Continue with 1 photo").performClick()
        awaitText("Compressed •", substring = true)
        compose.onAllNodesWithContentDescription(COMPARE).assertCountEquals(1)
        fun zoom() = compose.onNodeWithContentDescription(COMPARE).fetchSemanticsNode().config.getOrNull(SemanticsProperties.StateDescription)
        assertEquals("Zoom: 2% of full resolution", zoom())

        compose.onNodeWithContentDescription(COMPARE).performTouchInput {
            pinch(Offset(center.x - 10, center.y), Offset(center.x + 10, center.y), Offset(center.x - 60, center.y), Offset(center.x + 60, center.y))
        }

        assertNotEquals("Zoom: 2% of full resolution", zoom())
    }

    private fun tab(description: String) = compose.onNodeWithContentDescription(description)

    @Test
    fun `up to three photos get a tab each, with their sizes read out, and the first shows while the others come`() {
        val third = jpeg("/sdcard/DCIM/IMG_4.jpg", 4_100_000)
        show(model(big, middle, third))
        awaitText("3 photos, largest first")
        compose.onNodeWithText("Select all on this page").performClick()
        compose.onNodeWithText("Balanced").performClick()
        compose.onNodeWithText("Continue with 3 photos").performClick()

        awaitText("Compressed • ${size(1500)} • 8160 × 6120")
        compose.onNodeWithText("Original • ${size(24_000_000)} • 16320 × 12240").assertExists()
        compose.onNodeWithText("Before and after").assertExists()
        for ((index, bytes) in listOf(24_000_000L, 6_200_000L, 4_100_000L).withIndex()) {
            compose.waitUntil(5_000) { compose.onAllNodesWithContentDescription("Photo ${index + 1} of 3: ${size(bytes)} before, ${size(1500)} after").fetchSemanticsNodes().isNotEmpty() }
        }

        tab("Photo 2 of 3: ${size(6_200_000)} before, ${size(1500)} after").performClick()

        compose.onNodeWithText("Original • ${size(6_200_000)} • 4000 × 3000").assertExists()
        compose.onAllNodesWithText("Original • ${size(24_000_000)}", substring = true).assertCountEquals(0)
        // The photo on screen is the only one with layers, so the others hold no tiles.
        compose.onAllNodesWithContentDescription(COMPARE).assertCountEquals(1)
    }

    @Test
    fun `a single photo has no tabs`() {
        show(model(big))
        awaitText("1 photo, largest first")
        tile("IMG_1.jpg").performClick()
        compose.onNodeWithText("Balanced").performClick()
        compose.onNodeWithText("Continue with 1 photo").performClick()
        awaitText("Compressed •", substring = true)

        compose.onAllNodesWithText("Photo 1").assertCountEquals(0)
        compose.onAllNodesWithContentDescription("Photo 1 of 1", substring = true).assertCountEquals(0)
    }

    @Test
    fun `more than three photos compare the largest, the middle one and the smallest, and say so`() {
        val photos = many(5)
        show(model(*photos.toTypedArray()))
        awaitText("5 photos, largest first")
        compose.onNodeWithText("Select all on this page").performClick()
        compose.onNodeWithText("Balanced").performClick()
        compose.onNodeWithText("Continue with 5 photos").performClick()

        awaitText("Before and after, on the largest, the middle one and the smallest selected photo")
        for (index in 1..3) compose.waitUntil(5_000) { compose.onAllNodesWithContentDescription("Photo $index of 3", substring = true).fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodesWithContentDescription("Photo 4", substring = true).assertCountEquals(0)
        tab("Photo 3 of 3: ${size(photos[4].size)} before, ${size(1500)} after").performClick()
        compose.onNodeWithText("Original • ${size(photos[4].size)} • 4000 × 3000").assertExists()
    }

    @Test
    fun `a tab says when its photo is refused, and the others keep their preview`() {
        refusals["IMG_2.jpg"] = Refusal.NOT_SMALLER
        show(model(big, middle))
        awaitText("2 photos, largest first")
        compose.onNodeWithText("Select all on this page").performClick()
        compose.onNodeWithText("Smallest").performClick()
        compose.onNodeWithText("Continue with 2 photos").performClick()

        compose.waitUntil(5_000) { compose.onAllNodesWithContentDescription("Photo 2 of 2: ${size(6_200_000)} before, cannot be compressed at this level").fetchSemanticsNodes().isNotEmpty() }
        tab("Photo 1 of 2: ${size(24_000_000)} before, ${size(1500)} after").assertExists()
        tab("Photo 2 of 2: ${size(6_200_000)} before, cannot be compressed at this level").performClick()
        awaitText("This photo cannot be compressed at this level, so there is no preview. It would be left as it is.")
        compose.onNodeWithText("Compress 2 photos").assertIsEnabled()
    }

    private val line = mutableFloatStateOf(0.5f)

    private fun zoomPanel(zoom: Zoom) = compose.setContent {
        CompositionLocalProvider(LocalHapticFeedback provides haptics) {
            Box(Modifier.size(200.dp)) { CompareSlider(File(folder.root, "none.jpg"), File(folder.root, "none2.jpg"), zoom, line, 1f, true) }
        }
    }

    private fun handle() = compose.onNodeWithContentDescription(SLIDER)

    private fun pinchOut() = compose.onNodeWithContentDescription(COMPARE).performTouchInput {
        pinch(Offset(center.x - 10, center.y), Offset(center.x + 10, center.y), Offset(center.x - 40, center.y), Offset(center.x + 40, center.y))
    }

    @Test
    fun `the zoom and its position stay after the fingers lift`() {
        val zoom = Zoom(IntSize(4000, 3000))
        zoomPanel(zoom)

        pinchOut()

        assertTrue(zoom.scale > 1f)
        assertTrue("the offset is ${zoom.offset}", zoom.offset.x.isFinite() && zoom.offset.y.isFinite())
        assertTrue(zoom.offset.x < 0f)
    }

    @Test
    fun `one finger pans while zoomed, and a double tap goes back to the whole photo`() {
        val zoom = Zoom(IntSize(4000, 3000))
        zoomPanel(zoom)
        pinchOut()
        val zoomed = zoom.offset

        compose.onNodeWithContentDescription(COMPARE).performTouchInput { swipe(Offset(150f, 50f), Offset(110f, 50f)) }

        assertTrue("panned from $zoomed to ${zoom.offset}", zoom.offset.x < zoomed.x)
        assertEquals(0.5f, line.floatValue)
        compose.onNodeWithContentDescription(COMPARE).performTouchInput { doubleClick(Offset(150f, 50f)) }
        assertEquals(1f, zoom.scale)
        assertEquals(Offset.Zero, zoom.offset)
    }

    @Test
    fun `the handle moves the line, and a zoomed photo behind it does not pan`() {
        val zoom = Zoom(IntSize(4000, 3000))
        zoomPanel(zoom)
        pinchOut()
        val zoomed = zoom.offset

        val width = compose.onNodeWithContentDescription(COMPARE).fetchSemanticsNode().size.width
        handle().performTouchInput { swipe(center, Offset(center.x + 0.3f * width, center.y)) }

        assertEquals(0.8f, line.floatValue, 0.01f)
        assertEquals(zoomed, zoom.offset)
        assertTrue(zoom.scale > 1f)
    }

    @Test
    fun `the line gives a light tick on reaching either edge, once`() {
        zoomPanel(Zoom(IntSize(4000, 3000)))

        handle().performTouchInput { swipe(center, Offset(center.x + 150, center.y)) }
        assertEquals(1f, line.floatValue)
        assertEquals(listOf(HapticFeedbackType.SegmentTick), haptics.types)

        handle().performTouchInput { swipe(center, Offset(center.x + 50, center.y)) }
        assertEquals(1, haptics.types.size)

        handle().performTouchInput { swipe(center, Offset(center.x - 300, center.y)) }
        assertEquals(0f, line.floatValue)
        assertEquals(2, haptics.types.size)
    }

    @Test
    fun `a screen reader hears which side is which and moves the line with increment and decrement`() {
        zoomPanel(Zoom(IntSize(4000, 3000)))
        val config = handle().fetchSemanticsNode().config

        assertEquals("Comparison line. The original is on its left, the compressed copy on its right.", config.getOrNull(SemanticsProperties.ContentDescription)?.single())
        assertEquals(0.5f, config[SemanticsProperties.ProgressBarRangeInfo].current)
        assertEquals(0f..1f, config[SemanticsProperties.ProgressBarRangeInfo].range)
        val set = config[SemanticsActions.SetProgress].action!!
        compose.runOnUiThread { set(0.6f) }
        assertEquals(0.6f, line.floatValue)
        assertEquals(0.6f, handle().fetchSemanticsNode().config[SemanticsProperties.ProgressBarRangeInfo].current)
        assertTrue(haptics.types.isEmpty())
        compose.runOnUiThread { set(2f) }
        assertEquals(1f, line.floatValue)
        assertEquals(listOf(HapticFeedbackType.SegmentTick), haptics.types)
    }

    @Test
    fun `the handle is a target of 48 dp`() {
        zoomPanel(Zoom(IntSize(4000, 3000)))

        val bounds = handle().fetchSemanticsNode().boundsInRoot
        assertTrue("${bounds.width} x ${bounds.height}", bounds.width >= 47.5f && bounds.height >= 47.5f)
    }

    private fun many(count: Int, prefix: String = "IMG", info: JpegInfo = JpegInfo(JpegStatus.ELIGIBLE, quality = 92)) = List(count) { jpeg("/sdcard/DCIM/${prefix}_${"%03d".format(it)}.jpg", 100_000_000L - it, info = info) }

    private fun actionsOf(name: String) = tile(name).fetchSemanticsNode().config.getOrNull(SemanticsActions.CustomActions).orEmpty().map { it.label }

    @Test
    fun `a long press opens the photo full screen, where a button selects it and a swipe pages`() {
        show(model(big, middle))
        awaitText("2 photos, largest first")
        assertTrue("Open full size" in actionsOf("IMG_1.jpg"))

        tile("IMG_1.jpg").performTouchInput { longClick() }

        compose.onNodeWithTag("fullSize").assertExists()
        compose.onNodeWithText(big.path).assertExists()
        compose.onNodeWithText("Not selected").performClick()
        compose.onNodeWithText("Selected").assertExists()
        compose.onNodeWithTag("fullSize").performTouchInput { swipeLeft() }
        compose.onNodeWithText(middle.path).assertExists()
        compose.onNodeWithTag("fullSize").performTouchInput { click() }
        compose.mainClock.advanceTimeBy(1_000) // a tap waits to tell itself from a double tap, which resets the zoom
        compose.onNodeWithTag("fullSize").assertDoesNotExist()
        tile("IMG_1.jpg").assertIsSelected()
        tile("IMG_2.jpg").assertIsNotSelected()
    }

    @Test
    fun `a screen reader opens the photo full screen with an action`() {
        show(model(big))
        awaitText("1 photo, largest first")

        tile("IMG_1.jpg").fetchSemanticsNode().config.getOrNull(SemanticsActions.CustomActions)!!.first { it.label == "Open full size" }.action()
        compose.waitForIdle()

        compose.onNodeWithTag("fullSize").assertExists()
    }

    @Test
    fun `the list shows 50 photos at a time with its position, and Select all takes the current page only`() {
        val all = many(120)
        show(model(*all.toTypedArray()))
        awaitText("120 photos, largest first")

        compose.onNodeWithText("1 to 50 of 120").assertExists()
        tile("IMG_000.jpg").assertExists()
        compose.onNodeWithText("Select all on this page").performClick()
        compose.onNodeWithText("Continue with 50 photos").assertExists()

        compose.onAllNodesWithContentDescription("Next page")[0].performClick()
        compose.onNodeWithText("51 to 100 of 120").assertExists()
        tile("IMG_050.jpg").assertIsNotSelected()
        compose.onNodeWithText("Select all on this page").performClick()
        compose.onNodeWithText("Continue with 100 photos").assertExists()

        compose.onAllNodesWithContentDescription("Previous page")[0].performClick()
        tile("IMG_000.jpg").assertIsSelected()
        compose.onNodeWithText("Deselect all on this page").performClick()
        compose.onNodeWithText("Continue with 50 photos").assertExists()
    }

    @Test
    fun `the first page has no previous, the last has no next, and one page has no controls`() {
        show(model(*many(120).toTypedArray()))
        awaitText("120 photos, largest first")
        compose.onAllNodesWithContentDescription("Previous page")[0].assertIsNotEnabled()
        compose.onAllNodesWithContentDescription("Next page")[0].assertIsEnabled()

        compose.onAllNodesWithContentDescription("Next page")[0].performClick()
        compose.onAllNodesWithContentDescription("Next page")[0].performClick()

        compose.onNodeWithText("101 to 120 of 120").assertExists()
        compose.onAllNodesWithContentDescription("Next page")[0].assertIsNotEnabled()
    }

    @Test
    fun `fewer than 51 photos show no page controls`() {
        show(model(big, middle))
        awaitText("2 photos, largest first")

        compose.onAllNodesWithContentDescription("Next page").assertCountEquals(0)
    }

    @Test
    fun `the already compressed photos page too`() {
        val done = many(70, "OLD", before.info)
        show(model(big, *done.toTypedArray()))
        awaitText("Already compressed (70)")
        compose.onNodeWithText("Already compressed (70)").performClick()

        compose.onNodeWithText("1 to 50 of 70").assertExists()
        compose.onAllNodesWithContentDescription("Next page")[0].performClick()

        compose.onNodeWithText("51 to 70 of 70").assertExists()
        tile("OLD_050.jpg").assertExists()
    }

    @Test
    fun `a held image opens no decoder until it is let go`() {
        var tiled by mutableStateOf(false)
        var opened = 0
        compose.setContent {
            TiledImage(File(folder.root, "none.jpg"), remember { Zoom(IntSize(4000, 3000)) }, Modifier.size(100.dp), tiled) {
                opened++
                TiledImageTest.FakeDecoder()
            }
        }
        compose.waitForIdle()
        assertEquals(0, opened)

        tiled = true
        compose.waitForIdle()

        compose.waitUntil { opened == 1 }
    }

    @Test
    fun `one finger on a photo that is not zoomed leaves the drag to the page`() {
        val zoom = Zoom(IntSize(4000, 3000))
        zoomPanel(zoom)

        compose.onNodeWithContentDescription(COMPARE).performTouchInput { swipe(Offset(150f, 50f), Offset(110f, 50f)) }

        assertEquals(1f, zoom.scale)
        assertEquals(Offset.Zero, zoom.offset)
    }

    @Test
    fun `confirming starts the batch, largest first, with a strong haptic and an empty selection`() {
        val model = model(big, middle)
        show(model)
        awaitText("2 photos, largest first")
        compose.onNodeWithText("Select all on this page").performClick()
        compose.onNodeWithText("Balanced").performClick()
        compose.onNodeWithText("Continue with 2 photos").performClick()
        awaitText("Compress 2 photos?")
        haptics.types.clear()

        compose.onNodeWithText("Compress 2 photos").performClick()

        assertEquals(listOf(listOf(big.path, middle.path) to CompressionLevel.BALANCED), batches.started)
        assertEquals(listOf(HapticFeedbackType.Reject), haptics.types)
        assertEquals(emptySet<String>(), model.state.value.selected)
        assertEquals(null, model.state.value.level)
    }

    @Test
    fun `cancelling the summary starts nothing`() {
        show(model(big))
        awaitText("1 photo, largest first")
        tile("IMG_1.jpg").performClick()
        compose.onNodeWithText("Balanced").performClick()
        compose.onNodeWithText("Continue with 1 photo").performClick()
        awaitText("Compress 1 photo?")

        compose.onNodeWithText("Cancel").performClick()

        compose.onAllNodesWithText("Compress 1 photo?").assertCountEquals(0)
        assertEquals(emptyList<Any>(), batches.started)
    }

    @Test
    fun `selecting gives a tick, and a screen reader gets select and deselect as actions`() {
        show(model(big, favorites = setOf(big.path)))
        awaitText("1 photo, largest first")
        fun actions() = actionsOf("IMG_1.jpg")
        assertEquals(listOf("Select", "Open full size"), actions())
        tile("IMG_1.jpg").assertExists()
        compose.onNodeWithContentDescription("Favourite", substring = true).assertExists()

        tile("IMG_1.jpg").performClick()

        assertEquals(listOf(HapticFeedbackType.SegmentTick), haptics.types)
        assertEquals(listOf("Deselect", "Open full size"), actions())
        tile("IMG_1.jpg").assertIsSelected()
        assertTrue(tile("IMG_1.jpg").fetchSemanticsNode().config.getOrNull(SemanticsProperties.ContentDescription)!!.single().endsWith("Selected. Favourite"))
    }

    @Test
    fun `a running batch shows its progress, the saving so far and a heat pause, and stops on request`() {
        batches.mutableState.value = BatchState.Running(3, 12, 412_000_000, paused = true)
        show(model(big))

        awaitText("Compressing 4 of 12")
        compose.onAllNodesWithText("Continue", substring = true).assertCountEquals(0)
        compose.onNodeWithText("${size(412_000_000)} saved so far").assertExists()
        compose.onNodeWithText("Paused while the phone cools down.").assertExists()
        compose.onNodeWithText("Stop compressing").performClick()

        assertEquals(1, batches.cancels)
        assertEquals(listOf(HapticFeedbackType.Confirm), haptics.types)
    }

    @Test
    fun `a batch asked during a scan says it waits`() {
        batches.mutableState.value = BatchState.WaitingForScan(2)
        show(model(big))

        awaitText("Waiting for the scan to finish. The compression starts by itself afterwards.")
    }

    @Test
    fun `a finished batch shows the real saving, what was refused and one Undo, then the list again`() {
        val result = BatchResult(14, 10, mapOf(Refusal.NOT_SMALLER to 2, Refusal.LOW_MEMORY to 1), 1_300_000_000, emptyList())
        batches.mutableState.value = BatchState.Finished(result)
        show(model(big))

        awaitText("10 photos compressed")
        compose.onNodeWithText("Saved ${size(1_300_000_000)}").assertExists()
        compose.onNodeWithText("2 photos did not get smaller, so they were left as they were").assertExists()
        compose.onNodeWithText("1 photo needed more free memory than the phone had, so it was left as it was").assertExists()
        compose.onNodeWithText("Undo puts the originals back from the Cribrum trash, where they stay for 30 days.").assertExists()
        compose.onNodeWithText("Undo").performClick()
        awaitText("1 photo, largest first")
        assertEquals(listOf(result), batches.undone)
        assertEquals(listOf(HapticFeedbackType.SegmentTick), haptics.types)
    }

    @Test
    fun `an undo that waits for a scan says so, cannot be asked twice, and ends with the list`() {
        batches.undoGate = CompletableDeferred()
        batches.mutableState.value = BatchState.Finished(BatchResult(1, 1, emptyMap(), 100, emptyList()))
        show(model(big))
        awaitText("1 photo compressed")

        compose.onNodeWithText("Undo").performClick()

        awaitText("Putting the originals back. This waits for a scan or a compression that is running, which can take minutes.")
        compose.onNodeWithText("Undo").assertIsNotEnabled()
        compose.onNodeWithText("Done").assertIsNotEnabled()
        assertEquals(1, batches.undone.size)
        batches.undoGate!!.complete(Unit)

        awaitText("1 photo, largest first")
        compose.onAllNodesWithText("Putting the originals back", substring = true).assertCountEquals(0)
    }

    @Test
    fun `a process that died mid-batch comes back to the list`() {
        show(model(big))

        awaitText("1 photo, largest first")
        compose.onAllNodesWithText("Undo").assertCountEquals(0)
    }

    @Test
    fun `Done closes the result and shows the list again`() {
        batches.mutableState.value = BatchState.Finished(BatchResult(1, 1, emptyMap(), 100, emptyList()))
        show(model(big))
        awaitText("1 photo compressed")

        compose.onNodeWithText("Done").performClick()

        awaitText("1 photo, largest first")
        compose.onAllNodesWithText("Undo").assertCountEquals(0)
    }

    @Test
    fun `originals an undo could not put back are said`() {
        batches.stayed = listOf(ImageRef("/p/x.jpg", 1, 1))
        batches.mutableState.value = BatchState.Finished(BatchResult(1, 1, emptyMap(), 100, listOf(ImageRef("/p/x.jpg", 1, 1))))
        show(model(big))
        awaitText("1 photo compressed")

        compose.onNodeWithText("Undo").performClick()

        awaitText("1 original stayed in the trash, because another file took its place. Restore it from the trash.")
    }

    @Test
    fun `a batch stopped before it reached some photos says so`() {
        batches.mutableState.value = BatchState.Finished(BatchResult(5, 2, mapOf(Refusal.NOT_SMALLER to 1), 100, emptyList()))
        show(model(big))

        awaitText("2 photos were not reached and stay as they were")
    }

    @Test
    fun `a finished batch with nothing compressed offers no Undo`() {
        batches.mutableState.value = BatchState.Finished(BatchResult(1, 0, mapOf(Refusal.FAILED to 1), 0, emptyList()))
        show(model(big))

        awaitText("0 photos compressed")
        compose.onAllNodesWithText("Undo").assertCountEquals(0)
    }
}
