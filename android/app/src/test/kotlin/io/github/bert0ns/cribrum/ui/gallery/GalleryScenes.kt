package io.github.bert0ns.cribrum.ui.gallery

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.click
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import io.github.bert0ns.cribrum.R
import io.github.bert0ns.cribrum.compress.BatchResult
import io.github.bert0ns.cribrum.compress.BatchState
import io.github.bert0ns.cribrum.compress.JpegInfo
import io.github.bert0ns.cribrum.compress.JpegStatus
import io.github.bert0ns.cribrum.compress.Losses
import io.github.bert0ns.cribrum.compress.Marker
import io.github.bert0ns.cribrum.compress.Refusal
import io.github.bert0ns.cribrum.model.Category
import io.github.bert0ns.cribrum.pipeline.Failure
import io.github.bert0ns.cribrum.pipeline.Indexing
import io.github.bert0ns.cribrum.pipeline.ScanProgress
import io.github.bert0ns.cribrum.pipeline.Stats
import io.github.bert0ns.cribrum.review.ReviewOrder
import io.github.bert0ns.cribrum.ui.ReviewViewModel
import io.github.bert0ns.cribrum.ui.Screen
import kotlinx.coroutines.runBlocking
import java.io.File

/** Read before the list: what a reviewer needs to know about how the images were made. */
const val NOTES = "Notes: every picture is drawn in code and the numbers are made up. " +
    "Paths in the full size view and on the map start with a temporary folder of the test, not the phone's, and its name changes at each image. " +
    "The test window has no status bar or gesture bar, so the top bar starts at the top edge. " +
    "A dialog sits at the top edge and is as wide as the screen, where the phone centers it with a margin on each side. " +
    "The dark images are the app in night mode, with its dark color scheme."

private fun Gallery.awaitModel(model: ReviewViewModel, what: String, condition: (ReviewViewModel) -> Boolean) = await(what) { !model.state.value.loading && condition(model) }

private fun Gallery.awaitHome(model: ReviewViewModel) = awaitModel(model, "home") { it.state.value.screen == Screen.Home && it.state.value.categories.isNotEmpty() }

private fun Gallery.openCategory(model: ReviewViewModel, name: Int) {
    awaitHome(model)
    click(str(name))
    awaitModel(model, "a page") { it.state.value.screen is Screen.Category && it.state.value.page.isNotEmpty() }
    awaitText(applyText(model))
}

/** The text of the button under the grid: how many images the page trashes and how many it keeps. */
private fun Gallery.applyText(model: ReviewViewModel): String {
    val state = model.state.value
    return str(R.string.apply, state.marked.size, state.page.sumOf { group -> group.images.count { it.suggestTrash && it.image.path !in state.marked } })
}

private fun Gallery.goTo(model: ReviewViewModel, destination: Int, ready: (ReviewViewModel) -> Boolean) {
    awaitHome(model)
    click(str(destination))
    awaitModel(model, "the destination", ready)
}

/** The map opens from Settings, which it belongs to. */
private fun Gallery.openMap(model: ReviewViewModel, ready: (ReviewViewModel) -> Boolean) {
    goTo(model, R.string.nav_settings) { it.state.value.screen == Screen.Settings }
    scrollTo(str(R.string.storage_map))
    click(str(R.string.storage_map))
    awaitModel(model, "the map", ready)
}

private fun Gallery.openTrash(model: ReviewViewModel) {
    awaitHome(model)
    clickDescription(str(R.string.trash))
    awaitModel(model, "the trash") { it.state.value.screen is Screen.Trash }
}

/** Keeps the tiles at [positions] of the marked ones, from the first, one after the other. */
private fun Gallery.keep(vararg positions: Int) {
    for (position in positions) described(str(R.string.selected_for_trash))[position].performClick()
}

/** Taps the button under the grid with a favourite marked, which asks first. */
private fun Gallery.applyWithFavorite(model: ReviewViewModel) {
    val trash = model.state.value.marked.size
    click(applyText(model))
    awaitText(plural(R.plurals.summary_title, trash, trash))
}

/** Taps the button under the grid, which applies the page at once (D6). */
private fun Gallery.confirmApply(model: ReviewViewModel) {
    click(applyText(model))
    awaitText(str(R.string.undo))
}

