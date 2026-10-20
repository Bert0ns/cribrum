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

interface ImageSource {
    fun scan(): Flow<ImageRef>
}

/**
 * Walks a folder tree and yields every image file: the deep scan.
 *
 * Hidden folders and folders with a `.nomedia` file are included: that is where messaging apps
 * keep media the gallery never shows. Files are recognized by their content, not their
 * extension. Symlinks are skipped so no file is yielded twice, and so are FIFOs and other special
 * files, which would block or fail on read. Left out on purpose: the app trash, files in the
 * system trash or still being written (`.trashed-*`, `.pending-*`), and `Android/data` and
 * `Android/obb`, which Android 11 and newer keep from every app.
 */
class FilesystemImageSource(private val root: File) : ImageSource {
    override fun scan(): Flow<ImageRef> = flow {
        val folders = ArrayDeque(listOf(root))
        while (folders.isNotEmpty()) {
            val folder = folders.removeFirst()
            val entries = folder.listFiles().orEmpty().sortedBy { it.name }
            val (dirs, files) = entries.partition { Files.isDirectory(it.toPath(), LinkOption.NOFOLLOW_LINKS) }
            for (file in files) {
                if (SKIPPED_PREFIXES.any(file.name::startsWith)) continue
                val image = try {
                    if (!Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS) || !isImage(file)) continue
                    file.toImageRef()
                } catch (_: IOException) {
                    continue // unreadable or vanished during the scan
                }
                emit(image)
            }
            // Depth first, in name order, like the lab's Path.walk.
            folders.addAll(0, dirs.filter { it.name != TRASH_FOLDER && it.relativeTo(root).path !in SKIPPED_FOLDERS })
        }
    }.flowOn(Dispatchers.IO)

    private companion object {
        val SKIPPED_PREFIXES = listOf(".trashed-", ".pending-")
        val SKIPPED_FOLDERS = setOf("Android/data", "Android/obb")
    }
}

/**
 * The images MediaStore indexes, on every volume, the SD card included. It misses `.nomedia`
 * folders, which the deep scan covers, and leaves out the system trash and pending files.
 */
class MediaStoreImageSource(private val resolver: ContentResolver) : ImageSource {
    override fun scan(): Flow<ImageRef> = flow {
        val uri = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        val paths = mutableListOf<String>()
        @Suppress("DEPRECATION") // DATA is the only way to the path, and All files access allows reading it
        resolver.query(uri, arrayOf(MediaStore.Images.Media.DATA), null, null, null)?.use { cursor ->
            while (cursor.moveToNext()) cursor.getString(0)?.let(paths::add)
        }
        for (path in paths.sorted()) {
            val file = File(path)
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

/** Videos (e.g. "isom", "mp42") share the ftyp box with HEIF and AVIF but declare no image brand. */
internal fun isImage(file: File): Boolean {
    val head = file.inputStream().use { input ->
        val buffer = ByteArray(HEAD_SIZE)
        var read = 0
        while (read < HEAD_SIZE) read += input.read(buffer, read, HEAD_SIZE - read).takeIf { it > 0 } ?: break
        buffer.copyOf(read)
    }
    fun at(offset: Int, vararg bytes: Int) =
        head.size >= offset + bytes.size && bytes.indices.all { head[offset + it] == bytes[it].toByte() }
    fun text(offset: Int, value: String) = at(offset, *value.map { it.code }.toIntArray())
    return at(0, 0xff, 0xd8, 0xff) || // JPEG
        at(0, 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a) || // PNG
        text(0, "GIF87a") || text(0, "GIF89a") ||
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
