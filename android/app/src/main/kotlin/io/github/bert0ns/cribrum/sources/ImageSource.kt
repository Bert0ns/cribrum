// Image sources: where images come from. Mirrors lab/src/cribrum_lab/sources.py and docs/design.md §4.
package io.github.bert0ns.cribrum.sources

import android.content.ContentResolver
import android.provider.MediaStore
import io.github.bert0ns.cribrum.model.ImageRef
import io.github.bert0ns.cribrum.model.toImageRef
import io.github.bert0ns.cribrum.trash.TRASH_FOLDER
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.attribute.BasicFileAttributes

interface ImageSource {
    fun scan(): Flow<ImageRef>

    /**
     * The roots the last [scan] listed in full. An image under one of them that [scan] did not
     * yield is gone; under any other root the scan could not look, so it says nothing.
     */
    val walkedRoots: List<File>
}

/**
 * What the walk remembers of the files it sniffed and found not to be images, so it does not read
 * their head again while their size and mtime stay the same.
 */
interface NotImages {
    suspend fun notImages(): Collection<ImageRef>

    /** Stores the files; one already stored with the same path is replaced. */
    suspend fun addNotImages(files: List<ImageRef>)

    suspend fun forgetNotImages(paths: Collection<String>)
}

/** Files the walk hands to [NotImages.addNotImages] at once. */
private const val NOT_IMAGES_BATCH = 300

/**
 * Walks a folder tree and yields every image file: the deep scan.
 *
 * Hidden folders and folders with a `.nomedia` file are included: that is where messaging apps
 * keep media the gallery never shows. Files are recognized by their content, not their
 * extension, except a file that [indexed] has with the same path, size and mtime: the index
 * took it as an image, from this walk or from MediaStore, so its head is not read again. The
 * same goes for a file [notImages] has: an earlier walk found it not to be an image. The walk
 * adds the non-images it sniffs, and drops the ones it no longer finds when it listed the whole root.
 * Symlinks are skipped so no file is yielded twice, and so are FIFOs and other special files,
 * which would block or fail on read. Left out on purpose: the app trash, files in the system
 * trash or still being written (`.trashed-*`, `.pending-*`), `Android/.Trash`, which is
 * Samsung Gallery's recycle bin, and `Android/data` and `Android/obb`, which Android 11 and
 * newer keep from every app.
 */
class FilesystemImageSource(
    private val root: File,
    /** Read once per scan, before the walk. */
    private val notImages: NotImages? = null,
    /** Read once per scan, before the walk. Last, so a test can pass it as a trailing lambda. */
    private val indexed: suspend () -> Collection<ImageRef> = { emptyList() },
) : ImageSource {
    /** The root once the walk listed every folder; a folder that cannot be listed, or a missing root, leaves it out. */
    @Volatile // written on the IO thread of the flow, read by the collector
    override var walkedRoots: List<File> = emptyList()
        private set

    override fun scan(): Flow<ImageRef> = flow {
        walkedRoots = emptyList()
        var listedAll = true
        val known = indexed().toHashSet()
        val remembered = notImages?.notImages().orEmpty()
        val rememberedSet = remembered.toHashSet()
        val metNotImages = HashSet<String>() // paths of non-images this walk met, remembered or newly sniffed
        var sniffed = ArrayList<ImageRef>(NOT_IMAGES_BATCH)
        val folders = ArrayDeque(listOf(root))
        while (folders.isNotEmpty()) {
            val folder = folders.removeFirst()
            val listing = folder.listFiles()
            if (listing == null) listedAll = false
            // One stat per entry: it tells a folder from a file and gives the size and mtime.
            val entries = listing.orEmpty().sortedBy { it.name }.mapNotNull { entry ->
                try {
                    entry to Files.readAttributes(entry.toPath(), BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
                } catch (_: IOException) {
                    null // unreadable or vanished during the scan
                }
            }
            val (dirs, files) = entries.partition { (_, attributes) -> attributes.isDirectory }
            for ((file, attributes) in files) {
                if (hasSkippedPrefix(file)) continue // the folders were decided when they were listed
                if (!attributes.isRegularFile) continue
                val image = file.toImageRef(attributes)
                if (image !in known) {
                    val again = image in rememberedSet
                    val isImageFile = if (again) false else sniff(file) ?: continue
                    if (!isImageFile) {
                        metNotImages += image.path
                        if (!again && notImages != null) {
                            sniffed.add(image)
                            if (sniffed.size == NOT_IMAGES_BATCH) {
                                notImages.addNotImages(sniffed)
                                sniffed = ArrayList(NOT_IMAGES_BATCH) // the store may still hold the list it was given
                            }
                        }
                        continue
                    }
                }
                emit(image)
            }
            // Depth first, in name order, like the lab's Path.walk.
            folders.addAll(0, dirs.map { it.first }.filter { !isSkippedFolder(root, it) })
        }
        if (notImages != null) {
            if (sniffed.isNotEmpty()) notImages.addNotImages(sniffed)
            // Only a root listed in full can tell a file that is gone from one the walk could not see.
            if (listedAll) notImages.forgetNotImages(remembered.map { it.path }.filter { it !in metNotImages && File(it).startsWith(root) })
        }
        if (listedAll) walkedRoots = listOf(root)
    }.flowOn(Dispatchers.IO)
}

private val SKIPPED_PREFIXES = listOf(".trashed-", ".pending-")
private val SKIPPED_FOLDERS = setOf("Android/data", "Android/obb", "Android/.Trash")

private fun isSkippedFolder(root: File, folder: File) = folder.name == TRASH_FOLDER || folder.relativeTo(root).invariantSeparatorsPath in SKIPPED_FOLDERS

private fun hasSkippedPrefix(file: File) = SKIPPED_PREFIXES.any(file.name::startsWith)

/** Whether the deep scan leaves [file] out: a file the system is still writing or has trashed, or one below a skipped folder of its volume [root]. */
internal fun isSkipped(root: File, file: File): Boolean {
    if (hasSkippedPrefix(file)) return true
    return generateSequence(file.parentFile) { it.parentFile }.takeWhile { it != root }.any { isSkippedFolder(root, it) }
}

/**
 * The images MediaStore indexes, on every volume, the SD card included. It misses `.nomedia`
 * folders, which the deep scan covers, and leaves out what the deep scan leaves out. It walks no
 * folder, so it reports no walked root. [volumes] tell which folder is the root of a path.
 */
class MediaStoreImageSource(private val resolver: ContentResolver, private val volumes: List<File>) : ImageSource {
    override val walkedRoots: List<File> = emptyList()

    override fun scan(): Flow<ImageRef> = flow {
        val uri = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        val paths = mutableListOf<String>()
        @Suppress("DEPRECATION") // DATA is the only way to the path, and All files access allows reading it
        resolver.query(uri, arrayOf(MediaStore.Images.Media.DATA), null, null, null)?.use { cursor ->
            while (cursor.moveToNext()) cursor.getString(0)?.let(paths::add)
        }
        for (path in paths.sorted()) {
            val file = File(path)
            if (isSkipped(volumes.firstOrNull(file::startsWith) ?: File("/"), file)) continue
            val image = try {
                if (!Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS)) continue
                file.toImageRef()
            } catch (_: IOException) {
                continue
            }
            emit(image)
        }
    }.flowOn(Dispatchers.IO)
}