/** A camera roll for the Compress page: from a 200 MP shot to ordinary phone photos, and three that were compressed before. */
private fun Gallery.cameraRoll(): CompressSetup {
    val eligible = JpegInfo(JpegStatus.ELIGIBLE, quality = 92)
    fun marked(level: String) = JpegInfo(JpegStatus.ELIGIBLE, quality = 85, marker = Marker(1, level, "2026-10-21T20:04:00Z"))
    val photos = listOf(
        world.compressible("IMG_20261019_181144.jpg", 11, 24.1, 16320, 12240, JpegInfo(JpegStatus.ELIGIBLE, quality = 94, losses = GalleryWorld.reEdited)),
        world.compressible("IMG_20261011_120533.jpg", 12, 15.2, 8160, 6120, JpegInfo(JpegStatus.ELIGIBLE, quality = 93)),
        world.compressible("IMG_20260927_093208.jpg", 13, 14.7, 8160, 6120, JpegInfo(JpegStatus.ELIGIBLE, quality = 93, losses = Losses(xmp = true))),
        world.compressible("IMG_20261024_201457.jpg", 14, 9.8, 4624, 3472, eligible),
        world.compressible("IMG_20261003_150911.jpg", 15, 7.1, 4000, 3000, eligible),
        world.compressible("IMG_20261008_112340.jpg", 16, 6.8, 4000, 3000, eligible),
        world.compressible("IMG_20260921_174502.jpg", 17, 6.2, 4000, 3000, eligible),
        world.compressible("IMG_20261014_183422.jpg", 18, 5.9, 4000, 3000, eligible),
        world.compressible("IMG_20261030_082219.jpg", 19, 5.4, 4000, 3000, eligible),
        world.compressible("IMG_20260915_134008.jpg", 20, 4.8, 4000, 3000, eligible),
        world.compressible("IMG_20261001_192756.jpg", 21, 4.4, 4000, 3000, eligible),
        world.compressible("IMG_20260929_101133.jpg", 22, 4.1, 4000, 3000, eligible),
        world.compressible("IMG_20260918_163845.jpg", 23, 3.9, 4000, 3000, eligible),
        world.compressible("IMG_20261021_194201.jpg", 24, 3.1, 4000, 3000, marked("balanced")),
        world.compressible("IMG_20261021_194230.jpg", 25, 2.8, 4000, 3000, marked("high")),
        world.compressible("IMG_20261021_194302.jpg", 26, 1.4, 3000, 2250, marked("smallest")),
    )
    return CompressSetup(photos, favorites = setOf(photos[1].path, photos[7].path))
}

private fun Gallery.openCompress(model: ReviewViewModel, setup: CompressSetup) {
    goTo(model, R.string.nav_compress) { it.state.value.screen == Screen.Compress }
    if (setup.photos.isEmpty()) return
    awaitText(plural(R.plurals.compress_photos_heading, setup.photos.count { it.info.marker == null }, setup.photos.count { it.info.marker == null }))
    val first = setup.photos.firstOrNull { it.info.marker == null } ?: return
    await("the photos") { described(File(first.path).name).fetchSemanticsNodes().isNotEmpty() }
}

private fun Gallery.select(setup: CompressSetup, vararg positions: Int) {
    for (position in positions) described(File(setup.photos[position].path).name)[0].performClick()
}

private fun Gallery.summary(setup: CompressSetup, model: ReviewViewModel) {
    openCompress(model, setup)
    select(setup, 0, 1, 3)
    click(plural(R.plurals.compress_continue, 3, 3))
    awaitText(plural(R.plurals.compress_summary_title, 3, 3))
}

private fun Gallery.scanRunning(step: String, done: Int, total: Int, leftMs: Long? = null) {
    RunningScanWorker.progress = ScanProgress(step, done, total, leftMs)
    Indexing.scanNow(app)
    awaitNoText(str(R.string.scan_now))
}

/** 03:12 this morning, when the nightly scan ends. */
private fun lastNight(): Long = java.util.Calendar.getInstance().apply {
    set(java.util.Calendar.HOUR_OF_DAY, 3)
    set(java.util.Calendar.MINUTE, 12)
}.timeInMillis

