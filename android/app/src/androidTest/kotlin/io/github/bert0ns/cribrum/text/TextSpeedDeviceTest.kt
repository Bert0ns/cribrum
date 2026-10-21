package io.github.bert0ns.cribrum.text

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import io.github.bert0ns.cribrum.millis
import io.github.bert0ns.cribrum.saveNoise
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.system.measureNanoTime

/**
 * How long text coverage takes per image on the phone (docs/design.md §5). The numbers go to
 * logcat under the tag "cribrum-speed". The detector's cost depends on the image's size, not on
 * what is in it, so noise stands in for photos.
 */
class TextSpeedDeviceTest {
    private val folder = File(ApplicationProvider.getApplicationContext<Context>().cacheDir, "text-speed-test").apply { mkdirs() }

    @After
    fun cleanUp() {
        folder.deleteRecursively()
    }

    private fun images() = listOf(
        saveNoise(folder, "camera.jpg", 4000, 3000, Bitmap.CompressFormat.JPEG),
        saveNoise(folder, "whatsapp.jpg", 1600, 1200, Bitmap.CompressFormat.JPEG),
        saveNoise(folder, "screenshot.png", 1080, 2400, Bitmap.CompressFormat.PNG),
    )

    @Test
    fun reportsMillisecondsPerImage() {
        val images = images()
        val rounds = 5
        for (image in images) {
            preprocess(image)
            val nanos = measureNanoTime { repeat(rounds) { preprocess(image) } }
            Log.i(TAG, "text preprocess ${image.name}: ${millis(nanos, rounds)} ms")
        }
        val load = measureNanoTime { textCoverage(images[0]) }
        Log.i(TAG, "text model load and first image: ${millis(load, 1)} ms")
        for (image in images) {
            var coverage = -1f
            val nanos = measureNanoTime { repeat(rounds) { coverage = textCoverage(image) } }
            Log.i(TAG, "text coverage ${image.name}: ${millis(nanos, rounds)} ms")
            assertTrue("${image.name}: $coverage", coverage in 0f..1f)
        }
    }

    /** The scan runs one image per core at once, sharing the model. */
    @Test
    fun reportsImagesPerSecondOnEveryCore() {
        val images = images()
        val threads = Runtime.getRuntime().availableProcessors()
        val perThread = 6
        textCoverage(images[0]) // loads the model
        val coverages = ConcurrentLinkedQueue<Float>()
        val nanos = measureNanoTime {
            List(threads) { t -> Thread { repeat(perThread) { coverages += textCoverage(images[(t + it) % images.size]) } }.apply(Thread::start) }.forEach(Thread::join)
        }
        Log.i(TAG, "text coverage on $threads threads: %.1f images per second".format(threads * perThread / (nanos / 1e9)))
        assertTrue(coverages.toString(), coverages.size == threads * perThread && coverages.all { it in 0f..1f })
    }

    private companion object {
        const val TAG = "cribrum-speed"
    }
}
