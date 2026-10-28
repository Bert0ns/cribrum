package io.github.bert0ns.cribrum.ui

import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.work.testing.WorkManagerTestInitHelper
import io.github.bert0ns.cribrum.CribrumApp
import io.github.bert0ns.cribrum.metadata.ImageMetadata
import io.github.bert0ns.cribrum.model.Category
import io.github.bert0ns.cribrum.model.GROUP_KEY
import io.github.bert0ns.cribrum.model.ImageRef
import io.github.bert0ns.cribrum.model.ROLE_KEY
import io.github.bert0ns.cribrum.model.ReviewState
import io.github.bert0ns.cribrum.model.Role
import io.github.bert0ns.cribrum.model.Signal
import io.github.bert0ns.cribrum.model.toImageRef
import io.github.bert0ns.cribrum.pipeline.Coverage
import io.github.bert0ns.cribrum.pipeline.Stats
import io.github.bert0ns.cribrum.ranker.THRESHOLDS
import io.github.bert0ns.cribrum.review.PAGE_SIZE
import io.github.bert0ns.cribrum.review.Review
import io.github.bert0ns.cribrum.review.ReviewOrder
import io.github.bert0ns.cribrum.trash.AppTrash
import io.github.bert0ns.cribrum.trash.Trash
import io.github.bert0ns.cribrum.trash.TrashedImage
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
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
import java.io.IOException
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

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
        applyShown(model)
        settle(model) { it.state.value.lastDecision != null }
        assertEquals(listOf("/sdcard/b.jpg"), paths(model))
        model.undo(model.state.value.lastDecision!!)
        settle(model) { it.state.value.lastDecision == null }
        assertEquals(listOf("/sdcard/b.jpg"), paths(model))
    }

    @Test
    fun `applyPage on a near-duplicate group does not mark sticker or blurry candidates as kept`() {
        val best = "/sdcard/best.jpg"
        val blurry = "/sdcard/blurry.jpg"
        val sticker = "/sdcard/sticker.jpg"
        val plain = "/sdcard/plain.jpg"

        runBlocking {
            app.index.record(ImageRef(best, 1_000, 1L))
            app.index.setSignals(
                best,
                "near_duplicate",
                2,
                listOf(Signal("near_duplicate", 2, Category.NEAR_DUPLICATE, 0f, mapOf(GROUP_KEY to "g", ROLE_KEY to Role.BEST.value))),
            )

            app.index.record(ImageRef(plain, 1_000, 1L))
            app.index.setSignals(
                plain,
                "near_duplicate",
                2,
                listOf(Signal("near_duplicate", 2, Category.NEAR_DUPLICATE, 0.8f, mapOf(GROUP_KEY to "g", ROLE_KEY to Role.INFERIOR.value))),
            )

            app.index.record(ImageRef(blurry, 1_000, 1L))
            app.index.setSignals(
                blurry,
                "near_duplicate",
                2,
                listOf(Signal("near_duplicate", 2, Category.NEAR_DUPLICATE, 0.8f, mapOf(GROUP_KEY to "g", ROLE_KEY to Role.INFERIOR.value))),
            )
            app.index.setSignals(blurry, "blur", 1, listOf(Signal("blur", 1, Category.BLURRY, 0.9f)))

            app.index.record(ImageRef(sticker, 1_000, 1L))
            app.index.setSignals(
                sticker,
                "near_duplicate",
                2,
                listOf(Signal("near_duplicate", 2, Category.NEAR_DUPLICATE, 0.8f, mapOf(GROUP_KEY to "g", ROLE_KEY to Role.INFERIOR.value))),
            )
            app.index.setSignals(sticker, "sticker", 1, listOf(Signal("sticker", 1, Category.STICKER, 1f)))
        }

        val model = ReviewViewModel(app)
        model.open(Category.NEAR_DUPLICATE)
        settle(model) { paths(it).isNotEmpty() }

        assertEquals(listOf(best, plain, blurry).sorted(), paths(model).sorted())

        if (plain in model.state.value.marked) model.toggle(ImageRef(plain, 1_000, 1L))
        if (blurry in model.state.value.marked) model.toggle(ImageRef(blurry, 1_000, 1L))
        settle(model) { model.state.value.marked.isEmpty() }

        applyShown(model)
        settle(model) { it.state.value.lastDecision != null }

        runBlocking {
            assertEquals(ReviewState.KEPT, app.index.reviewState(best))
            assertEquals(ReviewState.KEPT, app.index.reviewState(plain))
            assertEquals(ReviewState.UNREVIEWED, app.index.reviewState(blurry))
            assertEquals(ReviewState.UNREVIEWED, app.index.reviewState(sticker))
        }
    }

    @Test
    fun `grouping a category by date reorders its page, keeps the user's marks, and is remembered for that category`() {
        blurry("/sdcard/old.jpg", 0.9f)
        blurry("/sdcard/new.jpg", 0.5f)
        runBlocking {
            app.index.setMetadata("/sdcard/old.jpg", ImageMetadata(10, 10, "image/jpeg", capturedAt = LocalDateTime.of(2020, 1, 1, 12, 0)))
            app.index.setMetadata("/sdcard/new.jpg", ImageMetadata(10, 10, "image/jpeg", capturedAt = LocalDateTime.of(2026, 1, 1, 12, 0)))
        }
        val model = ReviewViewModel(app)
        model.open(Category.BLURRY)
        settle(model) { paths(it).size == 2 }
        assertEquals(ReviewOrder.OFF, model.state.value.reviewOrder)
        assertEquals(listOf("/sdcard/old.jpg", "/sdcard/new.jpg"), paths(model))
        model.toggle(model.state.value.page.first().images.single().image) // keep old.jpg

        model.setReviewOrder(ReviewOrder.MONTH)
        settle(model) { it.state.value.reviewOrder == ReviewOrder.MONTH }

        assertEquals(listOf("/sdcard/new.jpg", "/sdcard/old.jpg"), paths(model))
        assertEquals(setOf("/sdcard/new.jpg"), model.state.value.marked)
        val next = ReviewViewModel(app)
        next.open(Category.BLURRY)
        settle(next) { paths(it).size == 2 }
        assertEquals(ReviewOrder.MONTH, next.state.value.reviewOrder)
        assertEquals(listOf("/sdcard/new.jpg", "/sdcard/old.jpg"), paths(next))
        next.open(Category.SCREENSHOT)
        settle(next) { it.state.value.screen == Screen.Category(Category.SCREENSHOT) }
        assertEquals(ReviewOrder.OFF, next.state.value.reviewOrder)
    }

    @Test
    fun `the date order holds after skipping, applying and undoing`() {
        listOf("old" to (0.9f to 2020), "mid" to (0.7f to 2024), "new" to (0.5f to 2026)).forEach { (name, found) ->
            val (score, year) = found
            blurry("/sdcard/$name.jpg", score)
            runBlocking { app.index.setMetadata("/sdcard/$name.jpg", ImageMetadata(10, 10, "image/jpeg", capturedAt = LocalDateTime.of(year, 1, 1, 12, 0))) }
        }
        val model = ReviewViewModel(app)
        model.open(Category.BLURRY)
        settle(model) { paths(it).size == 3 }
        model.setReviewOrder(ReviewOrder.MONTH)
        settle(model) { it.state.value.reviewOrder == ReviewOrder.MONTH }
        assertEquals(listOf("/sdcard/new.jpg", "/sdcard/mid.jpg", "/sdcard/old.jpg"), paths(model))

        model.skip(setOf("/sdcard/mid.jpg"))
        settle(model) { paths(it).size == 2 }
        assertEquals(listOf("/sdcard/new.jpg", "/sdcard/old.jpg"), paths(model))

        // The files are not on disk, so nothing moves, but the page is rebuilt and the decision can be undone.
        applyShown(model)
        settle(model) { it.state.value.lastDecision != null }
        assertEquals(listOf("/sdcard/new.jpg", "/sdcard/old.jpg"), paths(model))

        model.undo(model.state.value.lastDecision!!)
        settle(model) { it.state.value.lastDecision == null }
        assertEquals(listOf("/sdcard/new.jpg", "/sdcard/old.jpg"), paths(model))
    }

    @Test
    fun `a category without the control ignores a date order`() {
        val model = ReviewViewModel(app)
        model.open(Category.STICKER)
        settle(model) { it.state.value.screen == Screen.Category(Category.STICKER) }

        model.setReviewOrder(ReviewOrder.DAY)
        settle(model) { true }

        assertEquals(ReviewOrder.OFF, model.state.value.reviewOrder)
    }

    @Test
    fun `sorting a category by size is remembered, and the home total counts each image once`() {
        blurry("/sdcard/small.jpg", 0.9f) // the index says 1,000 bytes
        runBlocking { app.index.record(ImageRef("/sdcard/large.jpg", 5_000, 1L)) }
        runBlocking {
            app.index.setSignals("/sdcard/large.jpg", "blur", 1, listOf(Signal("blur", 1, Category.BLURRY, 0.5f)))
            app.index.setSignals("/sdcard/large.jpg", "shot", 1, listOf(Signal("shot", 1, Category.SCREENSHOT, 0.9f)))
        }
        val model = ReviewViewModel(app)
        model.refresh()
        settle(model) { it.state.value.categories.isNotEmpty() }
        assertEquals(6_000L, model.state.value.freeableBytes)

        model.open(Category.BLURRY)
        settle(model) { paths(it).size == 2 }
        model.setReviewOrder(ReviewOrder.SIZE)
        settle(model) { it.state.value.reviewOrder == ReviewOrder.SIZE }

        assertEquals(listOf("/sdcard/large.jpg", "/sdcard/small.jpg"), paths(model))
        model.open(Category.STICKER)
        settle(model) { it.state.value.screen == Screen.Category(Category.STICKER) }
        model.setReviewOrder(ReviewOrder.SIZE)
        settle(model) { it.state.value.reviewOrder == ReviewOrder.SIZE }
        val next = ReviewViewModel(app)
        next.open(Category.STICKER)
        settle(next) { it.state.value.screen == Screen.Category(Category.STICKER) }
        assertEquals(ReviewOrder.SIZE, next.state.value.reviewOrder)
    }

    /** A file in a trash of its own, trashed [daysAgo] days ago (the trash reads its time from origin.properties). */
    private fun trashed(trash: AppTrash, volume: File, name: String, daysAgo: Long) = runBlocking {
        val image = File(volume, name).apply { writeText(name) }.toImageRef()
        trash.moveToTrash(listOf(image))
        val origin = trash.trashed().single { it.original == image }.file.parentFile!!.resolve("origin.properties")
        val trashedAt = Instant.now() - Duration.ofDays(daysAgo)
        origin.writeText(
            origin.readLines().joinToString("\n") { line ->
                when {
                    line.startsWith("trashed_at=") -> "trashed_at=$trashedAt"
                    line.startsWith("seen_at=") -> "seen_at=$trashedAt"
                    else -> line
                }
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

    @Test
    fun `thresholdCounts and defaultCounts update when index changes while on Thresholds screen`() {
        blurry("/sdcard/a.jpg", 0.9f)
        val model = ReviewViewModel(app)
        model.openThresholds()
        settle(model) { it.state.value.screen == Screen.Thresholds && it.state.value.thresholdCounts.isNotEmpty() }
        assertEquals(1, model.state.value.thresholdCounts[Category.BLURRY])
        assertEquals(1, model.state.value.defaultCounts[Category.BLURRY])

        blurry("/sdcard/b.jpg", 0.9f)
        model.openThresholds()
        settle(model) { it.state.value.thresholdCounts[Category.BLURRY] == 2 }

        assertEquals(2, model.state.value.thresholdCounts[Category.BLURRY])
        assertEquals(2, model.state.value.defaultCounts[Category.BLURRY])
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

    /** A view model whose [Review] counts the overview loads: each one reads the thresholds once. */
    private fun modelCountingLoads(): Pair<ReviewViewModel, AtomicInteger> {
        val loads = AtomicInteger()
        val review = Review(app.index, app.trash, thresholds = {
            loads.incrementAndGet()
            THRESHOLDS
        })
        return ReviewViewModel(app, review) to loads
    }

    @Test
    fun `a cold start loads the overview once, for the resume that sees access and the first composition together`() {
        blurry("/sdcard/a.jpg", 0.9f)
        val (model, loads) = modelCountingLoads()

        model.refreshAfterAccessGranted() // MainActivity.onResume
        model.refresh() // the first composition of the home
        settle(model) { it.state.value.categories.isNotEmpty() }
        Thread.sleep(100)

        assertEquals(1, loads.get())
    }

    @Test
    fun `access granted while another screen is open refreshes the overview`() {
        blurry("/sdcard/a.jpg", 0.9f)
        val (model, loads) = modelCountingLoads()
        model.open(Category.BLURRY)
        settle(model) { it.state.value.screen == Screen.Category(Category.BLURRY) }
        val before = loads.get()

        model.refreshAfterAccessGranted()
        settle(model) { it.state.value.categories.isNotEmpty() }

        assertEquals(before + 1, loads.get())
    }

    @Test
    fun `refreshes asked one after another are not merged`() {
        blurry("/sdcard/a.jpg", 0.9f)
        val (model, loads) = modelCountingLoads()

        repeat(4) {
            // the start, two steps, the end
            model.refresh()
            settle(model) { true }
        }

        assertEquals(4, loads.get())
    }

    @Test
    fun `a refresh replaces the one before it, so overlapping refreshes load once, and the queue is left alone`() {
        blurry("/sdcard/a.jpg", 0.9f)
        val (model, loads) = modelCountingLoads()
        model.setHaptics(false)

        model.refresh() // replaced by the next
        model.refresh() // replaced by the last
        model.setHaptics(true) // not a read: must not be cancelled
        model.refresh()
        settle(model) { it.state.value.categories.isNotEmpty() }
        Thread.sleep(100)
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(1, loads.get())
        assertEquals(true, model.state.value.hapticsEnabled)
        assertEquals(false, model.state.value.loading)
        assertEquals(1, model.state.value.categories.first { it.category == Category.BLURRY }.count)
    }

    @Test
    fun `settings shows what each detector analysed and the last finished scan`() {
        blurry("/sdcard/a.jpg", 0.9f)
        app.lastScan.record(Stats(unreadable = 3), finishedAt = 5)
        val model = ReviewViewModel(app)

        model.openSettings()
        settle(model) { it.state.value.coverage != null }

        assertEquals(Coverage("blur", 1, analysed = 1, needed = 1), model.state.value.coverage!!.single { it.id == "blur" })
        assertEquals(3, model.state.value.lastScan!!.unreadable)
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

        override suspend fun purge(images: List<ImageRef>) = images

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

    /** A trash that records what it was asked and can hold the moves, like a slow volume. */
    private class RecordingTrash(private val gate: CompletableDeferred<Unit>? = null) : Trash {
        val moves = mutableListOf<List<ImageRef>>()
        val restores = mutableListOf<List<ImageRef>>()

        override suspend fun moveToTrash(images: List<ImageRef>): List<ImageRef> {
            gate?.await()
            moves += images
            return images
        }

        override suspend fun restore(images: List<ImageRef>): List<ImageRef> {
            restores += images
            return images
        }

        override suspend fun trashed() = emptyList<TrashedImage>()

        override suspend fun purge(images: List<ImageRef>) = images

        override suspend fun purgeExpired() = emptyList<ImageRef>()
    }

    /** What the screen hands over when the user confirms: the marked paths to trash, and the others on the page to keep. */
    private fun confirm(model: ReviewViewModel): Pair<Set<String>, Set<String>> {
        val marked = model.state.value.marked
        return marked to (paths(model).toSet() - marked)
    }

    private fun applyShown(model: ReviewViewModel) {
        val (trash, keep) = confirm(model)
        model.applyPage(trash, keep)
    }

    /** A page of [PAGE_SIZE] blurry images, and [more] on the next one. */
    private fun twoPages(more: Int = 1) {
        repeat(PAGE_SIZE) { blurry("/sdcard/a$it.jpg", 0.9f) }
        repeat(more) { blurry("/sdcard/b$it.jpg", 0.5f) }
    }

    @Test
    fun `confirming the same page twice while the first apply runs trashes the next page for no one`() {
        twoPages()
        val gate = CompletableDeferred<Unit>()
        val trash = RecordingTrash(gate)
        val model = ReviewViewModel(app, Review(app.index, trash))
        model.open(Category.BLURRY)
        settle(model) { paths(it).size == PAGE_SIZE }
        val (toTrash, toKeep) = confirm(model)

        model.applyPage(toTrash, toKeep)
        model.applyPage(toTrash, toKeep) // a second tap, while the first still moves files
        gate.complete(Unit)
        settle(model) { paths(it) == listOf("/sdcard/b0.jpg") && it.state.value.lastDecision != null }
        Thread.sleep(200)
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(1, trash.moves.size)
        assertEquals(PAGE_SIZE, model.state.value.lastDecision!!.trashed.size)
        assertEquals(listOf("/sdcard/b0.jpg"), paths(model))
        runBlocking { assertEquals(ReviewState.UNREVIEWED, app.index.reviewState("/sdcard/b0.jpg")) }
    }

    @Test
    fun `an apply for a page that is no longer shown does nothing`() {
        twoPages()
        val trash = RecordingTrash()
        val model = ReviewViewModel(app, Review(app.index, trash))
        model.open(Category.BLURRY)
        settle(model) { paths(it).size == PAGE_SIZE }

        model.applyPage(setOf("/sdcard/a0.jpg"), emptySet()) // not what the page holds
        settle(model) { true }

        assertEquals(emptyList<List<ImageRef>>(), trash.moves)
        assertNull(model.state.value.lastDecision)
        assertEquals(PAGE_SIZE, paths(model).size)
    }

    @Test
    fun `undo of a decision that is no longer the last one does nothing`() {
        twoPages()
        val trash = RecordingTrash()
        val model = ReviewViewModel(app, Review(app.index, trash))
        model.open(Category.BLURRY)
        settle(model) { paths(it).size == PAGE_SIZE }
        applyShown(model)
        settle(model) { it.state.value.lastDecision != null }
        val first = model.state.value.lastDecision!!
        applyShown(model)
        settle(model) { it.state.value.lastDecision != first }
        val second = model.state.value.lastDecision!!

        model.undo(first) // the snackbar of the first page, still on screen
        settle(model) { true }

        assertEquals(emptyList<List<ImageRef>>(), trash.restores)
        assertEquals(second, model.state.value.lastDecision)
        model.undo(second)
        settle(model) { it.state.value.lastDecision == null }
        assertEquals(listOf(second.trashed), trash.restores)
    }

    @Test
    fun `a tap made while a page loads is kept`() {
        repeat(3) { blurry("/sdcard/a$it.jpg", 0.9f - it / 10f) }
        val slow = AtomicBoolean(false)
        val held = CountDownLatch(1)
        val review = Review(app.index, RecordingTrash(), thresholds = {
            if (slow.get()) held.await()
            THRESHOLDS
        })
        val model = ReviewViewModel(app, review)
        model.open(Category.BLURRY)
        settle(model) { paths(it).size == 3 }
        slow.set(true)

        model.setReviewOrder(ReviewOrder.MONTH)
        shadowOf(Looper.getMainLooper()).idle()
        model.toggle(ImageRef("/sdcard/a0.jpg", 1_000, 1L)) // the page is still the old one on screen
        held.countDown()
        settle(model) { it.state.value.reviewOrder == ReviewOrder.MONTH && "/sdcard/a0.jpg" !in it.state.value.marked }

        assertEquals(setOf("/sdcard/a1.jpg", "/sdcard/a2.jpg"), model.state.value.marked)
    }

    @Test
    fun `skipping after an apply keeps the undo of that apply`() {
        twoPages(more = 2)
        val trash = RecordingTrash()
        val model = ReviewViewModel(app, Review(app.index, trash))
        model.open(Category.BLURRY)
        settle(model) { paths(it).size == PAGE_SIZE }
        applyShown(model)
        settle(model) { it.state.value.lastDecision != null }
        val applied = model.state.value.lastDecision

        model.skip(shownIds(model))
        settle(model) { paths(it).isEmpty() }

        assertEquals(applied, model.state.value.lastDecision)
        model.undo(applied!!)
        settle(model) { it.state.value.lastDecision == null }
        assertEquals(listOf(applied.trashed), trash.restores)
    }

    /** A trash holding [held], whose restore and purge handle only the first image asked. */
    private class StubbornTrash(held: List<ImageRef>) : Trash {
        private val inside = held.toMutableList()

        override suspend fun moveToTrash(images: List<ImageRef>) = images

        override suspend fun restore(images: List<ImageRef>) = images.take(1).also { inside.removeAll(it) }

        override suspend fun trashed() = inside.map { TrashedImage(it, Instant.EPOCH, File(it.path), Instant.EPOCH) }

        override suspend fun purge(images: List<ImageRef>) = images.take(1).also { inside.removeAll(it) }

        override suspend fun purgeExpired() = emptyList<ImageRef>()
    }

    @Test
    fun `restoring from the trash keeps only what came back and shows what is left`() {
        val one = ImageRef("/sdcard/one.jpg", 1_000, 1L)
        val two = ImageRef("/sdcard/two.jpg", 1_000, 1L)
        runBlocking {
            listOf(one, two).forEach {
                app.index.record(it)
                app.index.setReviewState(it.path, ReviewState.TRASHED)
            }
        }
        val model = ReviewViewModel(app, Review(app.index, StubbornTrash(listOf(one, two))))
        model.openTrash()
        settle(model) { it.state.value.trashed.size == 2 }
        model.select(model.state.value.trashed[0])
        model.select(model.state.value.trashed[1])

        model.restoreSelected()
        settle(model) { it.state.value.trashed.size == 1 }

        assertEquals(listOf(two), model.state.value.trashed.map { it.original })
        assertEquals(setOf(two.path), model.state.value.selected) // what stayed in the trash stays selected
        assertEquals(listOf(two), model.state.value.stayedInTrash)
        runBlocking {
            assertEquals(ReviewState.KEPT, app.index.reviewState(one.path))
            assertEquals(ReviewState.TRASHED, app.index.reviewState(two.path))
        }
    }

    /** A trash holding [held], whose restore refuses the paths in [occupied], like a file that took the name. */
    private class OccupiedTrash(held: List<ImageRef>, var occupied: Set<String> = emptySet()) : Trash {
        private val inside = held.toMutableList()

        override suspend fun moveToTrash(images: List<ImageRef>) = images.also { inside += it }

        override suspend fun restore(images: List<ImageRef>) = images.filter { it.path !in occupied }.also { inside.removeAll(it) }

        override suspend fun trashed() = inside.map { TrashedImage(it, Instant.EPOCH, File(it.path), Instant.EPOCH) }

        override suspend fun purge(images: List<ImageRef>) = images.also { inside.removeAll(it) }

        override suspend fun purgeExpired() = emptyList<ImageRef>()
    }

    @Test
    fun `restoring an image whose path is taken keeps it selected and says it stayed`() {
        val one = ImageRef("/sdcard/one.jpg", 1_000, 1L)
        val two = ImageRef("/sdcard/two.jpg", 1_000, 1L)
        runBlocking {
            listOf(one, two).forEach {
                app.index.record(it)
                app.index.setReviewState(it.path, ReviewState.TRASHED)
            }
        }
        val model = ReviewViewModel(app, Review(app.index, OccupiedTrash(listOf(one, two), occupied = setOf(two.path))))
        model.openTrash()
        settle(model) { it.state.value.trashed.size == 2 }
        model.selectAll(model.state.value.trashed, true)
        settle(model) { it.state.value.selected.size == 2 }

        model.restoreSelected()
        settle(model) { it.state.value.trashed.size == 1 }

        assertEquals(listOf(two), model.state.value.stayedInTrash)
        assertEquals(setOf(two.path), model.state.value.selected)

        model.dismissStayedInTrash(listOf(two))
        settle(model) { it.state.value.stayedInTrash.isEmpty() }
    }

    @Test
    fun `undo of a page whose file now has another file at its path says it stayed in the trash`() {
        twoPages(more = 0)
        val trash = OccupiedTrash(emptyList())
        val model = ReviewViewModel(app, Review(app.index, trash))
        model.open(Category.BLURRY)
        settle(model) { paths(it).size == PAGE_SIZE }
        applyShown(model)
        settle(model) { it.state.value.lastDecision != null }
        val decision = model.state.value.lastDecision!!
        val taken = decision.trashed.first()
        trash.occupied = setOf(taken.path)

        model.undo(decision)
        settle(model) { it.state.value.lastDecision == null }

        assertEquals(listOf(taken), model.state.value.stayedInTrash)
        runBlocking {
            assertEquals(ReviewState.TRASHED, app.index.reviewState(taken.path))
            assertEquals(ReviewState.UNREVIEWED, app.index.reviewState(decision.trashed.last().path))
        }
    }

    @Test
    fun `restoring from the trash with an empty index brings the file back and does not crash`() {
        val volume = folder.newFolder()
        val trash = AppTrash({ listOf(volume) })
        val image = File(volume, "a.jpg").apply { writeText("0123456789") }.toImageRef()
        runBlocking { trash.moveToTrash(listOf(image)) } // the trash survived a reinstall, the index did not
        val model = ReviewViewModel(app, Review(app.index, trash))
        model.openTrash()
        settle(model) { it.state.value.trashed.size == 1 }
        model.select(model.state.value.trashed.single())
        settle(model) { it.state.value.selected.size == 1 }

        model.restoreSelected()
        settle(model) { it.state.value.trashed.isEmpty() }

        assertTrue(File(image.path).exists())
        assertEquals(false, model.state.value.changeFailed)
        runBlocking { assertEquals(ReviewState.UNREVIEWED, app.index.reviewState(image.path)) }
    }

    /** A trash that cannot be read, like a volume that went away. */
    private class BrokenTrash : Trash {
        override suspend fun moveToTrash(images: List<ImageRef>) = images

        override suspend fun restore(images: List<ImageRef>) = images

        override suspend fun trashed(): List<TrashedImage> = throw IOException("volume gone")

        override suspend fun purge(images: List<ImageRef>) = images

        override suspend fun purgeExpired() = emptyList<ImageRef>()
    }

    @Test
    fun `a change that fails stops the loading, reports an error and leaves the screen as it was`() {
        val model = ReviewViewModel(app, Review(app.index, BrokenTrash()))
        settle(model) { true }

        model.openTrash()
        settle(model) { it.state.value.changeFailed }

        assertEquals(false, model.state.value.loading)
        assertEquals(Screen.Home, model.state.value.screen)

        model.back() // later changes still run
        model.dismissChangeFailed()
        settle(model) { !it.state.value.changeFailed }
    }

    @Test
    fun `emptying the trash shows what the trash still holds`() {
        val one = ImageRef("/sdcard/one.jpg", 1_000, 1L)
        val two = ImageRef("/sdcard/two.jpg", 1_000, 1L)
        val model = ReviewViewModel(app, Review(app.index, StubbornTrash(listOf(one, two))))
        model.openTrash()
        settle(model) { it.state.value.trashed.size == 2 }
        model.select(model.state.value.trashed[0])

        model.emptyTrash()
        settle(model) { it.state.value.trashed.size == 1 }

        assertEquals(listOf(two), model.state.value.trashed.map { it.original })
        assertTrue(model.state.value.selected.isEmpty())
    }

    @Test
    fun `a settings reload queued behind Back does not pull the user back to settings`() {
        val slow = AtomicBoolean(false)
        val held = CountDownLatch(1)
        val review = Review(app.index, RecordingTrash(), thresholds = {
            if (slow.get()) held.await()
            THRESHOLDS
        })
        val model = ReviewViewModel(app, review)
        model.openSettings()
        settle(model) { it.state.value.screen == Screen.Settings }
        slow.set(true)

        model.back() // waits for the overview
        shadowOf(Looper.getMainLooper()).idle()
        model.reloadSettings() // a scan changed phase meanwhile
        held.countDown()
        settle(model) { it.state.value.screen == Screen.Home }
        Thread.sleep(200)
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(Screen.Home, model.state.value.screen)
    }

    @Test
    fun `the old snackbar timing out during the next apply keeps the undo of that apply`() {
        twoPages(more = PAGE_SIZE + 1)
        var gate: CompletableDeferred<Unit>? = null
        val trash = object : Trash by RecordingTrash() {
            override suspend fun moveToTrash(images: List<ImageRef>): List<ImageRef> {
                gate?.await()
                return images
            }
        }
        val model = ReviewViewModel(app, Review(app.index, trash))
        model.open(Category.BLURRY)
        settle(model) { paths(it).size == PAGE_SIZE }
        applyShown(model)
        settle(model) { it.state.value.lastDecision != null }
        val first = model.state.value.lastDecision!!
        gate = CompletableDeferred()

        applyShown(model)
        model.dismissUndo(first) // the first snackbar times out while the second apply runs
        gate!!.complete(Unit)
        settle(model) { it.state.value.lastDecision != first }
        Thread.sleep(200)
        shadowOf(Looper.getMainLooper()).idle()

        assertNotNull(model.state.value.lastDecision)
    }

    @Test
    fun `the old exclusion snackbar timing out during the next exclusion keeps the undo of that one`() {
        blurry("/sdcard/A/a.jpg", 0.9f)
        blurry("/sdcard/B/b.jpg", 0.8f)
        blurry("/sdcard/C/c.jpg", 0.7f)
        val slow = AtomicBoolean(false)
        val held = CountDownLatch(1)
        val review = Review(app.index, RecordingTrash(), thresholds = {
            if (slow.get()) held.await()
            THRESHOLDS
        })
        val model = ReviewViewModel(app, review)
        model.open(Category.BLURRY)
        settle(model) { paths(it).size == 3 }
        model.excludeFolder(ImageRef("/sdcard/A/a.jpg", 1_000, 1L))
        settle(model) { it.state.value.lastExclusion == "/sdcard/A" }
        slow.set(true)

        model.excludeFolder(ImageRef("/sdcard/B/b.jpg", 1_000, 1L)) // waits in the page read
        shadowOf(Looper.getMainLooper()).idle()
        model.dismissExclusion("/sdcard/A") // the first snackbar times out meanwhile
        held.countDown()
        settle(model) { it.state.value.lastExclusion != "/sdcard/A" }
        Thread.sleep(200)
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals("/sdcard/B", model.state.value.lastExclusion)
    }

    @Test
    fun `a thresholds reload queued behind another screen does not pull the user back to thresholds`() {
        val release = CompletableDeferred<Unit>()
        val model = ReviewViewModel(app, Review(app.index, SlowTrash(release)))
        model.openThresholds()
        settle(model) { it.state.value.screen == Screen.Thresholds }

        model.openTrash() // waits for the trash listing
        shadowOf(Looper.getMainLooper()).idle()
        model.reloadThresholds() // a scan changed phase meanwhile
        release.complete(Unit)
        settle(model) { it.state.value.screen == Screen.Trash }
        Thread.sleep(200)
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(Screen.Trash, model.state.value.screen)
    }
}