/**
 * Every image any source finds, once. Each source builds its refs with [toImageRef], so the same
 * file has the same path, size and mtime whichever source finds it.
 */
class MergedImageSource(private val sources: List<ImageSource>) : ImageSource {
    override val walkedRoots: List<File> get() = sources.flatMap { it.walkedRoots }

    override fun scan(): Flow<ImageRef> = flow {
        val seen = HashSet<String>()
        for (source in sources) {
            source.scan().collect { if (seen.add(it.path)) emit(it) }
        }
    }
}

private val HEIF_BRANDS = setOf(
    "heic", "heix", "heim", "heis", "hevc", "hevx", "mif1", "mif2", "msf1", "miaf", "avif", "avis",
)

/** Enough for the magic bytes of every format and the brand list of a typical ftyp box. */
private const val HEAD_SIZE = 64

/** [isImage], or null when the head cannot be read. */
private fun sniff(file: File): Boolean? = try {
    isImage(file)
} catch (_: IOException) {
    null
}

/**
 * Videos (e.g. "isom", "mp42") share the ftyp box with HEIF and AVIF but declare no image brand.
 *
 * The walk remembers the files this refused, by size and mtime (docs/design.md §4). A change that lets
 * the head accept more, such as a new format or HEIF brand, must empty `not_images` in the same change:
 * a Room migration that runs `DELETE FROM not_images`. Otherwise those files are never sniffed again.
 */
internal fun isImage(file: File): Boolean {
    val head = file.inputStream().use { input ->
        val buffer = ByteArray(HEAD_SIZE)
        var read = 0
        while (read < HEAD_SIZE) read += input.read(buffer, read, HEAD_SIZE - read).takeIf { it > 0 } ?: break
        buffer.copyOf(read)
    }
    fun at(offset: Int, vararg bytes: Int) = head.size >= offset + bytes.size && bytes.indices.all { head[offset + it] == bytes[it].toByte() }
    fun text(offset: Int, value: String) = at(offset, *value.map { it.code }.toIntArray())
    return at(0, 0xff, 0xd8, 0xff) ||
        // JPEG
        at(0, 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a) ||
        // PNG
        text(0, "GIF87a") ||
        text(0, "GIF89a") ||
        (text(0, "RIFF") && text(8, "WEBP")) ||
        (text(4, "ftyp") && ftypBrands(head).any { it in HEIF_BRANDS })
}

/** Major brand plus compatible brands: HEIF files may declare their brand in either. */
private fun ftypBrands(head: ByteArray): List<String> {
    if (head.size < 12) return emptyList()
    val boxSize = head.take(4).fold(0L) { acc, b -> (acc shl 8) or (b.toLong() and 0xff) }
    val boxEnd = minOf(boxSize, head.size.toLong()).toInt()
    fun brand(offset: Int) = String(head, offset, 4, Charsets.ISO_8859_1)
    return listOf(brand(8)) + (16..boxEnd - 4 step 4).map(::brand)
}
