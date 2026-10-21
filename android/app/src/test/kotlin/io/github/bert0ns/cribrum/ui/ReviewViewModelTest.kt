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
import io.github.bert0ns.cribrum.review.PAGE_SIZE
import io.github.bert0ns.cribrum.review.Review
import io.github.bert0ns.cribrum.trash.AppTrash
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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

    private fun shownIds(model: ReviewViewModel) = model.state.value.page.map { it.id }.toSet()
}
