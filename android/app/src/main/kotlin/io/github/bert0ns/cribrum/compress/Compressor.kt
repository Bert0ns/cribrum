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
import io.github.bert0ns.cribrum.metadata.readMetadata
import io.github.bert0ns.cribrum.model.ImageRef
import io.github.bert0ns.cribrum.model.ReviewState
import io.github.bert0ns.cribrum.tracing.traceSuspend
import io.github.bert0ns.cribrum.trash.AppTrash
import io.github.bert0ns.cribrum.trash.Swap
import io.github.bert0ns.cribrum.trash.copyFileFor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.lang.reflect.Modifier
import java.nio.file.Files
import java.nio.file.attribute.FileTime
import java.time.Clock
import java.util.concurrent.TimeUnit

/** Why a photo was not compressed at a level, for the UI to say. The original is untouched, except with [LEFT_IN_TRASH]. */
enum class Refusal {
    /** The new file is not smaller than the original. */
    NOT_SMALLER,

    /** The phone has too little free memory for this photo at this level. */
    LOW_MEMORY,

    /** The photo has a gain map the decoded or scaled bitmap or the written copy lost, so it would come out as SDR. */
    GAIN_MAP_LOST,

    /** Anything else: the file could not be read, written or swapped, or it changed since its scan. */
    FAILED,

    /** The swap failed halfway and the original could not be put back: it is in the Cribrum trash, to restore from there. */
    LEFT_IN_TRASH,
}

sealed interface Compression {
    /** The compressed copy, now at the original's path. The original is in the trash. [favorite]: the original was marked in the gallery, the copy is not. */
    data class Done(val copy: ImageRef, val favorite: Boolean = false) : Compression

    data class Refused(val reason: Refusal) : Compression
}

