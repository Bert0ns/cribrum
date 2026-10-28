// The lab's tests/test_sources.py, translated, plus what only the phone has.
package io.github.bert0ns.cribrum.sources

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.provider.MediaStore
import androidx.test.core.app.ApplicationProvider
import io.github.bert0ns.cribrum.model.ImageRef
import io.github.bert0ns.cribrum.model.toImageRef
import io.github.bert0ns.cribrum.trash.TRASH_FOLDER
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.nio.file.Files

private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }
private fun ascii(text: String) = text.toByteArray(Charsets.ISO_8859_1)
private val ZEROS = ByteArray(16)

private val JPEG = bytes(0xff, 0xd8, 0xff, 0xe0) + ZEROS
private val PNG = bytes(0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a) + ZEROS
private val GIF = ascii("GIF89a") + ZEROS
private val WEBP = ascii("RIFF") + ByteArray(4) + ascii("WEBPVP8 ") + ByteArray(8)
private val HEIC = bytes(0, 0, 0, 0x18) + ascii("ftypheic") + ByteArray(12)

/** MIAF profile: the HEIF brand appears only among the compatible brands. */
private val MIAF_HEIC = bytes(0, 0, 0, 0x1c) + ascii("ftypmiaf") + ByteArray(4) + ascii("mif1heic") + ByteArray(8)
private val AVIF = bytes(0, 0, 0, 0x1c) + ascii("ftypavif") + ByteArray(4) + ascii("mif1miaf") + ByteArray(8)
private val MP4 = bytes(0, 0, 0, 0x18) + ascii("ftypisom") + ByteArray(12)

class ImageSourceTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val root by lazy { folder.root }

    private fun write(path: String, content: ByteArray): File = File(root, path).apply {
        parentFile!!.mkdirs()
        writeBytes(content)
    }

    private fun scanned(): List<String> = runBlocking { FilesystemImageSource(root).scan().toList() }.map { File(it.path).relativeTo(root).path }

    @Test
    fun `finds every supported format`() {
        listOf("a.jpg" to JPEG, "b.png" to PNG, "c.gif" to GIF, "d.webp" to WEBP, "e.heic" to HEIC)
            .forEach { (name, content) -> write(name, content) }

        assertEquals(listOf("a.jpg", "b.png", "c.gif", "d.webp", "e.heic"), scanned())
    }

    @Test
    fun `finds heif declared by a compatible brand`() {
        write("a.heic", MIAF_HEIC)
        write("b.avif", AVIF)

        assertEquals(listOf("a.heic", "b.avif"), scanned())
    }

    @Test
    fun `includes nomedia and hidden folders`() {
        write("WhatsApp Images/Sent/.nomedia", ByteArray(0))
        write("WhatsApp Images/Sent/IMG-1.jpg", JPEG)
        write(".thumbnails/t.jpg", JPEG)

        assertEquals(setOf("WhatsApp Images/Sent/IMG-1.jpg", ".thumbnails/t.jpg"), scanned().toSet())
    }

    @Test
    fun `detects images by content not extension`() {
        write("no_extension", JPEG)
        write("photo.jpg", PNG)
        write("fake.jpg", ascii("not an image"))
        write("clip.mp4", MP4)
        write("empty.jpg", ByteArray(0))
        write("short.heic", bytes(0, 0, 0, 0x0a) + ascii("ftyp"))

        assertEquals(listOf("no_extension", "photo.jpg"), scanned())
    }

    @Test
    fun `image ref identifies the file`() {
        val file = write("a.jpg", JPEG)

        assertEquals(listOf(file.toImageRef()), runBlocking { FilesystemImageSource(root).scan().toList() })
    }

    @Test
    fun `skips symlinks to files and folders`() {
        val target = write("a/a.jpg", JPEG)
        Files.createSymbolicLink(File(root, "link.jpg").toPath(), target.toPath())
        Files.createSymbolicLink(File(root, "linked-folder").toPath(), target.parentFile!!.toPath())

        assertEquals(listOf("a/a.jpg"), scanned())
    }

    @Test
    fun `skips special and unreadable files`() {
        ProcessBuilder("mkfifo", File(root, "pipe.jpg").path).start().waitFor() // reading would block forever
        write("locked.jpg", JPEG).setReadable(false)
        write("ok.jpg", JPEG)

        assertEquals(listOf("ok.jpg"), scanned())
    }

    @Test
    fun `scan order is deterministic`() {
        listOf("b/2.jpg", "b/1.jpg", "a/9.jpg", "c.jpg").forEach { write(it, JPEG) }

        assertEquals(listOf("c.jpg", "a/9.jpg", "b/1.jpg", "b/2.jpg"), scanned())
    }

    @Test
    fun `skips the system trash, pending files and the app trash`() {
        write("DCIM/Camera/.trashed-1760000000-IMG_1.jpg", JPEG)
        write("DCIM/Camera/.pending-1760000000-IMG_2.jpg", JPEG)
        write("$TRASH_FOLDER/0b1c/IMG_3.jpg", JPEG)
        write("DCIM/Camera/IMG_4.jpg", JPEG)

        assertEquals(listOf("DCIM/Camera/IMG_4.jpg"), scanned())
    }

    @Test
    fun `skips the app-private folders Android keeps from every app`() {
        write("Android/data/com.example/cache/a.jpg", JPEG)
        write("Android/obb/com.example/b.jpg", JPEG)
        write("Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Images/c.jpg", JPEG)

        assertEquals(listOf("Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Images/c.jpg"), scanned())
    }

    @Test
    fun `skips a skipped folder wherever it is, and everything below it`() {
        write("DCIM/$TRASH_FOLDER/0b1c/a.jpg", JPEG)
        write("$TRASH_FOLDER/Android/data/b.jpg", JPEG)
        write("Android/data/com.example/$TRASH_FOLDER/c.jpg", JPEG)
        write("Android/.Trash/Android/obb/d.jpg", JPEG)
        write("Android/media/e.jpg", JPEG)

        assertEquals(listOf("Android/media/e.jpg"), scanned())
    }

    @Test
    fun `keeps the folders that only look like a skipped one`() {
        write("DCIM/Android/data/a.jpg", JPEG)
        write("Pictures/Android/obb/b.jpg", JPEG)
        write("Android/dataset/c.jpg", JPEG)

        assertEquals(listOf("Android/dataset/c.jpg", "DCIM/Android/data/a.jpg", "Pictures/Android/obb/b.jpg"), scanned())
    }

    @Test
    fun `skips prefixed names in nested folders, and walks a prefixed folder`() {
        write("a/b/.trashed-1760000000-IMG_1.jpg", JPEG)
        write("a/b/.pending-1760000000-IMG_2.jpg", JPEG)
        write("a/b/IMG_3.jpg", JPEG)
        write("a/.trashed-folder/IMG_4.jpg", JPEG) // only file names carry the prefixes

        assertEquals(listOf("a/.trashed-folder/IMG_4.jpg", "a/b/IMG_3.jpg"), scanned())
    }

    @Test
    fun `skips the Samsung Gallery recycle bin at the root only`() {
        write("Android/.Trash/com.sec.android.gallery3d/a.jpg", JPEG)
        write("DCIM/Android/.Trash/b.jpg", JPEG)

        assertEquals(listOf("DCIM/Android/.Trash/b.jpg"), scanned())
    }

    private fun scannedKnowing(vararg known: ImageRef): List<String> = runBlocking { FilesystemImageSource(root) { known.toList() }.scan().toList() }.map { File(it.path).relativeTo(root).path }

    @Test
    fun `takes a file the index has with the same size and mtime as an image without reading it`() {
        val fake = write("fake.jpg", ascii("not an image")) // the head would say no

        assertEquals(listOf("fake.jpg"), scannedKnowing(fake.toImageRef()))
    }

    @Test
    fun `sniffs a known file again when its size or mtime changed`() {
        val sized = write("sized.jpg", ascii("not an image"))
        val touched = write("touched.jpg", ascii("not an image"))
        val edited = write("edited.jpg", JPEG)
        val before = listOf(sized, touched, edited).map { it.toImageRef() }

        sized.appendText("!")
        touched.setLastModified(touched.lastModified() + 5_000)
        edited.appendBytes(ZEROS)

        assertEquals(listOf("edited.jpg"), scannedKnowing(*before.toTypedArray()))
    }

    @Test
    fun `reads the known files once per scan, not once per file`() {
        listOf("a.jpg", "b.jpg", "c.jpg").forEach { write(it, JPEG) }
        var reads = 0
        val source = FilesystemImageSource(root) {
            reads++
            emptyList()
        }

        runBlocking { source.scan().toList() }

        assertEquals(1, reads)
    }

    @Test
    fun `reports its root as walked once the whole tree is listed`() {
        write("a/b.jpg", JPEG)
        val source = FilesystemImageSource(root)

        runBlocking { source.scan().toList() }

        assertEquals(listOf(root), source.walkedRoots)
    }

    @Test
    fun `reports no walked root when a folder cannot be listed`() {
        write("a/b.jpg", JPEG)
        write("c.jpg", JPEG)
        val locked = File(root, "a").apply { setReadable(false) }
        assumeTrue("the folder is still listable (running as root?)", locked.listFiles() == null)
        val source = FilesystemImageSource(root)

        val found = try {
            runBlocking { source.scan().toList() }
        } finally {
            locked.setReadable(true)
        }

        assertEquals(listOf(File(root, "c.jpg").path), found.map { it.path })
        assertEquals(emptyList<File>(), source.walkedRoots)
    }

    @Test
    fun `reports no walked root when the root is missing`() {
        val source = FilesystemImageSource(File(root, "gone"))

        runBlocking { source.scan().toList() }

        assertEquals(emptyList<File>(), source.walkedRoots)
    }

    @Test
    fun `merged sources report the roots of the sources that walked`() {
        write("a/x.jpg", JPEG)
        val present = FilesystemImageSource(File(root, "a"))
        val missing = FilesystemImageSource(File(root, "card"))
        val merged = MergedImageSource(listOf(present, missing))

        runBlocking { merged.scan().toList() }

        assertEquals(listOf(File(root, "a")), merged.walkedRoots)
    }

    @Test
    fun `merged sources yield each path once, in source order`() {
        fun source(vararg paths: String) = object : ImageSource {
            override val walkedRoots = emptyList<File>()
            override fun scan(): Flow<ImageRef> = flowOf(*paths.map { ImageRef(it, 1, 1) }.toTypedArray())
        }

        val merged = MergedImageSource(listOf(source("/a.jpg", "/b.jpg"), source("/b.jpg", "/sd/c.jpg")))

        assertEquals(listOf("/a.jpg", "/b.jpg", "/sd/c.jpg"), runBlocking { merged.scan().toList() }.map { it.path })
    }
}

