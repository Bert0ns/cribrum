package io.github.bert0ns.cribrum.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import io.github.bert0ns.cribrum.model.Category
import io.github.bert0ns.cribrum.model.ImageRef
import io.github.bert0ns.cribrum.review.CategorySummary
import io.github.bert0ns.cribrum.review.ReviewGroup
import io.github.bert0ns.cribrum.review.ReviewItem
import io.github.bert0ns.cribrum.trash.TrashedImage
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlin.random.Random

/**
 * Renders the screens in the README (docs/screenshots) on the JVM, with the real composables and
 * theme. Every image is drawn here, so no photo is involved. It writes only when
 * `-PreadmeScreenshots` is set (android/README.md), so a normal test run leaves the files alone.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w411dp-h891dp-mdpi", application = android.app.Application::class)
class ReadmeScreenshots {
    @get:Rule
    val compose = createComposeRule()

    @get:Rule
    val folder = TemporaryFolder()

    private val outputDir = System.getProperty("cribrum.screenshots.dir")?.let(::File)

    @Test
    fun home() = screenshot("home", title = "Cribrum", settings = true) {
        val categories = listOf(
            CategorySummary(Category.EXACT_DUPLICATE, 42, bytes = 188_000_000),
            CategorySummary(Category.NEAR_DUPLICATE, 217, bytes = 640_000_000),
            CategorySummary(Category.BURST, 64, bytes = 210_000_000),
            CategorySummary(Category.BLURRY, 128, bytes = 412_000_000),
            CategorySummary(Category.BAD_EXPOSURE, 31, bytes = 96_000_000),
            CategorySummary(Category.SCREENSHOT, 305, bytes = 530_000_000),
            CategorySummary(Category.DOCUMENT, 89, bytes = 270_000_000),
            CategorySummary(Category.FORWARDED, 412, bytes = 350_000_000),
            CategorySummary(Category.STICKER, 1_024, bytes = 120_000_000),
        )
        Column(Modifier.fillMaxSize()) {
            ScanStatus(null, onScan = {})
            CategoryList(categories, freeableBytes = 2_100_000_000, onOpen = {}, onTrash = {})
        }
    }

    @Test
    fun category() = screenshot("category", title = "Blurry", back = true) {
        val images = (1..24).map { thumbnail("blurry", it, blurry = true) }
        val page = images.map { ReviewGroup(it.path, listOf(ReviewItem(it, suggestTrash = true))) }
        val kept = setOf(images[2].path, images[7].path, images[12].path, images[17].path)
        CategoryScreen(page, 128, marked = images.map { it.path }.toSet() - kept, grouped = false, onToggle = {}, onApply = { _, _ -> }, onSkip = {})
    }

    @Test
    fun trash() = screenshot("trash", title = "Trash", back = true) {
        val now = Instant.now()
        val trashed = (1..12).map { index ->
            val image = thumbnail("trash", index, blurry = index % 2 == 0)
            TrashedImage(image, now.minus(index.toLong(), ChronoUnit.DAYS), File(image.path), now.plus(30L - index, ChronoUnit.DAYS))
        }
        TrashScreen(trashed, selected = setOf(trashed[1].original.path, trashed[4].original.path), onSelect = {}, onSelectAll = { _, _ -> }, onRestore = {}, onEmpty = {})
    }

    @OptIn(ExperimentalMaterial3Api::class)
    private fun screenshot(name: String, title: String, back: Boolean = false, settings: Boolean = false, content: @Composable () -> Unit) {
        assumeTrue("set -PreadmeScreenshots to write the screenshots", outputDir != null)
        compose.setContent {
            MaterialTheme {
                Scaffold(topBar = {
                    TopAppBar(
                        title = { Text(title) },
                        navigationIcon = { if (back) IconButton({}) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                        actions = { if (settings) IconButton({}) { Icon(Icons.Filled.Settings, "Settings") } },
                    )
                }) { padding -> Column(Modifier.fillMaxSize().padding(padding)) { content() } }
            }
        }
        // Coil decodes the thumbnails off the main thread, and Compose does not wait for it.
        repeat(20) {
            compose.waitForIdle()
            Thread.sleep(100)
        }
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        outputDir!!.mkdirs()
        File(outputDir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    /** A drawn picture: a gradient with a few shapes. The blurry ones are soft blobs. Nothing is a photo. */
    private fun thumbnail(set: String, index: Int, blurry: Boolean): ImageRef {
        val random = Random(index * 31 + set.length)
        val size = 140
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val hue = random.nextFloat() * 360f
        val sky = Color.HSVToColor(floatArrayOf(hue, 0.45f, 0.95f))
        val ground = Color.HSVToColor(floatArrayOf((hue + 40f) % 360f, 0.6f, 0.55f))
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.shader = LinearGradient(0f, 0f, 0f, size.toFloat(), sky, ground, Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, size.toFloat(), size.toFloat(), paint)
        repeat(3) {
            val x = random.nextFloat() * size
            val y = size * (0.3f + random.nextFloat() * 0.6f)
            val radius = size * (0.12f + random.nextFloat() * 0.18f)
            val color = Color.HSVToColor(floatArrayOf(random.nextFloat() * 360f, 0.7f, 0.9f))
            if (blurry) {
                paint.shader = RadialGradient(x, y, radius * 1.8f, color, Color.TRANSPARENT, Shader.TileMode.CLAMP)
                canvas.drawCircle(x, y, radius * 1.8f, paint)
            } else {
                paint.shader = null
                paint.color = color
                canvas.drawCircle(x, y, radius, paint)
            }
        }
        val file = folder.newFile("$set$index.png")
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return ImageRef(file.path, file.length(), 0)
    }
}
