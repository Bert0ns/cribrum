// Helpers shared by the speed tests on the phone.
package io.github.bert0ns.cribrum

import android.graphics.Bitmap
import java.io.File
import kotlin.random.Random

/** Noise in blocks, so the encoders can't make the files trivially small. */
fun saveNoise(folder: File, name: String, width: Int, height: Int, format: Bitmap.CompressFormat): File {
    val random = Random(width)
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val row = IntArray(width)
    for (y in 0 until height) {
        if (y % 8 == 0) for (x in 0 until width step 8) row.fill(0xff000000.toInt() or random.nextInt(0xffffff), x, minOf(x + 8, width))
        bitmap.setPixels(row, 0, width, 0, y, width, 1)
    }
    return File(folder, name).also { file -> file.outputStream().use { bitmap.compress(format, 90, it) } }
}

fun millis(nanos: Long, count: Int) = "%.1f".format(nanos / 1e6 / count)