/** A MediaStore that answers every query with the same paths. */
class FakeMedia : ContentProvider() {
    companion object {
        var paths: List<String?> = emptyList()
    }

    override fun onCreate() = true
    override fun query(uri: Uri, projection: Array<String>?, selection: String?, args: Array<String>?, order: String?): Cursor = MatrixCursor(arrayOf(MediaStore.Images.Media.DATA)).apply { paths.forEach { addRow(arrayOf(it)) } }
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, args: Array<String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<String>?) = 0
}

@RunWith(RobolectricTestRunner::class)
class MediaStoreImageSourceTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val root by lazy { folder.root }

    private fun write(path: String): String = File(root, path).apply {
        parentFile!!.mkdirs()
        writeBytes(JPEG)
    }.path

    private fun scanned(vararg paths: String?): List<String> {
        FakeMedia.paths = paths.toList()
        Robolectric.setupContentProvider(FakeMedia::class.java, MediaStore.AUTHORITY)
        val resolver = ApplicationProvider.getApplicationContext<Context>().contentResolver
        return runBlocking { MediaStoreImageSource(resolver, listOf(root)).scan().toList() }.map { it.path }
    }

    @Test
    fun `yields what MediaStore lists, in path order`() {
        val b = write("DCIM/b.jpg")
        val a = write("DCIM/a.jpg")

        assertEquals(listOf(a, b), scanned(b, a))
    }

    @Test
    fun `skips the app trash, the system trash and pending files`() {
        val trashed = write("$TRASH_FOLDER/0b1c/IMG_1.jpg")
        val systemTrash = write("DCIM/Camera/.trashed-1760000000-IMG_2.jpg")
        val pending = write("DCIM/Camera/.pending-1760000000-IMG_3.jpg")
        val kept = write("DCIM/Camera/IMG_4.jpg")

        assertEquals(listOf(kept), scanned(trashed, systemTrash, pending, kept))
    }

    @Test
    fun `skips the folders the deep scan skips, at the root of a volume only`() {
        val recycle = write("Android/.Trash/com.sec.android.gallery3d/a.jpg")
        val private = write("Android/data/com.example/files/b.jpg")
        val nested = write("DCIM/Android/.Trash/c.jpg")

        assertEquals(listOf(nested), scanned(recycle, private, nested))
    }

    @Test
    fun `ignores rows without a path or a file`() {
        val ok = write("a.jpg")

        assertEquals(listOf(ok), scanned(null, File(root, "missing.jpg").path, ok))
    }
}
