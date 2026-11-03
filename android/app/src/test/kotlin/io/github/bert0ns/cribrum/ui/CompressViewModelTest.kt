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
import io.github.bert0ns.cribrum.model.ImageRef
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
        previewFile = File(folder.root, "preview.jpg"),
        discard = { discarded += it },
    )

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
        assertEquals(null, model.state.value.level)
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
    fun `the summary compresses the largest selected photo at the chosen level, and closing it drops the preview`() {
        val model = loaded()
        model.toggle(b.path)
        model.toggle(a.path)
        model.chooseLevel(CompressionLevel.SMALLEST)

        model.openSummary()
        settle(model) { it.summary?.preview is SampleResult.Ready }

        assertEquals(listOf(File(a.path) to CompressionLevel.SMALLEST), previewed)
        assertEquals(a, model.state.value.summary?.sample)
        model.closeSummary()
        assertEquals(null, model.state.value.summary)
    }

    @Test
    fun `the summary needs a selection and a level`() {
        val model = loaded()
        model.openSummary()
        model.toggle(a.path)
        model.openSummary()

        assertEquals(null, model.state.value.summary)
        assertEquals(emptyList<Any>(), previewed)
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
    }

    @Test
    fun `a refused start keeps the selection`() {
        val refusing = object : CompressBatches by batches {
            override fun start(paths: List<String>, level: CompressionLevel) = false
        }
        val model = CompressViewModel(app, refusing, photos = { listed }, favorites = { emptySet() }, retentionDays = { 7 }, preview = { _, _, _ -> null }, previewFile = File(folder.root, "p.jpg"), discard = {})
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
        settle(model) { it.summary?.preview is SampleResult.Ready }
        assertEquals(a, model.state.value.summary?.sample)
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

    @Test
    fun `the preview file goes when the summary closes, when the batch starts and when the model is cleared`() {
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
        model.chooseLevel(CompressionLevel.HIGH)
        val file = File(folder.root, "preview.jpg")

        model.openSummary()
        settle(model) { it.summary?.preview is SampleResult.Ready }
        model.closeSummary()
        assertEquals(listOf(file), discarded)

        model.openSummary()
        settle(model) { it.summary?.preview is SampleResult.Ready }
        model.confirm()
        assertEquals(listOf(file, file), discarded)

        store.clear()
        assertEquals(listOf(file, file, file), discarded)
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
        settle(model) { it.summary?.preview is SampleResult.Ready }
        assertEquals(1, previewed.size)
    }

    @Test
    fun `the preview of another photo is another image for Coil, even from the same file`() {
        val file = File(folder.root, "preview.jpg").apply { writeBytes(ByteArray(1)) }
        val first = previewCacheKey(file)
        file.setLastModified(file.lastModified() + 5_000)

        assertNotEquals(first, previewCacheKey(file))
    }
}
