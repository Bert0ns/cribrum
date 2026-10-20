// The app-managed trash (docs/design.md §9): the only code allowed to delete files.
package io.github.bert0ns.cribrum.trash

import android.content.Context
import android.media.MediaScannerConnection
import android.os.storage.StorageManager
import io.github.bert0ns.cribrum.model.ImageRef
import io.github.bert0ns.cribrum.model.toImageRef
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.Properties
import java.util.UUID

/** Folder at the root of each storage volume. Hidden, and with a `.nomedia` so galleries skip it. */
const val TRASH_FOLDER = ".cribrum-trash"

val RETENTION: Duration = Duration.ofDays(30)

data class TrashedImage(
    /** Where the image was, with the size and mtime it had: moving keeps both. */
    val original: ImageRef,
    val trashedAt: Instant,
    /** The file inside the trash. */
    val file: File,
) {
    val purgeAt: Instant get() = trashedAt + RETENTION
}

interface Trash {
    /** Moves images to the trash. Returns the ones moved: a file gone or changed since its scan stays put. */
    suspend fun moveToTrash(images: List<ImageRef>): List<ImageRef>

    /** Moves images back to their original path. Returns the ones restored: an occupied path is never overwritten. */
    suspend fun restore(images: List<ImageRef>): List<ImageRef>

    suspend fun trashed(): List<TrashedImage>

    /** Deletes images from the trash for good. */
    suspend fun purge(images: List<ImageRef>)

    /** Deletes what has been in the trash for longer than [RETENTION]. Returns what it deleted. */
    suspend fun purgeExpired(): List<ImageRef>
}

/**
 * Each trashed file gets its own folder in the trash of its volume, holding the file under its
 * own name and an `origin.properties` with where it came from. The trash survives an uninstall
 * and needs no database to be restored. Moving is a rename, so it is instant and keeps the
 * file's mtime: a restored file is the same [ImageRef] as before.
 */
class AppTrash(
    /** The storage volume roots: each file goes to the trash on its own volume. */
    private val volumes: () -> List<File>,
    private val clock: Clock = Clock.systemUTC(),
    /** Told about every path that appeared or disappeared, so MediaStore can catch up. */
    private val changed: (List<String>) -> Unit = {},
) : Trash {
    override suspend fun moveToTrash(images: List<ImageRef>): List<ImageRef> = io {
        val moved = images.filter(::move)
        changed(moved.map { it.path })
        moved
    }

    override suspend fun restore(images: List<ImageRef>): List<ImageRef> = io {
        val byOriginal = items().associateBy { it.original }
        val restored = images.filter { image ->
            val item = byOriginal[image] ?: return@filter false
            val target = File(image.path)
            if (target.exists()) return@filter false
            target.parentFile?.mkdirs()
            item.file.renameTo(target).also { if (it) deleteItem(item.file.parentFile!!) }
        }
        changed(restored.map { it.path })
        restored
    }

    override suspend fun trashed(): List<TrashedImage> = io { items() }

    override suspend fun purge(images: List<ImageRef>) = io {
        val wanted = images.toSet()
        items().filter { it.original in wanted }.forEach { deleteItem(it.file.parentFile!!) }
    }

    override suspend fun purgeExpired(): List<ImageRef> = io {
        val now = clock.instant()
        items().filter { it.purgeAt <= now }.onEach { deleteItem(it.file.parentFile!!) }.map { it.original }
    }

    private fun move(image: ImageRef): Boolean {
        val file = File(image.path)
        if (!file.isFile || runCatching { file.toImageRef() }.getOrNull() != image) return false
        val root = rootOf(file) ?: return false
        val folder = File(root, UUID.randomUUID().toString())
        try {
            if (!folder.mkdirs()) return false
            File(root, ".nomedia").createNewFile()
            writeOrigin(folder, image, clock.instant())
            if (file.renameTo(File(folder, file.name))) return true
        } catch (_: IOException) {
        }
        deleteItem(folder)
        return false
    }

    private fun items(): List<TrashedImage> = volumes().flatMap { volume ->
        File(volume, TRASH_FOLDER).listFiles().orEmpty().mapNotNull(::readItem)
    }.sortedByDescending { it.trashedAt }

    private fun rootOf(file: File): File? {
        val path = file.canonicalPath
        val volume = volumes().firstOrNull { path.startsWith(it.canonicalPath + File.separator) } ?: return null
        return File(volume, TRASH_FOLDER)
    }

    private fun writeOrigin(folder: File, image: ImageRef, trashedAt: Instant) {
        val origin = Properties()
        origin["path"] = image.path
        origin["size"] = image.size.toString()
        origin["mtime_ns"] = image.mtimeNs.toString()
        origin["trashed_at"] = trashedAt.toString()
        File(folder, ORIGIN).outputStream().use { origin.store(it, "Cribrum trash: where this file came from") }
    }

    /** Null for anything the trash did not write, or that lost its file. */
    private fun readItem(folder: File): TrashedImage? {
        val originFile = File(folder, ORIGIN)
        if (!originFile.isFile) return null
        val origin = Properties().apply { originFile.inputStream().use(::load) }
        val path = origin.getProperty("path") ?: return null
        val file = File(folder, File(path).name).takeIf { it.isFile } ?: return null
        return runCatching {
            TrashedImage(
                ImageRef(path, origin.getProperty("size").toLong(), origin.getProperty("mtime_ns").toLong()),
                Instant.parse(origin.getProperty("trashed_at")),
                file,
            )
        }.getOrNull()
    }

    /** Deletes one item folder of the trash, never anything outside it. */
    private fun deleteItem(folder: File) {
        val trash = folder.canonicalFile.parentFile
        check(trash?.name == TRASH_FOLDER) { "not a trash item: $folder" }
        folder.deleteRecursively()
    }

    companion object {
        private const val ORIGIN = "origin.properties"

        fun forDevice(context: Context, clock: Clock = Clock.systemUTC()): AppTrash {
            val storage = context.getSystemService(StorageManager::class.java)
            return AppTrash(
                volumes = { storage.storageVolumes.mapNotNull { it.directory } },
                clock = clock,
                changed = { paths ->
                    if (paths.isNotEmpty()) MediaScannerConnection.scanFile(context, paths.toTypedArray(), null, null)
                },
            )
        }
    }
}

private suspend fun <T> io(block: () -> T): T = withContext(Dispatchers.IO) { block() }
