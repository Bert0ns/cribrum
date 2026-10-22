package io.github.bert0ns.cribrum.ui

import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.work.testing.WorkManagerTestInitHelper
import io.github.bert0ns.cribrum.CribrumApp
import io.github.bert0ns.cribrum.model.Category
import io.github.bert0ns.cribrum.model.ImageRef
import io.github.bert0ns.cribrum.model.ReviewState
import io.github.bert0ns.cribrum.model.Signal
import io.github.bert0ns.cribrum.model.toImageRef
import io.github.bert0ns.cribrum.ranker.THRESHOLDS
import io.github.bert0ns.cribrum.review.PAGE_SIZE
import io.github.bert0ns.cribrum.review.Review
import io.github.bert0ns.cribrum.trash.AppTrash
import io.github.bert0ns.cribrum.trash.Trash
import io.github.bert0ns.cribrum.trash.TrashedImage
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import java.time.Duration
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ReviewViewModelTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val app = ApplicationProvider.getApplicationContext<CribrumApp>()

    @Before
    fun setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(app)
    }

    private fun blurry(path: String, score: Float) = runBlocking {
        app.index.record(ImageRef(path, 1_000, 1L))
        app.index.setSignals(path, "blur", 1, listOf(Signal("blur", 1, Category.BLURRY, score)))
    }

    private fun paths(model: ReviewViewModel) = model.state.value.page.flatMap { group -> group.images.map { it.image.path } }

    /** The view model changes state on its own scope: run the main looper until the change is done. */
    private fun settle(model: ReviewViewModel, done: (ReviewViewModel) -> Boolean) {
        repeat(500) {
            shadowOf(Looper.getMainLooper()).idle()
            if (!model.state.value.loading && done(model)) return
            Thread.sleep(10)
        }
        throw AssertionError("never settled: ${model.state.value}")
    }

    @Test
    fun `skipping a page shows the next candidates, reviews nothing, and the skipped come back when reopened`() {
        repeat(PAGE_SIZE) { blurry("/sdcard/a$it.jpg", 0.9f) }
        blurry("/sdcard/b.jpg", 0.5f)
        val model = ReviewViewModel(app)
        model.open(Category.BLURRY)
        settle(model) { paths(it).size == PAGE_SIZE }

        model.skip(shownIds(model))
        settle(model) { paths(it) == listOf("/sdcard/b.jpg") }

        assertEquals(1, model.state.value.remaining)
        assertNull(model.state.value.lastDecision)
        runBlocking {
            assertEquals(ReviewState.UNREVIEWED, app.index.reviewState("/sdcard/a0.jpg"))
            assertEquals(ReviewState.UNREVIEWED, app.index.reviewState("/sdcard/b.jpg"))
        }

        model.back()
        settle(model) { it.state.value.screen == Screen.Home }
        model.open(Category.BLURRY)
        settle(model) { paths(it).size == PAGE_SIZE }
        assertEquals(PAGE_SIZE + 1, model.state.value.remaining)
    }

    @Test
    fun `a second skip of the page already skipped does not skip the next one unseen`() {
        repeat(PAGE_SIZE) { blurry("/sdcard/a$it.jpg", 0.9f) }
        blurry("/sdcard/b.jpg", 0.5f)
        val model = ReviewViewModel(app)
        model.open(Category.BLURRY)
        settle(model) { paths(it).size == PAGE_SIZE }
        val shown = shownIds(model)

        model.skip(shown)
        model.skip(shown)
        settle(model) { paths(it) == listOf("/sdcard/b.jpg") }

        assertEquals(1, model.state.value.remaining)
    }

    @Test
    fun `applying or undoing after a skip keeps the skipped images out`() {
        repeat(PAGE_SIZE) { blurry("/sdcard/a$it.jpg", 0.9f) }
        blurry("/sdcard/b.jpg", 0.5f)
        val model = ReviewViewModel(app)
        model.open(Category.BLURRY)
        settle(model) { paths(it).size == PAGE_SIZE }
        model.skip(shownIds(model))
        settle(model) { paths(it) == listOf("/sdcard/b.jpg") }

        // The file is not on disk, so nothing moves, but the page is rebuilt and the decision can be undone.
        model.applyPage()
        settle(model) { it.state.value.lastDecision != null }
        assertEquals(listOf("/sdcard/b.jpg"), paths(model))
        model.undo()
        settle(model) { it.state.value.lastDecision == null }
        assertEquals(listOf("/sdcard/b.jpg"), paths(model))
    }

    /** A file in a trash of its own, trashed [daysAgo] days ago (the trash reads its time from origin.properties). */
    private fun trashed(trash: AppTrash, volume: File, name: String, daysAgo: Long) = runBlocking {
        val image = File(volume, name).apply { writeText(name) }.toImageRef()
        trash.moveToTrash(listOf(image))
        val origin = trash.trashed().single { it.original == image }.file.parentFile!!.resolve("origin.properties")
        origin.writeText(
            origin.readLines().joinToString("\n") { line ->
                if (line.startsWith("trashed_at=")) "trashed_at=${Instant.now() - Duration.ofDays(daysAgo)}" else line
            },
        )
    }

    private fun modelWithTrash(vararg daysAgo: Long): ReviewViewModel {
        val volume = folder.newFolder("storage")
        val trash = AppTrash({ listOf(volume) })
        daysAgo.forEach { trashed(trash, volume, "$it.jpg", it) }
        return ReviewViewModel(app, Review(app.index, trash))
    }

    @Test
    fun `lowering the retention asks first when images would be purged, and cancel keeps the setting`() {
        app.trashRetention.days = 90
        val model = modelWithTrash(20, 2)
        settle(model) { it.state.value.retentionDays == 90 }

        model.chooseRetention(7)
        settle(model) { it.state.value.pendingRetention != null }
        assertEquals(RetentionChange(days = 7, expiring = 1), model.state.value.pendingRetention)
        assertEquals(90, model.state.value.retentionDays)

        model.cancelRetention()
        assertNull(model.state.value.pendingRetention)
        assertEquals(90, model.state.value.retentionDays)
        assertEquals(90, app.trashRetention.days)

        model.chooseRetention(7)
        settle(model) { it.state.value.pendingRetention != null }
        model.confirmRetention()
        settle(model) { it.state.value.retentionDays == 7 }
        assertNull(model.state.value.pendingRetention)
        assertEquals(7, app.trashRetention.days)
    }

    @Test
    fun `raising the retention, or lowering it when nothing would expire, needs no confirmation`() {
        app.trashRetention.days = 30
        val model = modelWithTrash(2)
        settle(model) { it.state.value.retentionDays == 30 }

        model.chooseRetention(90)
        settle(model) { it.state.value.retentionDays == 90 }
        model.chooseRetention(14)
        settle(model) { it.state.value.retentionDays == 14 }

        assertNull(model.state.value.pendingRetention)
        assertEquals(14, app.trashRetention.days)
    }

    @Test
    fun `excluding a folder hides its images, and removing the exclusion from settings brings them back`() {
        blurry("/sdcard/Family/a.jpg", 0.9f)
        blurry("/sdcard/Family/2020/b.jpg", 0.9f)
        blurry("/sdcard/Other/c.jpg", 0.8f)
        val model = ReviewViewModel(app)
        model.open(Category.BLURRY)
        settle(model) { paths(it).size == 3 }

        model.excludeFolder(ImageRef("/sdcard/Family/a.jpg", 1_000, 1L))
        settle(model) { paths(it) == listOf("/sdcard/Other/c.jpg") }
        assertEquals(1, model.state.value.remaining)

        model.back()
        settle(model) { it.state.value.screen == Screen.Home }
        model.openSettings()
        settle(model) { it.state.value.excludedFolders == listOf("/sdcard/Family") }
        model.removeExcludedFolder("/sdcard/Family")
        settle(model) { it.state.value.excludedFolders.isEmpty() }

        model.back()
        settle(model) { it.state.value.screen == Screen.Home }
        model.open(Category.BLURRY)
        settle(model) { paths(it).size == 3 }
    }

    @Test
    fun `excluding a folder keeps the marks the user changed on the page`() {
        blurry("/sdcard/A/a.jpg", 0.9f)
        blurry("/sdcard/A/b.jpg", 0.8f)
        blurry("/sdcard/B/c.jpg", 0.7f)
        blurry("/sdcard/C/d.jpg", 0.6f)
        val model = ReviewViewModel(app)
        model.open(Category.BLURRY)
        settle(model) { paths(it).size == 4 }
        model.toggle(ImageRef("/sdcard/A/a.jpg", 1_000, 1L))

        model.excludeFolder(ImageRef("/sdcard/C/d.jpg", 1_000, 1L))
        settle(model) { paths(it).size == 3 }

        assertEquals(setOf("/sdcard/A/b.jpg", "/sdcard/B/c.jpg"), model.state.value.marked)
    }

    @Test
    fun `undoing an exclusion brings the images back and keeps the marks`() {
        blurry("/sdcard/A/a.jpg", 0.9f)
        blurry("/sdcard/B/b.jpg", 0.8f)
        val model = ReviewViewModel(app)
        model.open(Category.BLURRY)
        settle(model) { paths(it).size == 2 }
        model.toggle(ImageRef("/sdcard/A/a.jpg", 1_000, 1L))

        model.excludeFolder(ImageRef("/sdcard/B/b.jpg", 1_000, 1L))
        settle(model) { it.state.value.lastExclusion == "/sdcard/B" }
        assertEquals(listOf("/sdcard/A/a.jpg"), paths(model))

        model.undoExclusion()
        settle(model) { paths(it).size == 2 }

        assertNull(model.state.value.lastExclusion)
        assertEquals(setOf("/sdcard/B/b.jpg"), model.state.value.marked)
        assertEquals(emptyList<String>(), runBlocking { app.index.excludedFolders() })
    }

    @Test
    fun `excluding a folder from the storage map takes it off the map and out of the review`() {
        blurry("/sdcard/Family/a.jpg", 0.9f)
        blurry("/sdcard/Family/2020/b.jpg", 0.9f)
        blurry("/sdcard/Other/c.jpg", 0.8f)
        val model = ReviewViewModel(app)
        model.openStorageMap()
        settle(model) { it.state.value.storageMap.size == 1 }
        assertEquals(3, model.state.value.storageMap.single().imageCount)

        model.excludeFolder("/sdcard/Family")
        settle(model) { it.state.value.lastExclusion == "/sdcard/Family" }

        assertEquals(listOf("/sdcard/Other"), model.state.value.storageMap.map { it.path })
        assertEquals(listOf("/sdcard/Family"), runBlocking { app.index.excludedFolders() })
        model.open(Category.BLURRY)
        settle(model) { paths(it) == listOf("/sdcard/Other/c.jpg") }
    }

    @Test
    fun `undoing an exclusion made on the storage map puts the folder back on the map`() {
        blurry("/sdcard/Family/a.jpg", 0.9f)
        blurry("/sdcard/Other/c.jpg", 0.8f)
        val model = ReviewViewModel(app)
        model.openStorageMap()
        settle(model) { it.state.value.storageMap.size == 1 }
        model.zoomStorageMap(model.state.value.storageMap.single().children.first())
        settle(model) { it.state.value.storageMap.size == 2 }
        model.excludeFolder("/sdcard/Family")
        settle(model) { it.state.value.lastExclusion != null }
        assertEquals(1, model.state.value.storageMap.size)

        model.undoExclusion()
        settle(model) { it.state.value.lastExclusion == null }

        assertEquals(2, model.state.value.storageMap.single().imageCount)
        assertEquals(emptyList<String>(), runBlocking { app.index.excludedFolders() })
    }

    @Test
    fun `leaving the storage map after an exclusion shows the folder in settings`() {
        blurry("/sdcard/Family/a.jpg", 0.9f)
        blurry("/sdcard/Other/c.jpg", 0.8f)
        val model = ReviewViewModel(app)
        model.openStorageMap()
        settle(model) { it.state.value.storageMap.size == 1 }
        model.excludeFolder("/sdcard/Family")
        settle(model) { it.state.value.lastExclusion != null }

        model.back()
        settle(model) { it.state.value.screen == Screen.Settings }

        assertEquals(listOf("/sdcard/Family"), model.state.value.excludedFolders)
    }

    @Test
    fun `a threshold set in settings changes the lists at once, is stored, and reset restores the defaults`() {
        blurry("/sdcard/a.jpg", 0.9f)
        blurry("/sdcard/b.jpg", 0.6f)
        blurry("/sdcard/c.jpg", 0.45f)
        val model = ReviewViewModel(app)
        model.openThresholds()
        settle(model) { it.state.value.screen == Screen.Thresholds && it.state.value.thresholdCounts.isNotEmpty() }
        assertEquals(3, model.state.value.thresholdCounts[Category.BLURRY])

        model.setThreshold(Category.BLURRY, 0.7f)
        settle(model) { it.state.value.thresholds[Category.BLURRY] == 0.7f }

        assertEquals(1, model.state.value.thresholdCounts[Category.BLURRY])
        assertEquals(3, model.state.value.defaultCounts[Category.BLURRY])
        assertEquals(0.7f, app.thresholds.values[Category.BLURRY])
        model.back()
        settle(model) { it.state.value.screen == Screen.Settings }
        model.back()
        settle(model) { it.state.value.screen == Screen.Home && it.state.value.categories.isNotEmpty() }
        assertEquals(1, model.state.value.categories.single { it.category == Category.BLURRY }.count)

        model.openSettings()
        settle(model) { it.state.value.screen == Screen.Settings }
        model.openThresholds()
        settle(model) { it.state.value.screen == Screen.Thresholds }
        model.resetThresholds()
        settle(model) { it.state.value.thresholds == THRESHOLDS }

        assertEquals(3, model.state.value.thresholdCounts[Category.BLURRY])
        assertEquals(THRESHOLDS, app.thresholds.values)
    }

    @Test
    fun `back from the thresholds returns to settings`() {
        val model = ReviewViewModel(app)
        model.openThresholds()
        settle(model) { it.state.value.screen == Screen.Thresholds }

        model.back()
        settle(model) { it.state.value.screen == Screen.Settings }
    }

    private fun shownIds(model: ReviewViewModel) = model.state.value.page.map { it.id }.toSet()

    @Test
    fun `deleting the embeddings updates the count and keeps the candidates`() {
        blurry("/sdcard/a.jpg", 0.9f)
        runBlocking { app.index.setEmbeddings("clip", 1, mapOf("/sdcard/a.jpg" to floatArrayOf(1f))) }
        val model = ReviewViewModel(app)
        model.open(Category.BLURRY)
        settle(model) { paths(it) == listOf("/sdcard/a.jpg") }
        model.openSettings()
        settle(model) { it.state.value.embeddingCount == 1 }

        model.deleteEmbeddings()
        settle(model) { it.state.value.embeddingCount == 0 }
        assertEquals(emptyMap<String, FloatArray>(), runBlocking { app.index.embeddings("clip", 1) })
        assertEquals(listOf("/sdcard/a.jpg"), paths(model))
        runBlocking { assertEquals(1, app.index.signals("/sdcard/a.jpg").size) }
    }

    @Test
    fun `haptic feedback is on until the user turns it off, and the choice is kept`() {
        val model = ReviewViewModel(app)
        settle(model) { true }
        assertEquals(true, model.state.value.hapticsEnabled)

        model.setHaptics(false)
        settle(model) { !it.state.value.hapticsEnabled }

        val next = ReviewViewModel(app)
        settle(next) { true }
        assertEquals(false, next.state.value.hapticsEnabled)
    }

    @Test
    fun `the home screen knows whether anything is indexed`() {
        val model = ReviewViewModel(app)
        model.refresh()
        settle(model) { it.state.value.categories.isNotEmpty() }
        assertTrue(model.state.value.indexEmpty)

        blurry("/sdcard/a.jpg", 0.9f)
        model.refresh()
        settle(model) { !it.state.value.indexEmpty }
    }

    @Test
    fun `settings measures the trash and the embeddings, and measures again after a delete`() {
        val volume = folder.newFolder()
        val trash = AppTrash({ listOf(volume) })
        val image = File(volume, "a.jpg").apply { writeText("0123456789") }.toImageRef()
        runBlocking {
            app.index.record(image)
            trash.moveToTrash(listOf(image))
            app.index.setEmbeddings("clip", 1, mapOf(image.path to FloatArray(4)))
        }
        val model = ReviewViewModel(app, Review(app.index, trash))

        model.openSettings()
        settle(model) { it.state.value.storage != null }
        val storage = model.state.value.storage!!
        assertEquals(10L, storage.trash)
        assertEquals(true, storage.embeddings > 0)
        assertEquals(true, storage.index > 0)

        model.deleteEmbeddings()
        settle(model) { it.state.value.storage?.embeddings == 0L }
        assertEquals(10L, model.state.value.storage?.trash)
    }

    /** A trash whose listing waits for [release], like a big one on a slow phone. */
    private class SlowTrash(private val release: CompletableDeferred<Unit>) : Trash {
        override suspend fun moveToTrash(images: List<ImageRef>) = images

        override suspend fun restore(images: List<ImageRef>) = images

        override suspend fun trashed(): List<TrashedImage> {
            release.await()
            return emptyList()
        }

        override suspend fun purge(images: List<ImageRef>) {}

        override suspend fun purgeExpired() = emptyList<ImageRef>()
    }

    @Test
    fun `leaving settings does not wait for the storage measure, and its late sizes are dropped`() {
        val release = CompletableDeferred<Unit>()
        val model = ReviewViewModel(app, Review(app.index, SlowTrash(release)))

        model.openSettings()
        settle(model) { it.state.value.screen == Screen.Settings }
        model.back()
        settle(model) { it.state.value.screen == Screen.Home } // while the measure is still stuck
        assertNull(model.state.value.storage)

        release.complete(Unit)
        Thread.sleep(300)
        shadowOf(Looper.getMainLooper()).idle()
        assertNull(model.state.value.storage)
    }
}
