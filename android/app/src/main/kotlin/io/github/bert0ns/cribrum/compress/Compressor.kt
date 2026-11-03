// Recompresses a JPEG the user keeps and swaps it in through the trash (docs/design.md §9).
package io.github.bert0ns.cribrum.compress

import android.app.ActivityManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import androidx.core.graphics.scale
import androidx.exifinterface.media.ExifInterface
import io.github.bert0ns.cribrum.index.ImageIndex
import io.github.bert0ns.cribrum.model.ImageRef
import io.github.bert0ns.cribrum.trash.AppTrash
import io.github.bert0ns.cribrum.trash.copyFileFor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.io.File
import java.lang.reflect.Modifier
import java.nio.file.Files
import java.nio.file.attribute.FileTime
import java.time.Clock
import java.util.concurrent.TimeUnit

/** Why a photo was not compressed at a level, for the UI to say. The original is untouched in every case. */
enum class Refusal {
    /** The new file is not smaller than the original. */
    NOT_SMALLER,

    /** The phone has too little free memory for this photo at this level. */
    LOW_MEMORY,

    /** The photo has a gain map the decoded or scaled bitmap lost, so it would come out as SDR. */
    GAIN_MAP_LOST,

    /** Anything else: the file could not be read, written or swapped, or it changed since its scan. */
    FAILED,
}

sealed interface Compression {
    /** The compressed copy, now at the original's path. The original is in the trash. */
    data class Done(val copy: ImageRef) : Compression

    data class Refused(val reason: Refusal) : Compression
}

class Compressor(
    private val trash: AppTrash,
    private val index: ImageIndex,
    /** `availMem − threshold` from `ActivityManager.getMemoryInfo`, read for each photo. */
    private val freeMemory: () -> Long,
    private val clock: Clock = Clock.systemUTC(),
) {
    /**
     * Writes a compressed copy of [image] next to it, then moves the original to the trash and the copy to its path.
     * The copy is a new [ImageRef] at the same path: the index keeps the row's review state and drops its signals, so
     * the next scan analyzes the copy again. Not cancellable, so the files and the index always change together.
     */
    suspend fun compress(image: ImageRef, level: CompressionLevel): Compression = withContext(Dispatchers.IO + NonCancellable) {
        val original = File(image.path)
        val copy = copyFileFor(original)
        val refusal = try {
            write(original, image, copy, level) ?: Refusal.NOT_SMALLER.takeIf { copy.length() >= image.size }
        } catch (_: OutOfMemoryError) {
            Refusal.LOW_MEMORY
        } catch (_: Exception) {
            Refusal.FAILED
        }
        if (refusal != null) {
            trash.discardCopy(copy)
            return@withContext Compression.Refused(refusal)
        }
        val swapped = trash.replaceWithCopy(image, copy) ?: return@withContext Compression.Refused(Refusal.FAILED)
        index.record(swapped)
        Compression.Done(swapped)
    }

    private fun write(original: File, image: ImageRef, copy: File, level: CompressionLevel): Refusal? {
        val gainMap = readJpeg(original).gainMap
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(original.path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return Refusal.FAILED
        val target = target(bounds.outWidth, bounds.outHeight, level)
        val sample = target.sampleSize
        val decodedBytes = ceilDiv(bounds.outWidth, sample).toLong() * ceilDiv(bounds.outHeight, sample) * 4
        if (decodedBytes * 5 / 2 > freeMemory()) return Refusal.LOW_MEMORY

        var bitmap = BitmapFactory.decodeFile(original.path, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return Refusal.FAILED
        val width: Int
        val height: Int
        try {
            if (gainMap && !bitmap.keepsGainMap()) return Refusal.GAIN_MAP_LOST
            if (bitmap.width != target.width || bitmap.height != target.height) {
                val scaled = bitmap.scale(target.width, target.height)
                bitmap.recycle()
                bitmap = scaled
                if (gainMap && !bitmap.keepsGainMap()) return Refusal.GAIN_MAP_LOST
            }
            width = bitmap.width
            height = bitmap.height
            if (!copy.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, level.quality, it) }) return Refusal.FAILED
        } finally {
            bitmap.recycle()
        }

        copyExif(original, copy, width, height)
        copy.writeBytes(withMarker(copy.readBytes(), markerSegment(level, clock.instant())))
        Files.setLastModifiedTime(copy.toPath(), FileTime.from(image.mtimeNs, TimeUnit.NANOSECONDS))
        return null
    }

    companion object {
        fun forDevice(context: Context, trash: AppTrash, index: ImageIndex): Compressor {
            val activities = context.getSystemService(ActivityManager::class.java)
            return Compressor(trash, index, freeMemory = {
                val memory = ActivityManager.MemoryInfo().also(activities::getMemoryInfo)
                memory.availMem - memory.threshold
            })
        }
    }
}

