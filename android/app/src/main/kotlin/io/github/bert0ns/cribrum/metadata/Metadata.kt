// Reads image metadata from the file header. Mirrors read_metadata in lab/src/cribrum_lab/metadata.py.
package io.github.bert0ns.cribrum.metadata

import android.graphics.BitmapFactory
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.io.IOException
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

/** The file looks like an image but its header can't be decoded. */
class UnreadableImage(file: File, cause: Throwable? = null) : IOException(file.path, cause)

private val EXIF_DATETIME: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy:MM:dd HH:mm:ss")

/** EXIF orientations that turn the image by 90 degrees. */
private val ROTATED_ORIENTATIONS = 5..8

/** Reads the header only: the pixels are not decoded. */
fun readMetadata(file: File): ImageMetadata {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.path, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw UnreadableImage(file)
    val exif = try {
        ExifInterface(file)
    } catch (_: IOException) {
        null // a malformed EXIF block: the image itself still counts
    }
    val rotated = exif?.getAttributeInt(ExifInterface.TAG_ORIENTATION, 1) in ROTATED_ORIENTATIONS
    return ImageMetadata(
        width = if (rotated) bounds.outHeight else bounds.outWidth,
        height = if (rotated) bounds.outWidth else bounds.outHeight,
        mime = bounds.outMimeType,
        cameraMake = exif?.text(ExifInterface.TAG_MAKE),
        cameraModel = exif?.text(ExifInterface.TAG_MODEL),
        capturedAt = exif?.let {
            datetime(it.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL))
                ?: datetime(it.getAttribute(ExifInterface.TAG_DATETIME_DIGITIZED))
        },
    )
}

private fun ExifInterface.text(tag: String): String? = getAttribute(tag)?.trim('\u0000', ' ')?.ifEmpty { null }

/** Drops NUL terminators and any subsecond or timezone suffix some cameras append. */
private fun datetime(value: String?): LocalDateTime? = try {
    value?.trim('\u0000', ' ')?.take(19)?.let { LocalDateTime.parse(it, EXIF_DATETIME) }
} catch (_: DateTimeParseException) {
    null
}
