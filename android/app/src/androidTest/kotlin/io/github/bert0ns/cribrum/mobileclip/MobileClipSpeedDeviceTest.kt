package io.github.bert0ns.cribrum.mobileclip

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import io.github.bert0ns.cribrum.runtime.OnnxRuntime
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import kotlin.random.Random
import kotlin.system.measureNanoTime

/**
 * How long MobileCLIP2-S0 takes per image on the phone (docs/design.md §5). The numbers go to
 * logcat under the tag "cribrum-speed".
 */
class MobileClipSpeedDeviceTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val folder = File(context.cacheDir, "speed-test").apply { mkdirs() }

    @After
    fun cleanUp() {
        folder.deleteRecursively()
    }

    /** Noise in blocks, so the encoders can't make the files trivially small. */
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
            Log.i(TAG, "preprocess ${image.name}: ${millis(nanos, rounds)} ms")
        }

        val model = MobileClip { OnnxRuntime(context.assets.open(VISION_MODEL_ASSET).use { it.readBytes() }) }
        val load = measureNanoTime { model.embedPixels(listOf(preprocess(images[0]))) }
        Log.i(TAG, "model load and first image: ${millis(load, 1)} ms")
        for (batch in listOf(1, 16)) {
            val pixels = List(batch) { preprocess(images[it % images.size]) }
            lateinit var vectors: List<FloatArray>
            val nanos = measureNanoTime { repeat(rounds) { vectors = model.embedPixels(pixels) } }
            Log.i(TAG, "inference, batch of $batch: ${millis(nanos, rounds * batch)} ms per image")
            assertEquals(batch, vectors.size)
        }
    }

    private companion object {
        const val TAG = "cribrum-speed"
    }
}
