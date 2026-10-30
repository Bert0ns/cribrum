// Only in the build that generates the Baseline Profile (android/README.md), which has no storage
// access and must show the home and a category all the same.
package io.github.bert0ns.cribrum.profile

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import androidx.core.graphics.createBitmap
import androidx.startup.Initializer
import io.github.bert0ns.cribrum.CribrumApp
import io.github.bert0ns.cribrum.MainActivity
import io.github.bert0ns.cribrum.Permissions
import io.github.bert0ns.cribrum.model.Category
import io.github.bert0ns.cribrum.model.GROUP_KEY
import io.github.bert0ns.cribrum.model.ROLE_KEY
import io.github.bert0ns.cribrum.model.Role
import io.github.bert0ns.cribrum.model.Signal
import io.github.bert0ns.cribrum.model.toImageRef
import io.github.bert0ns.cribrum.pipeline.DETECTORS
import io.github.bert0ns.cribrum.pipeline.GROUP_DETECTORS
import kotlinx.coroutines.runBlocking
import java.io.File

/** Enough for several pages, and for the ranking to run as it does on a library. */
private const val IMAGES = 300
private const val GROUP_SIZE = 3
private const val IMAGE_SIDE = 256

/** Lets the app past its permission screen, and fills the index with flat colors on the first start. */
class ProfileSeed : Initializer<Unit> {
    override fun create(context: Context) {
        MainActivity.permissions = object : Permissions {
            override fun hasAllFilesAccess() = true

            override fun hasNotificationPermission(context: Context) = true
        }
        val folder = File(context.filesDir, "seed")
        if (folder.mkdir()) runBlocking { seed(context.applicationContext as CribrumApp, folder) }
    }

    override fun dependencies() = emptyList<Class<out Initializer<*>>>()

    private suspend fun seed(app: CribrumApp, folder: File) {
        val images = List(IMAGES) { File(folder, "$it.jpg").also { file -> writeImage(file, it) }.toImageRef() }
        app.index.recordAll(images)
        for (detector in DETECTORS) {
            // A sticker is listed under STICKER only, which would empty the other categories.
            val category = detector.category?.takeIf { it != Category.STICKER } ?: continue
            app.index.setSignalsOfMany(detector.id, detector.version, images.associate { it.path to listOf(Signal(detector.id, detector.version, category, 1f)) })
        }
        for (detector in GROUP_DETECTORS) {
            val signals = images.chunked(GROUP_SIZE).withIndex().flatMap { (group, members) ->
                members.mapIndexed { position, image ->
                    val best = position == 0
                    val evidence = mapOf(GROUP_KEY to "$group", ROLE_KEY to (if (best) Role.BEST else Role.INFERIOR).value)
                    image.path to listOf(Signal(detector.id, detector.version, detector.category!!, if (best) 0f else 1f, evidence))
                }
            }
            app.index.setSignalsOfMany(detector.id, detector.version, signals.toMap())
        }
    }

    private fun writeImage(file: File, position: Int) {
        val bitmap = createBitmap(IMAGE_SIDE, IMAGE_SIDE)
        bitmap.eraseColor(Color.HSVToColor(floatArrayOf(position * 360f / IMAGES, 0.6f, 0.8f)))
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 80, it) }
        bitmap.recycle()
    }
}
