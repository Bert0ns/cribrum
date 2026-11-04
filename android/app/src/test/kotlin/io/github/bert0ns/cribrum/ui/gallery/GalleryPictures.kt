package io.github.bert0ns.cribrum.ui.gallery

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile
import kotlin.math.sin
import kotlin.random.Random

/**
 * Pictures drawn in code for the UI gallery: scenes, blurred scenes, screenshots, documents and stickers.
 * Nothing here comes from a photo or a file on a device.
 */
object GalleryPictures {
    private fun hsv(h: Float, s: Float, v: Float) = Color.HSVToColor(floatArrayOf(((h % 360f) + 360f) % 360f, s, v))

    /** A landscape or a still life: sky gradient, a sun, hills and a few shapes. [shift] moves the shapes, for near duplicates. */
    fun scene(seed: Int, width: Int = 480, height: Int = 360, shift: Float = 0f, brightness: Float = 1f): Bitmap {
        val random = Random(seed)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val hue = random.nextFloat() * 360f
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.shader = LinearGradient(0f, 0f, 0f, height.toFloat(), hsv(hue, 0.45f, 0.98f * brightness), hsv(hue + 35f, 0.35f, 0.85f * brightness), Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
        paint.shader = null
        paint.color = hsv(hue + 50f, 0.2f, 1f * brightness)
        canvas.drawCircle(width * (0.2f + random.nextFloat() * 0.6f) + shift, height * 0.25f, height * 0.09f, paint)
        for (layer in 0 until 3) {
            val base = height * (0.55f + layer * 0.14f)
            val phase = random.nextFloat() * 6f
            val path = Path().apply { moveTo(0f, height.toFloat()) }
            var x = 0f
            while (x <= width) {
                path.lineTo(x, base + sin(x / width * 5f + phase) * height * 0.06f)
                x += 8f
            }
            path.lineTo(width.toFloat(), height.toFloat())
            path.close()
            paint.color = hsv(hue + 120f + layer * 18f, 0.5f, (0.7f - layer * 0.12f) * brightness)
            canvas.drawPath(path, paint)
        }
        repeat(3) {
            paint.color = hsv(random.nextFloat() * 360f, 0.7f, 0.95f * brightness)
            val cx = width * random.nextFloat() + shift
            val cy = height * (0.45f + random.nextFloat() * 0.45f)
            if (random.nextBoolean()) {
                canvas.drawCircle(cx, cy, height * (0.05f + random.nextFloat() * 0.07f), paint)
            } else {
                val half = height * (0.05f + random.nextFloat() * 0.07f)
                canvas.drawRoundRect(RectF(cx - half, cy - half, cx + half, cy + half), 8f, 8f, paint)
            }
        }
        return bitmap
    }

    /** A soft copy: shrunk by [factor] and stretched back with filtering, like a photo out of focus. */
    fun blurred(source: Bitmap, factor: Int = 14): Bitmap {
        val small = Bitmap.createScaledBitmap(source, (source.width / factor).coerceAtLeast(2), (source.height / factor).coerceAtLeast(2), true)
        return Bitmap.createScaledBitmap(small, source.width, source.height, true)
    }

    /** A phone screenshot: a status bar, a title bar and rows of an imaginary list. */
    fun screenshot(seed: Int, width: Int = 270, height: Int = 585): Bitmap {
        val random = Random(seed)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val accent = hsv(random.nextFloat() * 360f, 0.6f, 0.75f)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        canvas.drawColor(Color.rgb(248, 248, 250))
        paint.color = Color.rgb(30, 30, 34)
        canvas.drawRect(0f, 0f, width.toFloat(), 22f, paint)
        paint.color = accent
        canvas.drawRect(0f, 22f, width.toFloat(), 70f, paint)
        paint.color = Color.WHITE
        canvas.drawRoundRect(RectF(16f, 38f, 16f + width * 0.4f, 52f), 7f, 7f, paint)
        var y = 86f
        while (y < height - 70f) {
            paint.color = Color.WHITE
            canvas.drawRoundRect(RectF(10f, y, width - 10f, y + 52f), 10f, 10f, paint)
            paint.color = hsv(random.nextFloat() * 360f, 0.35f, 0.9f)
            canvas.drawCircle(36f, y + 26f, 16f, paint)
            paint.color = Color.rgb(70, 70, 76)
            canvas.drawRoundRect(RectF(62f, y + 12f, 62f + width * (0.3f + random.nextFloat() * 0.3f), y + 22f), 5f, 5f, paint)
            paint.color = Color.rgb(170, 170, 178)
            canvas.drawRoundRect(RectF(62f, y + 30f, 62f + width * (0.4f + random.nextFloat() * 0.3f), y + 38f), 4f, 4f, paint)
            y += 60f
        }
        paint.color = Color.rgb(235, 235, 240)
        canvas.drawRect(0f, height - 48f, width.toFloat(), height.toFloat(), paint)
        paint.color = Color.rgb(40, 40, 44)
        canvas.drawRoundRect(RectF(width * 0.35f, height - 14f, width * 0.65f, height - 9f), 3f, 3f, paint)
        return bitmap
    }

    /** A flat-colored round face on a plain background: what a messaging sticker looks like. */
    fun sticker(seed: Int, size: Int = 256): Bitmap {
        val random = Random(seed)
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val hue = random.nextFloat() * 360f
        canvas.drawColor(hsv(hue + 180f, 0.2f, 0.97f))
        paint.color = hsv(hue, 0.7f, 1f)
        canvas.drawCircle(size / 2f, size / 2f, size * 0.38f, paint)
        paint.color = Color.rgb(40, 30, 30)
        canvas.drawCircle(size * 0.38f, size * 0.42f, size * 0.04f, paint)
        canvas.drawCircle(size * 0.62f, size * 0.42f, size * 0.04f, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = size * 0.025f
        canvas.drawArc(RectF(size * 0.34f, size * 0.42f, size * 0.66f, size * 0.7f), 15f, 150f, false, paint)
        return bitmap
    }

    /** A dark or washed out frame: a scene with a flat, almost uniform tone. */
    fun exposure(seed: Int, dark: Boolean): Bitmap {
        val bitmap = scene(seed, brightness = if (dark) 0.22f else 1f)
        if (!dark) {
            Canvas(bitmap).drawColor(Color.argb(170, 255, 255, 255))
        }
        return bitmap
    }

    /** The same picture as a hard compression leaves it: coarse JPEG blocks. The summary's "after" is this, since the real encoder is not run. */
    fun degraded(file: File): ByteArray {
        val bitmap = checkNotNull(BitmapFactory.decodeFile(file.path)) { "cannot decode ${file.path}" }
        return ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, 12, it) }.toByteArray()
    }

    /**
     * Writes [bitmap] to [file] and stretches the file to [bytes] with zeros after the image data. Decoders stop at the
     * end of the image, so it still opens, and the index and the trash see the size a real photo has. The stretch is sparse.
     */
    fun write(file: File, bitmap: Bitmap, bytes: Long, quality: Int = 88) {
        file.parentFile?.mkdirs()
        val png = file.extension.equals("png", ignoreCase = true)
        file.outputStream().use { bitmap.compress(if (png) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG, quality, it) }
        if (file.length() < bytes) RandomAccessFile(file, "rw").use { it.setLength(bytes) }
    }
}