private fun Gallery.settings(model: ReviewViewModel) {
    world.library(others = true)
    world.commit()
    runBlocking {
        app.index.excludeFolder(world.path("Pictures", "Memes"))
        app.index.excludeFolder(world.path("WhatsApp/Media", "WhatsApp Video"))
    }
    app.lastScan.record(
        Stats(images = 2_350, newOrChanged = 18, unreadable = 2, unreadablePaths = listOf("/sdcard/Download/broken.jpg", "/sdcard/Pictures/old.heic"))
            .also { it.failures += Failure("/sdcard/DCIM/Camera/IMG_20261014_183422.jpg", "blur", "OutOfMemoryError: bitmap too large") },
        finishedAt = System.currentTimeMillis() - 5 * 3_600_000L,
        durationMs = 412_000,
    )
    goTo(model, R.string.nav_settings) { it.state.value.screen == Screen.Settings && it.state.value.coverage != null && it.state.value.storage != null }
}

val scenes: List<Scene> = listOf(
    // Clean
    Scene("clean-results", "Clean, the home with a finished scan", "See how many images each category lists and how much they can free, and when the last scan ran. Open a category, scan again, open the trash from the top bar, or use the bar to go to Compress or Settings.", Variant.full) {
        world.library()
        world.commit()
        app.lastScan.record(Stats(images = 2_350), finishedAt = lastNight(), durationMs = 412_000)
        val model = app()
        awaitHome(model)
        awaitText(str(R.string.scan_last, ""), substring = true)
    },
    Scene("clean-first-run", "Clean, first run with nothing scanned", "Start the first scan with Scan now. The categories are empty until it ends.", Variant.base) {
        world.commit(scanned = false)
        val model = app()
        awaitModel(model, "an empty index") { it.state.value.indexEmpty }
        awaitText(str(R.string.scan_empty))
    },
    Scene("clean-first-run-notifications", "Clean, the first scan with notifications off", "Read the hint that scan progress notifications are off and open the system settings to turn them on, or watch the scan.", Variant.base) {
        world.commit(scanned = false)
        val model = app(notificationsGranted = false)
        awaitModel(model, "an empty index") { it.state.value.indexEmpty }
        scanRunning("scan", 1_230, 0)
        awaitText(str(R.string.notification_hint))
    },
    Scene("clean-scanning", "Clean, a scan running in its detector phase", "Watch the phase, the count and the time left. Open a category that already has candidates, or leave the app while the scan goes on.", Variant.wide) {
        world.library()
        world.commit()
        val model = app()
        awaitHome(model)
        scanRunning("detectors", 4_320, 10_220, 11 * 60_000L)
    },
    Scene("clean-scanning-first", "Clean, the first scan still finding files", "Watch the count of images found so far. Nothing can be reviewed yet.", Variant.base) {
        world.commit(scanned = false)
        val model = app()
        awaitModel(model, "an empty index") { it.state.value.indexEmpty }
        scanRunning("scan", 1_230, 0)
    },
    // Review
    Scene("review-near-duplicates", "Review of Similar copies, groups with their best copy", "Compare each best copy with its copies, tap a photo to flip its mark, hold one to see it full size, trash the marked ones or skip the page.", Variant.base) {
        world.library(pictures = setOf(Category.NEAR_DUPLICATE))
        world.commit()
        val model = app()
        openCategory(model, R.string.category_near_duplicate)
    },
    Scene("review-blurry", "Review of Blurry photos, three kept", "Tap a photo to keep it or mark it for the trash, hold one to see it full size, trash the marked ones or skip the page.", Variant.full) {
        world.library(pictures = setOf(Category.BLURRY))
        world.commit()
        val model = app()
        openCategory(model, R.string.category_blurry)
        keep(2, 5, 8)
    },
    Scene("review-screenshots", "Review of Screenshots", "Tap a screenshot to keep it or mark it for the trash, hold one to see it full size, trash the marked ones or skip the page.", Variant.base) {
        world.library(pictures = setOf(Category.SCREENSHOT))
        world.commit()
        val model = app()
        openCategory(model, R.string.category_screenshot)
    },
    Scene("review-order-menu", "Review with the date order menu open", "Choose how the page is ordered: off, by day, by week, by month or largest first.", Variant.base) {
        world.library(pictures = setOf(Category.BLURRY))
        world.commit()
        val model = app()
        openCategory(model, R.string.category_blurry)
        compose.onNodeWithContentDescription(str(R.string.review_order)).performClick()
        awaitText(str(R.string.review_order_week))
    },
    Scene("review-by-day", "Review of Blurry photos grouped by day", "Read the photos under their dates, newest first, and review them as usual.", Variant.base) {
        world.library(pictures = setOf(Category.BLURRY))
        world.commit()
        val model = app()
        openCategory(model, R.string.category_blurry)
        compose.onNodeWithContentDescription(str(R.string.review_order)).performClick()
        click(str(R.string.review_order_day))
        awaitModel(model, "the day order") { it.state.value.reviewOrder == ReviewOrder.DAY && it.state.value.page.isNotEmpty() }
    },
    Scene("review-full-size", "Review, a photo at full size with why it is a candidate", "Swipe to the next photo, pinch to zoom, tick or untick the checkbox that marks it for the trash, read its folder, never suggest the folder again, or close the view.", Variant.base) {
        world.library(pictures = setOf(Category.BLURRY))
        world.commit()
        val model = app()
        openCategory(model, R.string.category_blurry)
        described(str(R.string.selected_for_trash))[1].performTouchInput { longClick() }
        awaitTag("fullSize")
    },
    Scene("review-favorites", "Review of Too dark or too bright photos, favourites kept at the end", "Find the gallery favourites, which start kept with a heart, and decide whether to trash them anyway.", Variant.base) {
        world.library(pictures = setOf(Category.BAD_EXPOSURE))
        world.commit()
        val model = app()
        openCategory(model, R.string.category_bad_exposure)
        scrollToEnd()
    },
    Scene("review-summary-favorite", "Review, the confirmation when a favourite is marked", "Read the warning that a favourite goes to the trash, then confirm with the stronger button or cancel.", Variant.base) {
        world.library(pictures = setOf(Category.BAD_EXPOSURE))
        world.commit()
        val model = app()
        openCategory(model, R.string.category_bad_exposure)
        scrollToEnd()
        described(str(R.string.favorite))[0].performClick()
        applyWithFavorite(model)
    },
    Scene("review-snackbar-undo", "Review, the next page with the Undo snackbar right after tapping the trash button", "Read how many images went to the trash and how much space they take, undo the last page from the snackbar, or go on with the next page.", Variant.base) {
        world.library(pictures = setOf(Category.BLURRY))
        world.commit()
        val model = app()
        openCategory(model, R.string.category_blurry)
        confirmApply(model)
    },
    Scene("review-favorite-appeared", "Review, the page back after a photo on it became a favourite", "Read why nothing went to the trash, find the new heart, then trash the page again or keep the favourite.", Variant.base) {
        world.library(pictures = setOf(Category.BLURRY))
        world.commit()
        val model = app()
        openCategory(model, R.string.category_blurry)
        world.favorites += model.state.value.marked.first() // hearted in the gallery while the page was open
        click(applyText(model))
        awaitText(str(R.string.favorite_appeared_snackbar))
    },
    Scene("review-excluded-snackbar", "Review, after excluding a photo's folder", "Undo the exclusion from the snackbar. The photos of that folder left the page.", Variant.base) {
        world.library(pictures = setOf(Category.BLURRY))
        world.commit()
        val model = app()
        openCategory(model, R.string.category_blurry)
        described(str(R.string.selected_for_trash))[0].performTouchInput { longClick() }
        awaitTag("fullSize")
        click(str(R.string.exclude_folder))
        awaitText(str(R.string.undo))
    },
    // Trash
    Scene("trash-empty", "Trash, empty", "Read that the trash is empty and go back.", Variant.base) {
        world.commit(scanned = false)
        val model = app()
        openTrash(model)
        awaitText(str(R.string.trash_empty))
    },
    Scene("trash-items", "Trash with photos, screenshots and stickers", "Select photos to bring them back, select all, hold one to see it full size, empty the trash, or go back. The days left count down to the automatic deletion.", Variant.full) {
        val model = app(trashed(this))
        openTrash(model)
        awaitModel(model, "the items") { it.state.value.trashed.isNotEmpty() }
    },
    Scene("trash-selected", "Trash with three photos selected", "Restore the selected photos to their folders, delete only those for good, or select more.", Variant.base) {
        val model = app(trashed(this))
        openTrash(model)
        awaitModel(model, "the items") { it.state.value.trashed.isNotEmpty() }
        for (position in listOf(0, 2, 5)) described("IMG_")[position].performClick()
        awaitText(str(R.string.restore, 3))
    },
    Scene("trash-empty-confirm", "Trash, the confirmation before emptying it", "Read how much space deleting frees and that it is permanent, then delete everything or cancel.", Variant.base) {
        val model = app(trashed(this))
        openTrash(model)
        awaitModel(model, "the items") { it.state.value.trashed.isNotEmpty() }
        click(str(R.string.empty_trash))
        val count = model.state.value.trashed.size
        awaitText(plural(R.plurals.empty_trash_title, count, count))
    },
    Scene("trash-full-size", "Trash, a photo at full size", "Look at a trashed photo before restoring it or deleting it for good, read its folder and its days left, swipe to the next one, or close the view.", Variant.base) {
        val model = app(trashed(this))
        openTrash(model)
        awaitModel(model, "the items") { it.state.value.trashed.isNotEmpty() }
        described("IMG_")[0].performTouchInput { longClick() }
        awaitTag("fullSize")
    },
    // Compress
    Scene("compress-empty", "Compress, nothing to compress", "Read why the list is empty, or open the sheet of what can be compressed.", Variant.base) {
        world.commit(scanned = false)
        val setup = CompressSetup()
        val model = app(compress = setup)
        openCompress(model, setup)
        awaitText(str(R.string.compress_none))
    },
    Scene("compress-list", "Compress, the list with nothing selected", "Pick photos, change the level, open what can be compressed from the info icon, see the photos compressed before, or open the trash from the top bar.", Variant.full) {
        val setup = cameraRoll()
        val model = app(compress = setup)
        openCompress(model, setup)
    },
    Scene("compress-selected", "Compress, three photos selected with the Continue button", "Change the level to see the saving, select more or fewer photos, or continue to the summary.", Variant.wide) {
        val setup = cameraRoll()
        val model = app(compress = setup)
        openCompress(model, setup)
        select(setup, 0, 1, 3)
        awaitText(plural(R.plurals.compress_continue, 3, 3))
    },
    Scene("compress-formats", "Compress, the sheet about supported formats", "Read the two short lists of what is compressed and what is not. Close the sheet to go on.", Variant.base) {
        val setup = cameraRoll()
        val model = app(compress = setup)
        openCompress(model, setup)
        clickDescription(str(R.string.compress_formats_open))
        awaitText(str(R.string.compress_sheet_title))
    },
    Scene("compress-summary", "Compress, the summary with the level and the before and after slider", "Change the level and see the previews made again, drag the line to compare the original and the compressed copy, pinch to zoom, read the saving, then compress or cancel.", Variant.full) {
        val setup = cameraRoll()
        summary(setup, app(compress = setup))
    },
    Scene("compress-summary-losses", "Compress, the summary scrolled to what the copy loses", "Read what the compressed copy loses and how long the originals stay in the trash, then compress or cancel.", Variant.base) {
        val setup = cameraRoll()
        summary(setup, app(compress = setup))
        scrollToEnd(inDialog = true)
    },
    Scene("compress-full-size", "Compress, a photo at full size from the list", "Swipe through the photos of the page, zoom to the full resolution, select or deselect this photo, or tap to close.", Variant.base) {
        val setup = cameraRoll()
        val model = app(compress = setup)
        openCompress(model, setup)
        described(File(setup.photos[0].path).name)[0].performTouchInput { longClick() }
        awaitTag("fullSize")
    },
    Scene("compress-running", "Compress, a batch running", "Watch how many photos are done and how much space they saved so far, or stop after the photo in progress.", Variant.base) {
        val setup = CompressSetup(cameraRoll().photos, GalleryBatches(BatchState.Running(done = 4, total = 12, savedBytes = 41_300_000)))
        val model = app(compress = setup)
        goTo(model, R.string.nav_compress) { it.state.value.screen == Screen.Compress }
        awaitText(str(R.string.compress_screen_cancel))
    },
    Scene("compress-waiting-scan", "Compress, a batch waiting for the scan to end", "Read that the batch starts when the scan ends and which phase the scan is in, or cancel it.", Variant.base) {
        val setup = CompressSetup(cameraRoll().photos, GalleryBatches(BatchState.WaitingForScan(total = 12)))
        val model = app(compress = setup)
        goTo(model, R.string.nav_compress) { it.state.value.screen == Screen.Compress }
        RunningScanWorker.progress = ScanProgress("embeddings", 840, 1_900, 4 * 60_000L)
        Indexing.scanNow(app)
        awaitText(str(R.string.compress_waiting_scan))
        awaitText(str(R.string.scan_phase_embeddings), substring = true)
    },
    Scene("compress-result", "Compress, the result of a batch with Undo", "Read how many photos were compressed and how much space they saved, close the result with Done, or undo the batch to bring the originals back.", Variant.wide) {
        val roll = cameraRoll()
        val result = BatchResult(12, 10, mapOf(Refusal.NOT_SMALLER to 2), 96_400_000, emptyList())
        val model = app(compress = CompressSetup(roll.photos, GalleryBatches(BatchState.Finished(result))))
        goTo(model, R.string.nav_compress) { it.state.value.screen == Screen.Compress }
        awaitText(str(R.string.compress_result_done))
    },
    Scene("compress-result-partial", "Compress, a batch that stopped early with refusals", "Read why some photos were left as they are, how many were not reached, and how many favourites lost their mark, then undo or close.", Variant.base) {
        val roll = cameraRoll()
        val result = BatchResult(12, 6, mapOf(Refusal.NOT_SMALLER to 2, Refusal.LOW_MEMORY to 1, Refusal.GAIN_MAP_LOST to 1), 52_100_000, emptyList())
        val model = app(compress = CompressSetup(roll.photos, GalleryBatches(BatchState.Finished(result, favoritesLost = 2))))
        goTo(model, R.string.nav_compress) { it.state.value.screen == Screen.Compress }
        awaitText(str(R.string.compress_result_done))
    },
    Scene("compress-compressed-before", "Compress, the photos compressed before, expanded", "Read which level each photo was compressed at, select one to compress it again, or collapse the section.", Variant.base) {
        val setup = cameraRoll()
        val model = app(compress = setup)
        openCompress(model, setup)
        val heading = plural(R.plurals.compress_already_heading, 3, 3)
        scrollTo(heading)
        click(heading)
        awaitText(str(R.string.compress_already_hint))
        scrollToEnd()
    },
    // Map
    Scene("map-root", "Storage map, opened from Settings, the treemap of the whole library", "Read the names on the larger folders, tap a folder to select it and read its size, open it, or go back to Settings.", Variant.full) {
        world.library(others = true)
        world.commit()
        val model = app()
        openMap(model) { it.state.value.screen == Screen.StorageMap && it.state.value.storageMap.isNotEmpty() }
    },
    Scene("map-zoomed", "Storage map, zoomed into Pictures", "Go back to the whole map from the top bar, tap a subfolder to select it, or zoom further.", Variant.base) {
        world.library(others = true)
        world.commit()
        val model = app()
        openMap(model) { it.state.value.screen == Screen.StorageMap && it.state.value.storageMap.isNotEmpty() }
        click("Pictures")
        awaitModel(model, "the zoom") { it.state.value.storageMap.size > 1 }
    },
    Scene("map-selected", "Storage map, a folder selected with its details", "Read the folder's name, size and count, open it with Open folder, or exclude it from the review and the map.", Variant.base + Variant.italianDark + Variant.italianLargeText) {
        world.library(others = true)
        world.commit()
        val model = app()
        openMap(model) { it.state.value.screen == Screen.StorageMap && it.state.value.storageMap.isNotEmpty() }
        compose.onNodeWithTag("storage_map").performTouchInput { click(Offset(60f, 60f)) }
        awaitText(str(R.string.storage_map_exclude))
    },
    Scene("map-excluded-snackbar", "Storage map, after excluding a folder", "Undo the exclusion from the snackbar. The map starts again from its root without the folder.", Variant.base) {
        world.library(others = true)
        world.commit()
        val model = app()
        openMap(model) { it.state.value.screen == Screen.StorageMap && it.state.value.storageMap.isNotEmpty() }
        compose.onNodeWithTag("storage_map").performTouchInput { click(Offset(60f, 60f)) }
        click(str(R.string.storage_map_exclude))
        awaitText(str(R.string.undo))
    },
    Scene("map-empty", "Storage map, nothing found yet", "Read that no images were found yet. Scan from Clean to fill the map.", Variant.base) {
        world.commit(scanned = false)
        val model = app()
        openMap(model) { it.state.value.screen == Screen.StorageMap }
        awaitText(str(R.string.storage_map_empty))
    },
    // Settings
    Scene("settings-top", "Settings, the top: what the user changes", "Choose how long the trash keeps images, remove an excluded folder, open the storage map or the detection thresholds, or turn the haptics off.", Variant.full) {
        settings(app())
    },
    Scene("settings-bottom", "Settings, scrolled to the end", "Read the storage the app takes, delete the analysis data, or read the version and open the website or the source.", Variant.base) {
        settings(app())
        scrollToEnd()
    },
    Scene("settings-scan-status", "Settings, the scans and storage", "Read what the last scan did, how long it took, and how many images each step analysed.", Variant.base) {
        settings(app())
        scrollTo(str(R.string.settings_scan_status_hint))
    },
    Scene("settings-retention-menu", "Settings, the choices of how long the trash keeps images", "Choose 7, 14, 30, 60 or 90 days.", Variant.base) {
        settings(app())
        scrollTo(str(R.string.settings_trash_retention))
        click(str(R.string.settings_trash_retention))
        awaitText(plural(R.plurals.settings_trash_days, 7, 7))
    },
    Scene("settings-retention-confirm", "Settings, the confirmation before a shorter retention deletes trashed photos", "Read how many trashed photos the new retention deletes at the next scan, then delete them or cancel.", Variant.base) {
        val model = app(trashed(this))
        settings(model)
        scrollTo(str(R.string.settings_trash_retention))
        click(str(R.string.settings_trash_retention))
        click(plural(R.plurals.settings_trash_days, 7, 7))
        awaitModel(model, "the question") { it.state.value.pendingRetention != null }
    },
    Scene("settings-delete-embeddings", "Settings, the confirmation before deleting the analysis data", "Read that the analysis data comes back at the next scan, then delete it or cancel.", Variant.base) {
        settings(app())
        scrollTo(str(R.string.settings_delete_embeddings))
        click(str(R.string.settings_delete_embeddings))
        awaitText(str(R.string.settings_delete_embeddings_confirm))
    },
    Scene("thresholds", "Detection thresholds", "Move a slider to list more or fewer images in a category, read how many that adds or removes, or reset to the defaults.", Variant.full) {
        world.library()
        world.commit()
        val model = app()
        goTo(model, R.string.nav_settings) { it.state.value.screen == Screen.Settings }
        scrollTo(str(R.string.settings_thresholds))
        click(str(R.string.settings_thresholds))
        awaitModel(model, "the thresholds") { it.state.value.screen == Screen.Thresholds && it.state.value.thresholdCounts.isNotEmpty() }
    },
    // Permissions
    Scene("permission-all-files", "First run, the screen that asks for All files access", "Open the system settings to grant All files access. Nothing else in the app works before that.", Variant.wide) {
        launchMainActivity(allFilesAccess = false)
        awaitText(str(R.string.access_title))
    },
)

/** Photos, screenshots and stickers in the trash, trashed between one and 25 days ago, with the app on the empty home. */
private fun trashed(gallery: Gallery): ReviewViewModel = with(gallery) {
    val photos = (0 until 12).map { world.picture(CAMERA, cameraName(taken(it)), GalleryPictures.scene(5_000 + it), 3_500_000L + it * 90_000L, taken(it)) }
    val shots = (0 until 3).map { world.picture(SCREENSHOTS, "Screenshot_2026102$it-1${it}2030.png", GalleryPictures.screenshot(5_100 + it), 1_800_000L, taken(it)) }
    val stickers = (0 until 4).map { world.picture(STICKERS, "${500_000 + it}-$it.webp", GalleryPictures.sticker(5_200 + it), 120_000L, taken(it)) }
    world.commit(scanned = false)
    world.trashed(photos + shots + stickers) { 1L + it * 2 }
    return world.model()
}