class Compressor(
    private val trash: AppTrash,
    private val index: ImageIndex,
    /** `availMem − threshold` from `ActivityManager.getMemoryInfo`, read for each photo. */
    private val freeMemory: () -> Long,
    private val clock: Clock = Clock.systemUTC(),
) {
    /** Robolectric decodes no gain map, so a test swaps this to reach the check on the written copy. */
    internal var bitmapKeepsGainMap: (Bitmap) -> Boolean = { it.keepsGainMap() }

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
        when (val swap = trash.replaceWithCopy(image, copy)) {
            Swap.Untouched -> Compression.Refused(Refusal.FAILED)
            Swap.LeftInTrash -> {
                // Like any trashed image, so the next scan does not forget the row of a file it no longer finds.
                runCatching { index.setReviewState(image.path, ReviewState.TRASHED) }
                Compression.Refused(Refusal.LEFT_IN_TRASH)
            }
            is Swap.Swapped -> {
                // The files are swapped already. If the index cannot follow, the next scan records the copy.
                runCatching {
                    index.record(swap.copy)
                    showBeforeScan(index, swap.copy.path)
                }
                Compression.Done(swap.copy, swap.favorite)
            }
        }
    }

    /**
     * Compresses [original] at [level] into [out] the way [compress] would, with the EXIF but no marker, and
     * touches nothing else: the original, the trash and the index stay as they are. For the compress summary's
     * before and after. One at a time, like a compression. Cancellable between the decode, the scale and the
     * encode, since the bitmap calls themselves cannot be interrupted. [out] is deleted, through the trash, when
     * the preview refuses, fails or is cancelled, so it must be named by [copyFileFor].
     */
    suspend fun preview(original: File, level: CompressionLevel, out: File): Refusal? = previews.withLock {
        // Inside the lock, so the section is the work and not the wait for the preview before it.
        traceSuspend("Compressor.preview") {
            withContext(Dispatchers.IO) {
                // Until it ends well, [out] is not worth keeping: a throw or a cancel leaves this as it is.
                var refusal: Refusal? = Refusal.FAILED
                try {
                    refusal = try {
                        val encoded = encode(original, out, level) { ensureActive() }
                        if (encoded.refusal != null) {
                            encoded.refusal
                        } else {
                            ensureActive()
                            copyExif(original, out, encoded.width, encoded.height)
                            Refusal.NOT_SMALLER.takeIf { out.length() >= original.length() }
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: OutOfMemoryError) {
                        Refusal.LOW_MEMORY
                    } catch (_: Exception) {
                        Refusal.FAILED
                    }
                    refusal
                } finally {
                    if (refusal != null) trash.discardCopy(out)
                }
            }
        }
    }

    private val previews = Mutex()

    private fun write(original: File, image: ImageRef, copy: File, level: CompressionLevel): Refusal? {
        val encoded = encode(original, copy, level)
        if (encoded.refusal != null) return encoded.refusal
        copyExif(original, copy, encoded.width, encoded.height)
        copy.writeBytes(withMarker(copy.readBytes(), markerSegment(level, clock.instant())))
        Files.setLastModifiedTime(copy.toPath(), FileTime.from(image.mtimeNs, TimeUnit.NANOSECONDS))
        return null
    }

    /** What [encode] wrote: its size, or the reason it did not. */
    private class Encoded(val width: Int = 0, val height: Int = 0, val refusal: Refusal? = null)

    /** Decodes [original] at [level]'s size and writes the JPEG to [copy], with the gain map checks. No EXIF yet. */
    private fun encode(original: File, copy: File, level: CompressionLevel, checkActive: () -> Unit = {}): Encoded {
        val gainMap = readJpeg(original).gainMap
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(original.path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return Encoded(refusal = Refusal.FAILED)
        val target = target(bounds.outWidth, bounds.outHeight, level)
        val sample = target.sampleSize
        val decodedBytes = ceilDiv(bounds.outWidth, sample).toLong() * ceilDiv(bounds.outHeight, sample) * 4
        if (decodedBytes * 5 / 2 > freeMemory()) return Encoded(refusal = Refusal.LOW_MEMORY)

        var bitmap = BitmapFactory.decodeFile(original.path, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return Encoded(refusal = Refusal.FAILED)
        val width: Int
        val height: Int
        try {
            checkActive()
            if (gainMap && !bitmapKeepsGainMap(bitmap)) return Encoded(refusal = Refusal.GAIN_MAP_LOST)
            if (bitmap.width != target.width || bitmap.height != target.height) {
                val scaled = bitmap.scale(target.width, target.height)
                bitmap.recycle()
                bitmap = scaled
                checkActive()
                if (gainMap && !bitmapKeepsGainMap(bitmap)) return Encoded(refusal = Refusal.GAIN_MAP_LOST)
            }
            width = bitmap.width
            height = bitmap.height
            checkActive()
            if (!copy.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, level.quality, it) }) return Encoded(refusal = Refusal.FAILED)
        } finally {
            bitmap.recycle()
        }
        if (gainMap && !readJpeg(copy).gainMap) return Encoded(refusal = Refusal.GAIN_MAP_LOST)
        return Encoded(width, height)
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
 * Reads the headers and the resolution of the file the app has just put at [path], a compressed copy or a restored
 * original, so the compress page lists it before a scan reads it (docs/design.md §9). Only an eligible JPEG gets a row,
 * since the page lists no other. The next scan still reads the file as a changed one.
 */
suspend fun showBeforeScan(index: ImageIndex, path: String) {
    val file = File(path)
    val (info, metadata) = withContext(Dispatchers.IO) {
        val info = readJpeg(file)
        if (info.status == JpegStatus.ELIGIBLE) info to readMetadata(file) else null
    } ?: return
    index.setJpegBeforeScan(path, info, metadata.width, metadata.height)
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
    // ExifInterface drops this tag when the camera stored it in another TIFF format, and without it a depth reads as a height.
    if (source.getAttribute(ExifInterface.TAG_GPS_ALTITUDE_REF) == null) {
        rawGpsAltitudeRef(from)?.let { target.setAttribute(ExifInterface.TAG_GPS_ALTITUDE_REF, it.toString()) }
    }
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
    // How the original's pixels were encoded, which the new encode decides again.
    ExifInterface.TAG_Y_CB_CR_SUB_SAMPLING,
    ExifInterface.TAG_BITS_PER_SAMPLE,
    ExifInterface.TAG_PHOTOMETRIC_INTERPRETATION,
    ExifInterface.TAG_SAMPLES_PER_PIXEL,
    ExifInterface.TAG_PLANAR_CONFIGURATION,
    // Raw formats only.
    ExifInterface.TAG_DNG_VERSION,
    ExifInterface.TAG_DEFAULT_CROP_SIZE,
    ExifInterface.TAG_ORF_ASPECT_FRAME,
    ExifInterface.TAG_RW2_SENSOR_BOTTOM_BORDER,
    ExifInterface.TAG_RW2_SENSOR_LEFT_BORDER,
    ExifInterface.TAG_RW2_SENSOR_RIGHT_BORDER,
    ExifInterface.TAG_RW2_SENSOR_TOP_BORDER,
    ExifInterface.TAG_RW2_ISO,
)

private val COPIED_TAGS: List<String> = ExifInterface::class.java.fields
    .filter { it.name.startsWith("TAG_") && it.type == String::class.java && Modifier.isStatic(it.modifiers) }
    .map { it.get(null) as String }
    .filterNot { it in NOT_COPIED }
    .distinct()

/** Below API 34 `Bitmap` has no gain map, and `Bitmap.compress` writes SDR. */
private fun Bitmap.keepsGainMap() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && hasGainmap()

private fun ceilDiv(value: Int, divisor: Int) = (value + divisor - 1) / divisor
