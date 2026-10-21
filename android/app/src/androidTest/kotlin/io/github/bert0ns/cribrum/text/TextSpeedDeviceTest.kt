package io.github.bert0ns.cribrum.text

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Test
import java.io.File
import kotlin.random.Random
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

    private fun save(name: String, width: Int, height: Int, format: Bitmap.CompressFormat): File {
        val random = Random(width)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val row = IntArray(width)
        for (y in 0 until height) {
            if (y % 8 == 0) for (x in 0 until width step 8) row.fill(0xff000000.toInt() or random.nextInt(0xffffff), x, minOf(x + 8, width))
            bitmap.setPixels(row, 0, width, 0, y, width, 1)
        }
        return File(folder, name).also { file -> file.outputStream().use { bitmap.compress(format, 90, it) } }
    }

    private fun millis(nanos: Long, count: Int) = "%.1f".format(nanos / 1e6 / count)

    @Test
    fun reportsMillisecondsPerImage() {
        val images = listOf(
            save("camera.jpg", 4000, 3000, Bitmap.CompressFormat.JPEG),
            save("whatsapp.jpg", 1600, 1200, Bitmap.CompressFormat.JPEG),
            save("screenshot.png", 1080, 2400, Bitmap.CompressFormat.PNG),
        )
        val rounds = 5
        for (image in images) {
            preprocess(image)
            val nanos = measureNanoTime { repeat(rounds) { preprocess(image) } }
            Log.i(TAG, "text preprocess ${image.name}: ${millis(nanos, rounds)} ms")
        }
        val load = measureNanoTime { textCoverage(images[0]) }
        Log.i(TAG, "text model load and first image: ${millis(load, 1)} ms")
        for (image in images) {
            val nanos = measureNanoTime { repeat(rounds) { textCoverage(image) } }
            Log.i(TAG, "text coverage ${image.name}: ${millis(nanos, rounds)} ms")
        }
    }

    private companion object {
        const val TAG = "cribrum-speed"
    }
}