/**
 * Copies every tag ExifInterface knows from [from] to [to], except the structural ones and the XMP, then sets the
 * pixel size tags the original has to the new size. [to] keeps the XMP `Bitmap.compress` wrote, which describes
 * the new gain map of an Ultra HDR photo.
 */
internal fun copyExif(from: File, to: File, width: Int, height: Int) {
    // Release builds keep the TAG_ fields with a rule in proguard-rules.pro. Without them no tag would be copied.
    check(COPIED_TAGS.size > 100) { "ExifInterface tags not found: ${COPIED_TAGS.size}" }
    val source = ExifInterface(from)
    val target = ExifInterface(to)
    for (tag in COPIED_TAGS) source.getAttribute(tag)?.let { target.setAttribute(tag, it) }
    for ((tag, value) in listOf(
        ExifInterface.TAG_IMAGE_WIDTH to width,
        ExifInterface.TAG_IMAGE_LENGTH to height,
        ExifInterface.TAG_PIXEL_X_DIMENSION to width,
        ExifInterface.TAG_PIXEL_Y_DIMENSION to height,
    )) {
        if (source.getAttribute(tag) != null) target.setAttribute(tag, value.toString())
    }
    target.saveAttributes()
}

/** The pixel size, the thumbnail (with the Compression tag, which in a JPEG's Exif describes it) and the strips, and the XMP. */
private val NOT_COPIED = setOf(
    ExifInterface.TAG_IMAGE_WIDTH,
    ExifInterface.TAG_IMAGE_LENGTH,
    ExifInterface.TAG_PIXEL_X_DIMENSION,
    ExifInterface.TAG_PIXEL_Y_DIMENSION,
    ExifInterface.TAG_COMPRESSION,
    ExifInterface.TAG_JPEG_INTERCHANGE_FORMAT,
    ExifInterface.TAG_JPEG_INTERCHANGE_FORMAT_LENGTH,
    ExifInterface.TAG_THUMBNAIL_IMAGE_LENGTH,
    ExifInterface.TAG_THUMBNAIL_IMAGE_WIDTH,
    "ThumbnailOrientation", // TAG_THUMBNAIL_ORIENTATION is restricted to the library
    ExifInterface.TAG_STRIP_OFFSETS,
    ExifInterface.TAG_STRIP_BYTE_COUNTS,
    ExifInterface.TAG_ROWS_PER_STRIP,
    ExifInterface.TAG_NEW_SUBFILE_TYPE,
    ExifInterface.TAG_SUBFILE_TYPE,
    ExifInterface.TAG_ORF_THUMBNAIL_IMAGE,
    ExifInterface.TAG_ORF_PREVIEW_IMAGE_START,
    ExifInterface.TAG_ORF_PREVIEW_IMAGE_LENGTH,
    ExifInterface.TAG_RW2_JPG_FROM_RAW,
    ExifInterface.TAG_XMP,
)

private val COPIED_TAGS: List<String> = ExifInterface::class.java.fields
    .filter { it.name.startsWith("TAG_") && it.type == String::class.java && Modifier.isStatic(it.modifiers) }
    .map { it.get(null) as String }
    .filterNot { it in NOT_COPIED }
    .distinct()

/** Below API 34 `Bitmap` has no gain map, and `Bitmap.compress` writes SDR. */
private fun Bitmap.keepsGainMap() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && hasGainmap()

private fun ceilDiv(value: Int, divisor: Int) = (value + divisor - 1) / divisor
