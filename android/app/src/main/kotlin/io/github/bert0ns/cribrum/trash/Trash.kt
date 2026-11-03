// The app-managed trash (docs/design.md §9): the only code allowed to delete files.
package io.github.bert0ns.cribrum.trash

import android.content.Context
import android.media.MediaScannerConnection
import android.os.storage.StorageManager
import android.util.Log
import io.github.bert0ns.cribrum.model.ImageRef
import io.github.bert0ns.cribrum.model.toImageRef
import io.github.bert0ns.cribrum.sources.FavoriteRequests
import io.github.bert0ns.cribrum.sources.queryFavoritesAmong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.Properties
import java.util.UUID

/** Folder at the root of each storage volume. Hidden, and with a `.nomedia` so galleries skip it. */
const val TRASH_FOLDER = ".cribrum-trash"

/** Where to write a compressed copy of [original]: next to it, so moving it into place is a rename on the same volume. The scan skips `.pending-` files (§4). */
fun copyFileFor(original: File): File = File(original.parentFile, ".pending-cribrum-${UUID.randomUUID()}.jpg")

/** The names [copyFileFor] gives. */
private val COPY_NAME = Regex("""^\.pending-cribrum-[0-9a-f-]{36}\.jpg$""")

/** What [AppTrash.replaceWithCopy] did. The copy is discarded unless it is [Swapped]. */
sealed interface Swap {
    /** The copy, as it now is at the original's path. The original is in the trash. [favorite]: the gallery had it marked, and the copy is a new MediaStore row without the mark. */
    data class Swapped(val copy: ImageRef, val favorite: Boolean = false) : Swap

    /** Nothing changed: the original is where it was. */
    data object Untouched : Swap

    /** The copy never took the path and the original could not be put back: it waits in the trash. */
    data object LeftInTrash : Swap
}

data class TrashedImage(
    /** Where the image was, with the size and mtime it had: moving keeps both. */
    val original: ImageRef,
    val trashedAt: Instant,
    /** The file inside the trash. */
    val file: File,
    /** When the trash purges it, under the retention the user has chosen now. */
    val purgeAt: Instant,
    val seenAt: Instant = trashedAt,
    /** The compressed copy that took the original's path, as it was when it did. Null for an ordinary item. */
    val compressedCopy: ImageRef? = null,
    /** The gallery had it marked as a favourite when it went to the trash, so a restore asks for the mark again (docs/design.md §9). */
    val favorite: Boolean = false,
)

interface Trash {
    /** Moves images to the trash. Returns the ones moved: a file gone or changed since its scan stays put. */
    suspend fun moveToTrash(images: List<ImageRef>): List<ImageRef>

    /** Moves images back to their original path. Returns the ones restored: an occupied path is never overwritten. */
    suspend fun restore(images: List<ImageRef>): List<ImageRef>

    suspend fun trashed(): List<TrashedImage>

    /** Deletes images from the trash for good. Returns the ones deleted: a file the system would not delete is still in the trash. */
    suspend fun purge(images: List<ImageRef>): List<ImageRef>

    /** Deletes what has been in the trash for longer than the retention the user chose. Returns what it deleted. */
    suspend fun purgeExpired(): List<ImageRef>
}

/**
 * The paths of [purged] that no item left in the trash holds, for the index to forget. A compressed original and
 * its copy share a path, so purging one must not drop the row the other still needs (docs/design.md §9).
 */
