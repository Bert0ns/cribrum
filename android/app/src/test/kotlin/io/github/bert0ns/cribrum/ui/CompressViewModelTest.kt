package io.github.bert0ns.cribrum.ui

import android.os.Looper
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.CreationExtras
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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class CompressViewModelTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val app = ApplicationProvider.getApplicationContext<CribrumApp>()
    private val a = CompressibleImage("/p/a.jpg", 9_000, 4000, 3000, JpegInfo(JpegStatus.ELIGIBLE, quality = 92, losses = Losses(c2pa = true)))
    private val b = CompressibleImage("/p/b.jpg", 5_000, 4000, 3000, JpegInfo(JpegStatus.ELIGIBLE, quality = 92, losses = Losses(c2pa = true, depthMap = true)))
    private val done = CompressibleImage("/p/done.jpg", 3_000, 4000, 3000, JpegInfo(JpegStatus.ELIGIBLE, quality = 85, marker = Marker(1, "smallest", null)))
    private val batches = RecordingBatches()
    private var listed = listOf(a, b, done)
    private val previewed = mutableListOf<Pair<File, CompressionLevel>>()
    private val discarded = mutableListOf<File>()

    private fun model() = CompressViewModel(
        app,
        batches,
        photos = { listed },
        favorites = { emptySet() },
        retentionDays = { 7 },
        preview = { original, level, out ->
            previewed += original to level
            out.writeBytes(ByteArray(10))
            null
        },
        previewFiles = previewFiles,
        discard = { discarded += it },
    )

    private val previewFiles by lazy { List(MAX_COMPARED) { File(folder.root, "preview$it.jpg") } }

    private fun ready(state: CompressState) = state.summary?.samples?.all { it.preview is SampleResult.Ready } == true

    private fun settle(model: CompressViewModel, done: (CompressState) -> Boolean) {
        repeat(500) {
            shadowOf(Looper.getMainLooper()).idle()
            if (done(model.state.value)) return
            Thread.sleep(10)
        }
        throw AssertionError("never settled: ${model.state.value}")
    }

    private fun loaded() = model().also {
        it.refresh()
        settle(it) { state -> state.loaded }
    }

    @Test
    fun `the photos with the marker are kept apart, and the retention is read`() {
        val model = loaded()

        assertEquals(listOf(a, b), model.state.value.photos)
        assertEquals(listOf(done), model.state.value.compressed)
        assertEquals(7, model.state.value.retentionDays)
        assertEquals(emptySet<String>(), model.state.value.selected)
        assertEquals(CompressionLevel.BALANCED, model.state.value.level)
    }

    @Test
    fun `a photo that left the list is no longer selected`() {
        val model = loaded()
        model.toggle(a.path)
        model.toggle(b.path)

        listed = listOf(b, done)
        model.refresh()
        settle(model) { it.photos == listOf(b) }

        assertEquals(setOf(b.path), model.state.value.selected)
    }

    @Test
    fun `the loss counts and the saving follow the selection`() {
        val model = loaded()
        model.toggle(a.path)
        model.toggle(b.path)

        assertEquals(LossCounts(c2pa = 2, depthMap = 1), model.state.value.lossCounts())
        assertEquals(a.saving(CompressionLevel.SMALLEST) + b.saving(CompressionLevel.SMALLEST), model.state.value.estimatedSaving(CompressionLevel.SMALLEST))
        assertTrue(model.state.value.estimatedSaving(CompressionLevel.SMALLEST) > 0)
    }

    @Test
    fun `the summary compresses the selected photo at the chosen level, and closing it drops the preview`() {
        val model = loaded()
        model.toggle(b.path)
        model.toggle(a.path)
        model.chooseLevel(CompressionLevel.SMALLEST)

        model.openSummary()
        settle(model, ::ready)

        assertEquals(listOf(File(a.path) to CompressionLevel.SMALLEST, File(b.path) to CompressionLevel.SMALLEST), previewed)
        assertEquals(listOf(a, b), model.state.value.summary?.samples?.map { it.photo })
        model.closeSummary()
        assertEquals(null, model.state.value.summary)
    }

    @Test
    fun `a level changed on the summary makes the previews again at that level`() {
        val model = loaded()
        model.toggle(a.path)
        model.openSummary()
        settle(model, ::ready)

        model.chooseLevel(CompressionLevel.HIGH)
        settle(model) { it.summary?.level == CompressionLevel.HIGH && ready(it) }

        assertEquals(listOf(File(a.path) to CompressionLevel.BALANCED, File(a.path) to CompressionLevel.HIGH), previewed)
        assertEquals(CompressionLevel.HIGH, model.state.value.level)
    }

    @Test
    fun `a level changed on the list makes no preview`() {
        val model = loaded()
        model.toggle(a.path)

        model.chooseLevel(CompressionLevel.HIGH)
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(emptyList<Pair<File, CompressionLevel>>(), previewed)
        assertEquals(null, model.state.value.summary)
    }

    private fun photos(count: Int) = List(count) { CompressibleImage("/p/$it.jpg", 1_000_000L - it, 4000, 3000, JpegInfo(JpegStatus.ELIGIBLE, quality = 92)) }

    private fun compared(count: Int): List<String> {
        listed = photos(count)
        val model = loaded()
        model.selectAll(true)
        return model.state.value.comparedPhotos.map { it.path }
    }

    @Test
    fun `up to three photos are all compared`() {
        assertEquals(emptyList<String>(), compared(0))
        assertEquals(listOf("/p/0.jpg"), compared(1))
        assertEquals(listOf("/p/0.jpg", "/p/1.jpg"), compared(2))
        assertEquals(listOf("/p/0.jpg", "/p/1.jpg", "/p/2.jpg"), compared(3))
    }

    @Test
    fun `more than three compare the largest, the middle one by size and the smallest`() {
        assertEquals(listOf("/p/0.jpg", "/p/2.jpg", "/p/3.jpg"), compared(4))
        assertEquals(listOf("/p/0.jpg", "/p/3.jpg", "/p/6.jpg"), compared(7))
        assertEquals(listOf("/p/0.jpg", "/p/25.jpg", "/p/49.jpg"), compared(50))
    }

    @Test
    fun `the previews run one after the other, and the first shows as soon as it is ready`() {
        val gates = List(3) { CompletableDeferred<Unit>() }
        var running = 0
        var most = 0
        val order = mutableListOf<String>()
        listed = photos(3)
        val model = CompressViewModel(
            app,
            batches,
            photos = { listed },
            favorites = { emptySet() },
            retentionDays = { 7 },
            preview = { original, _, out ->
                running++
                most = maxOf(most, running)
                order += original.name
                gates[order.size - 1].await()
                out.writeBytes(ByteArray(10))
                running--
                null
            },
            previewFiles = previewFiles,
            discard = {},
        )
        model.refresh()
        settle(model) { it.loaded }
        model.selectAll(true)
        model.chooseLevel(CompressionLevel.BALANCED)

        model.openSummary()
        settle(model) { order.size == 1 }
        assertEquals(List(3) { SampleResult.Loading }, model.state.value.summary?.samples?.map { it.preview })

        gates[0].complete(Unit)
        settle(model) { it.summary?.samples?.get(0)?.preview is SampleResult.Ready }
        settle(model) { order.size == 2 }
        assertEquals(SampleResult.Loading, model.state.value.summary?.samples?.get(1)?.preview)

        gates[1].complete(Unit)
        gates[2].complete(Unit)
        settle(model, ::ready)

        assertEquals(listOf("0.jpg", "1.jpg", "2.jpg"), order)
        assertEquals(1, most)
    }

    @Test
    fun `a photo whose preview is refused says why and the others still get theirs`() {
        listed = photos(3)
        val model = CompressViewModel(
            app,
            batches,
            photos = { listed },
            favorites = { emptySet() },
            retentionDays = { 7 },
            preview = { original, _, out ->
                out.writeBytes(ByteArray(10))
                if (original.name == "1.jpg") Refusal.LOW_MEMORY else null
            },
            previewFiles = previewFiles,
            discard = {},
        )
        model.refresh()
        settle(model) { it.loaded }
        model.selectAll(true)
        model.chooseLevel(CompressionLevel.HIGH)

        model.openSummary()
        settle(model) { state -> state.summary?.samples?.none { it.preview is SampleResult.Loading } == true }

        assertEquals(
            listOf(SampleResult.Ready(previewFiles[0]), SampleResult.Failed(Refusal.LOW_MEMORY), SampleResult.Ready(previewFiles[2])),
            model.state.value.summary?.samples?.map { it.preview },
        )
    }

    @Test
    fun `closing the summary stops the previews that have not started`() {
        val gate = CompletableDeferred<Unit>()
        listed = photos(3)
        val started = mutableListOf<String>()
        val model = CompressViewModel(
            app,
            batches,
            photos = { listed },
            favorites = { emptySet() },
            retentionDays = { 7 },
            preview = { original, _, _ ->
                started += original.name
                gate.await()
                null
            },
            previewFiles = previewFiles,
            discard = {},
        )
        model.refresh()
        settle(model) { it.loaded }
        model.selectAll(true)
        model.chooseLevel(CompressionLevel.HIGH)
        model.openSummary()
        settle(model) { started.size == 1 }

        model.closeSummary()
        gate.complete(Unit)
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(listOf("0.jpg"), started)
    }

    @Test
    fun `the summary needs a selection, and compresses at Balanced when no level was chosen`() {
        val model = loaded()
        model.openSummary()

        assertEquals(null, model.state.value.summary)
        assertEquals(emptyList<Any>(), previewed)

        model.toggle(a.path)
        model.openSummary()
        settle(model, ::ready)

        assertEquals(CompressionLevel.BALANCED, model.state.value.summary?.level)
        assertEquals(listOf(File(a.path) to CompressionLevel.BALANCED), previewed)
    }

    @Test
    fun `confirming starts the batch, and starting another shows its result even when it looks like the last`() {
        val model = loaded()
        val result = BatchResult(1, 1, emptyMap(), 100, emptyList())
        model.dismissResult(result)
        model.toggle(a.path)
        model.chooseLevel(CompressionLevel.HIGH)

        model.confirm()

        assertEquals(listOf(listOf(a.path) to CompressionLevel.HIGH), batches.started)
        assertEquals(null, model.state.value.dismissed)
        // The selection is spent, the level stays for the next batch.
        assertEquals(CompressionLevel.HIGH, model.state.value.level)
    }

    @Test
    fun `a refused start keeps the selection`() {
        val refusing = object : CompressBatches by batches {
            override fun start(paths: List<String>, level: CompressionLevel) = false
        }
        val model = CompressViewModel(app, refusing, photos = { listed }, favorites = { emptySet() }, retentionDays = { 7 }, preview = { _, _, _ -> null }, previewFiles = previewFiles, discard = {})
        model.refresh()
        settle(model) { it.loaded }
        model.toggle(a.path)
        model.chooseLevel(CompressionLevel.HIGH)

        model.confirm()

        assertEquals(setOf(a.path), model.state.value.selected)
    }

    @Test
    fun `an undo says how many originals stayed in the trash`() {
        val model = loaded()
        batches.stayed = listOf(ImageRef("/p/a.jpg", 1, 1))

        model.undo(BatchResult(1, 1, emptyMap(), 100, listOf(ImageRef("/p/a.jpg", 1, 1))))
        settle(model) { it.stayedInTrash == 1 }
    }

    @Test
    fun `a selection across both sections goes largest first`() {
        val model = loaded()
        model.toggle(done.path)
        model.toggle(b.path)
        model.toggle(a.path)
        model.chooseLevel(CompressionLevel.BALANCED)

        assertEquals(listOf(a, b, done), model.state.value.selectedPhotos)
        model.openSummary()
        settle(model, ::ready)
        assertEquals(listOf(a, b, done), model.state.value.summary?.samples?.map { it.photo })
        model.confirm()

        assertEquals(listOf(listOf(a.path, b.path, done.path) to CompressionLevel.BALANCED), batches.started)
    }

    @Test
    fun `equal sizes go by path`() {
        val twin = a.copy(path = "/p/0.jpg")
        listed = listOf(a, twin)
        val model = loaded()
        model.selectAll(true)

        assertEquals(listOf(twin, a), model.state.value.selectedPhotos)
    }

    /** [count] photos, largest first like the index gives them. */
    private fun many(count: Int, prefix: String = "p", marker: Marker? = null) = List(count) { CompressibleImage("/m/$prefix${"%03d".format(it)}.jpg", 100_000L - it, 4000, 3000, JpegInfo(JpegStatus.ELIGIBLE, quality = 92, marker = marker)) }

    private fun loaded(photos: List<CompressibleImage>) = run {
        listed = photos
        loaded()
    }

    @Test
    fun `the photos show in pages of 50, and a page number out of range is kept inside the list`() {
        val all = many(120)
        val model = loaded(all)

        assertEquals(all.take(50), model.state.value.pagePhotos)
        model.goToPage(1)
        assertEquals(all.subList(50, 100), model.state.value.pagePhotos)
        model.goToPage(9)
        assertEquals(all.drop(100), model.state.value.pagePhotos)
        model.goToPage(-3)
        assertEquals(all.take(50), model.state.value.pagePhotos)
    }

    @Test
    fun `select all picks the current page only, and the selection on other pages stays`() {
        val all = many(120)
        val model = loaded(all)

        model.selectAll(true)
        model.goToPage(2)
        model.toggle(all[110].path)
        model.selectAll(true)
        assertEquals((all.take(50) + all.drop(100)).map { it.path }.toSet(), model.state.value.selected)

        model.goToPage(0)
        model.selectAll(false)
        assertEquals(all.drop(100).map { it.path }.toSet(), model.state.value.selected)
        assertEquals(20, model.state.value.selectedPhotos.size)
    }

    @Test
    fun `the list shrinking under the page brings the page back to the last one`() {
        val all = many(120)
        val model = loaded(all)
        model.goToPage(2)

        listed = all.take(60)
        model.refresh()
        settle(model) { it.photos.size == 60 }

        assertEquals(all.subList(50, 60), model.state.value.pagePhotos)
    }

    @Test
    fun `the compressed photos page on their own`() {
        val done = many(70, "d", Marker(1, "balanced", null))
        val model = loaded(many(60) + done)

        assertEquals(done.take(50), model.state.value.pageCompressed)
        model.goToCompressedPage(1)
        assertEquals(done.drop(50), model.state.value.pageCompressed)
        assertEquals(50, model.state.value.pagePhotos.size)
        assertEquals(0, model.state.value.page)
    }

    @Test
    fun `the preview files go when the summary closes, when the batch starts and when the model is cleared`() {
        val store = ViewModelStore()
        val model = ViewModelProvider(
            store,
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras) = model() as T
            },
        )[CompressViewModel::class.java]
        model.refresh()
        settle(model) { it.loaded }
        model.toggle(a.path)
        model.toggle(b.path)
        model.chooseLevel(CompressionLevel.HIGH)

        model.openSummary()
        settle(model, ::ready)
        model.closeSummary()
        assertEquals(previewFiles, discarded)

        model.openSummary()
        settle(model, ::ready)
        model.confirm()
        assertEquals(previewFiles + previewFiles, discarded)

        store.clear()
        assertEquals(previewFiles + previewFiles + previewFiles, discarded)
    }

    @Test
    fun `no preview while a batch waits or runs`() {
        val model = loaded()
        model.toggle(a.path)
        model.chooseLevel(CompressionLevel.HIGH)

        for (busy in listOf(BatchState.WaitingForScan(1), BatchState.Running(0, 1, 0))) {
            batches.mutableState.value = busy
            model.openSummary()
            assertEquals(null, model.state.value.summary)
        }
        assertEquals(emptyList<Any>(), previewed)
        batches.mutableState.value = BatchState.Idle
        model.openSummary()
        settle(model, ::ready)
        assertEquals(1, previewed.size)
    }

    @Test
    fun `the preview of another photo is another image for Coil, even from the same file`() {
        val file = File(folder.root, "preview.jpg").apply { writeBytes(ByteArray(1)) }
        val first = previewCacheKey(file)
        file.setLastModified(file.lastModified() + 5_000)

        assertNotEquals(first, previewCacheKey(file))
    }

    @Test
    fun `an undo is on until it ends, is not asked twice, and a failed one can be asked again`() {
        val model = loaded()
        val result = BatchResult(1, 1, emptyMap(), 100, emptyList())
        batches.undoGate = CompletableDeferred()

        model.undo(result)
        model.undo(result)
        settle(model) { it.undoing }
        assertEquals(1, batches.undone.size)
        batches.undoGate!!.complete(Unit)
        settle(model) { !it.undoing }

        batches.undoGate = CompletableDeferred<Unit>().also { it.completeExceptionally(java.io.IOException("trash")) }
        model.undo(result)
        settle(model) { !it.undoing && batches.undone.size == 2 }
    }
}
