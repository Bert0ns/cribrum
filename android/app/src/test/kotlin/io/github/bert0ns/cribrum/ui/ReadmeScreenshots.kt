package io.github.bert0ns.cribrum.ui

import android.database.SQLException
import android.graphics.Bitmap
import androidx.compose.material3.Text
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import io.github.bert0ns.cribrum.CribrumApp
import io.github.bert0ns.cribrum.ui.gallery.Gallery
import io.github.bert0ns.cribrum.ui.gallery.GalleryWorkerFactory
import io.github.bert0ns.cribrum.ui.gallery.GalleryWorld
import io.github.bert0ns.cribrum.ui.gallery.MovableClock
import io.github.bert0ns.cribrum.ui.gallery.scenes
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.junit.runners.model.Statement
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.Instant

/**
 * Renders the screens for the README and the website (docs/screenshots) on the JVM, driving the real
 * app over a drawn library (android/README.md). Every image is drawn in code, so no photo from a device
 * is involved. It runs only when `-PreadmeScreenshots` is set.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "en-w411dp-h891dp-notnight-mdpi", application = CribrumApp::class)
class ReadmeScreenshots {
    private var written = false

    // WorkManager's background threads can race test teardown against Room after the screenshot is written.
    @get:Rule(order = 0)
    val workManagerTeardown = TestRule { base, _ ->
        object : Statement() {
            override fun evaluate() {
                try {
                    base.evaluate()
                } catch (e: Throwable) {
                    val closed = generateSequence(e) { it.cause }.any { cause ->
                        cause is SQLException &&
                            cause.message.orEmpty().contains("connection is closed") &&
                            cause.stackTrace.any { it.className.startsWith("androidx.work.") }
                    }
                    if (!written || !closed) throw e
                }
            }
        }
    }

    @get:Rule(order = 1)
    val compose = createEmptyComposeRule()

    @get:Rule(order = 2)
    val folder = TemporaryFolder()

    private val outputDirs: List<File> by lazy {
        val dirsProperty = System.getProperty("cribrum.screenshots.dirs")
        if (!dirsProperty.isNullOrBlank()) {
            dirsProperty.split(",").map { File(it.trim()) }
        } else {
            listOfNotNull(System.getProperty("cribrum.screenshots.dir")?.let(::File))
        }
    }

    @Test
    fun home() = render("clean-results", "home.png")

    @Test
    fun category() = render("review-blurry", "category.png")

    @Test
    fun group() = render("review-near-duplicates", "group.png")

    @Test
    fun fullSize() = render("review-full-size", "full-size.png")

    @Test
    fun compress() = render("compress-selected", "compress.png")

    @Test
    fun compressSummary() = render("compress-summary", "compress-summary.png")

    @Test
    fun trash() = render("trash-items", "trash.png")

    @Test
    fun settings() = render("settings-top", "settings.png")

    @Test
    fun storageMap() = render("map-root", "storage-map.png")

    private fun render(sceneId: String, fileName: String) {
        assumeTrue("set -PreadmeScreenshots to write the screenshots", outputDirs.isNotEmpty())
        RuntimeEnvironment.setQualifiers("en-w411dp-h891dp-notnight-mdpi")
        val app = ApplicationProvider.getApplicationContext<CribrumApp>()
        WorkManagerTestInitHelper.initializeTestWorkManager(
            app,
            Configuration.Builder().setExecutor(SynchronousExecutor()).setWorkerFactory(GalleryWorkerFactory()).build(),
        )
        val clock = MovableClock(Instant.parse("2026-11-04T12:00:00Z"))
        val gallery = Gallery(compose, app, GalleryWorld(app, folder.newFolder("sdcard"), clock = clock))
        try {
            prime(gallery)
            val scene = checkNotNull(scenes.firstOrNull { it.id == sceneId }) { "scene $sceneId not found" }
            scene.run(gallery)
            gallery.settle()
            val bitmap = gallery.capture()
            for (dir in outputDirs) {
                dir.mkdirs()
                File(dir, fileName).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            }
            written = true
        } finally {
            gallery.close()
        }
    }

    /** Sets up native graphics so dialogs and complex views render safely. */
    private fun prime(gallery: Gallery) {
        gallery.launch { Text("Cribrum") }
        gallery.capture()
        gallery.close()
    }
}