suspend fun Trash.unheldPaths(purged: List<ImageRef>): List<String> {
    val held = trashed().mapTo(HashSet()) { it.original.path }
    return purged.map { it.path }.filterNot { it in held }
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
    /** Read on every call, so a change applies to the images already in the trash. */
    private val retention: () -> Duration = { TrashRetention.DEFAULT },
    /** Which of the paths MediaStore flags as favourites now, read on the IO dispatcher. A throw counts as none. */
    private val favorites: (Collection<String>) -> Set<String> = { emptySet() },
    /** Told the paths of the restored images that were favourites, so the mark can be asked for again. */
    private val restoredFavorites: suspend (List<String>) -> Unit = {},
    /** Told about every path that appeared or disappeared, so MediaStore can catch up. */
    private val changed: (List<String>) -> Unit = {},
) : Trash {

    internal var recordWriter: (File, ImageRef, Instant, Instant, ImageRef?, Boolean) -> Unit = ::writeOrigin

    /** Moves a file without replacing one already there. Tests swap it to fail a step. */
    internal var mover: (File, File) -> Unit = { from, to -> Files.move(from.toPath(), to.toPath()) }

    override suspend fun moveToTrash(images: List<ImageRef>): List<ImageRef> = io {
        val flagged = flagged(images.map { it.path })
        val moved = images.filter { move(it, favorite = it.path in flagged) != null }
        changed(moved.map { it.path })
        moved
    }

    /**
     * Restoring an original whose path holds its compressed copy moves the copy to the trash first, so nothing is
     * deleted. A file at the path that is not that copy is never moved or overwritten, and the original stays.
     */
    override suspend fun restore(images: List<ImageRef>): List<ImageRef> {
        val (restored, favorites) = io { restoreFiles(images) }
        if (favorites.isNotEmpty()) restoredFavorites(favorites)
        return restored
    }

    /** The images put back, and the paths among them that were favourites. */
    private fun restoreFiles(images: List<ImageRef>): Pair<List<ImageRef>, List<String>> {
        val byOriginal = items().associateBy { it.original }
        val restored = images.filter { image ->
            val item = byOriginal[image] ?: return@filter false
            val target = File(image.path)
            val copy = item.compressedCopy
            val copyFolder = if (copy != null && target.exists()) move(copy, favorite = flagged(listOf(copy.path)).isNotEmpty()) ?: return@filter false else null
            target.parentFile?.mkdirs()
            val moved = runCatching { mover(item.file, target) }.isSuccess
            if (moved) {
                deleteItem(item.file.parentFile!!)
            } else if (copyFolder != null && runCatching { mover(File(copyFolder, target.name), target) }.isSuccess) {
                deleteItem(copyFolder)
            }
            moved
        }
        changed(restored.map { it.path })
        return restored to restored.filter { byOriginal.getValue(it).favorite }.map { it.path }
    }

    /**
     * Moves [original] to the trash and renames [copy], its compressed copy written by [copyFileFor], to its path.
     * The item records the copy, so restoring the original puts the copy in the trash in its place.
     */
    suspend fun replaceWithCopy(original: ImageRef, copy: File): Swap = io {
        val target = File(original.path)
        val copyRef = runCatching { copy.toImageRef() }.getOrNull()?.let { ImageRef(original.path, it.size, it.mtimeNs) }
        val favorite = flagged(listOf(original.path)).isNotEmpty()
        val folder = copyRef?.let { runCatching { move(original, it, favorite) }.getOrNull() }
        if (folder == null || runCatching { mover(copy, target) }.isFailure) {
            discardCopy(copy)
            if (folder == null) return@io Swap.Untouched
            if (runCatching { mover(File(folder, target.name), target) }.isFailure) return@io Swap.LeftInTrash
            deleteItem(folder)
            return@io Swap.Untouched
        }
        changed(listOf(original.path))
        Swap.Swapped(runCatching { target.toImageRef() }.getOrDefault(copyRef), favorite)
    }

    /**
     * Deletes a compressed copy that never took its original's place, and does nothing when it was never written.
     * Refuses anything but a file named by [copyFileFor] outside the trash.
     */
    internal fun discardCopy(copy: File) {
        check(COPY_NAME.matches(copy.name)) { "not a compressed copy: $copy" }
        if (!copy.exists()) return
        check(copy.isFile) { "not a file: $copy" }
        check(generateSequence(copy.canonicalFile.parentFile) { it.parentFile }.none { it.name == TRASH_FOLDER }) { "inside the trash: $copy" }
        copy.delete()
    }

    /** Discards the copies [copyFileFor] named in [folders], which a compression killed before its swap leaves behind. */
    internal fun discardCopiesIn(folders: Collection<File>) {
        for (folder in folders) {
            folder.listFiles { file -> COPY_NAME.matches(file.name) }.orEmpty().forEach { runCatching { discardCopy(it) } }
        }
    }

    override suspend fun trashed(): List<TrashedImage> = io { items() }

    override suspend fun purge(images: List<ImageRef>): List<ImageRef> = io {
        val wanted = images.toSet()
        items().filter { it.original in wanted && deleteItem(it.file.parentFile!!) }.map { it.original }
    }

    override suspend fun purgeExpired(): List<ImageRef> = io {
        val now = clock.instant()
        val expired = mutableListOf<ImageRef>()
        for (volume in volumes()) {
            val trashFolder = File(volume, TRASH_FOLDER)
            for (folder in trashFolder.listFiles().orEmpty()) {
                val item = readItem(folder, syncSeenAt = true) ?: continue
                if (item.purgeAt <= now && deleteItem(folder)) expired += item.original
            }
        }
        expired
    }

    /** Returns the item folder the file went to, or null if it stayed where it was. */
    private fun move(image: ImageRef, compressedCopy: ImageRef? = null, favorite: Boolean = false): File? {
        val file = File(image.path)
        if (file.name == ORIGIN) return null
        if (!file.isFile || runCatching { file.toImageRef() }.getOrNull() != image) return null
        val root = rootOf(file) ?: return null
        val folder = File(root, UUID.randomUUID().toString())
        try {
            if (!folder.mkdirs()) return null
            File(root, ".nomedia").createNewFile()
            val now = clock.instant()
            recordWriter(folder, image, now, now, compressedCopy, favorite)
            if (file.renameTo(File(folder, file.name))) return folder
        } catch (_: IOException) {
        }
        deleteItem(folder)
        return null
    }

    private fun items(): List<TrashedImage> = volumes().flatMap { volume ->
        File(volume, TRASH_FOLDER).listFiles().orEmpty().mapNotNull { readItem(it) }
    }.sortedByDescending { it.trashedAt }

    private fun rootOf(file: File): File? {
        val path = file.canonicalPath
        val volume = volumes().firstOrNull { path.startsWith(it.canonicalPath + File.separator) } ?: return null
        return File(volume, TRASH_FOLDER)
    }

    private fun flagged(paths: Collection<String>): Set<String> = runCatching { favorites(paths) }.getOrDefault(emptySet())

    private fun writeOrigin(folder: File, image: ImageRef, trashedAt: Instant, seenAt: Instant, compressedCopy: ImageRef?, favorite: Boolean) {
        val origin = Properties()
        origin["path"] = image.path
        origin["size"] = image.size.toString()
        origin["mtime_ns"] = image.mtimeNs.toString()
        origin["trashed_at"] = trashedAt.toString()
        origin["seen_at"] = seenAt.toString()
        if (compressedCopy != null) {
            origin["compressed_size"] = compressedCopy.size.toString()
            origin["compressed_mtime_ns"] = compressedCopy.mtimeNs.toString()
        }
        if (favorite) origin["favorite"] = "true"
        File(folder, ORIGIN).outputStream().use { origin.store(it, "Cribrum trash: where this file came from") }
    }

    /** Null for anything the trash did not write, or that lost its file. */
    internal fun readItem(folder: File, syncSeenAt: Boolean = false): TrashedImage? = runCatching {
        val originFile = File(folder, ORIGIN)
        if (!originFile.isFile) return null
        val origin = Properties().apply { originFile.inputStream().use(::load) }
        val path = origin.getProperty("path") ?: return null
        val fileName = File(path).name
        if (fileName == ORIGIN) return null
        val file = File(folder, fileName).takeIf { it.isFile } ?: return null
        val now = clock.instant()
        val rawTrashedAt = origin.getProperty("trashed_at")?.let { runCatching { Instant.parse(it) }.getOrNull() } ?: return null
        val trashedAt = if (rawTrashedAt > now) now else rawTrashedAt
        val rawSeenAt = origin.parsedSeenAt()
        val seenAt = if (syncSeenAt && (rawSeenAt == null || rawSeenAt > now)) {
            origin["seen_at"] = now.toString()
            runCatching {
                originFile.outputStream().use { origin.store(it, "Cribrum trash: where this file came from") }
            }
            now
        } else {
            origin.seenAt(now)
        }
        val size = origin.getProperty("size")?.toLongOrNull() ?: return null
        val mtimeNs = origin.getProperty("mtime_ns")?.toLongOrNull() ?: return null
        val purgeAt = maxOf(trashedAt, seenAt) + retention()
        val compressedSize = origin.getProperty("compressed_size")?.toLongOrNull()
        val compressedMtimeNs = origin.getProperty("compressed_mtime_ns")?.toLongOrNull()
        TrashedImage(
            ImageRef(path, size, mtimeNs),
            trashedAt,
            file,
            purgeAt,
            seenAt,
            if (compressedSize != null && compressedMtimeNs != null) ImageRef(path, compressedSize, compressedMtimeNs) else null,
            origin.getProperty("favorite") == "true",
        )
    }.getOrNull()

    /** Deletes one item folder of the trash, never anything outside it. Returns false if it could not delete all of it. */
    internal fun deleteItem(folder: File): Boolean {
        val trash = folder.canonicalFile.parentFile
        check(trash?.name == TRASH_FOLDER) { "not a trash item: $folder" }
        return folder.deleteRecursively()
    }

    companion object {
        private const val ORIGIN = "origin.properties"

        fun forDevice(
            context: Context,
            clock: Clock = Clock.systemUTC(),
            retention: () -> Duration = { TrashRetention.DEFAULT },
        ): AppTrash {
            val storage = context.getSystemService(StorageManager::class.java)
            return AppTrash(
                volumes = { storage.storageVolumes.mapNotNull { it.directory } },
                clock = clock,
                retention = retention,
                favorites = { queryFavoritesAmong(context.contentResolver, it) },
                restoredFavorites = { paths ->
                    try {
                        FavoriteRequests.ask(context, paths)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        // The restore itself worked: only the mark stays missing.
                        Log.w("trash", "Could not ask for the favourites of a restore", e)
                    }
                },
                changed = { paths ->
                    if (paths.isNotEmpty()) MediaScannerConnection.scanFile(context, paths.toTypedArray(), null, null)
                },
            )
        }
    }
}

private fun Properties.parsedSeenAt(): Instant? = getProperty("seen_at")?.let { runCatching { Instant.parse(it) }.getOrNull() }

private fun Properties.seenAt(now: Instant): Instant = parsedSeenAt()?.takeIf { it <= now } ?: now

private suspend fun <T> io(block: () -> T): T = withContext(Dispatchers.IO) { block() }
