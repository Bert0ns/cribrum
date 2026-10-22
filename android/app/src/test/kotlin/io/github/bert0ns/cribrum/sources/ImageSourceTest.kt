// The lab's tests/test_sources.py, translated, plus what only the phone has.
package io.github.bert0ns.cribrum.sources

import io.github.bert0ns.cribrum.model.ImageRef
import io.github.bert0ns.cribrum.model.toImageRef
import io.github.bert0ns.cribrum.trash.TRASH_FOLDER
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
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
    fun `skips the Samsung Gallery recycle bin at the root only`() {
        write("Android/.Trash/com.sec.android.gallery3d/a.jpg", JPEG)
        write("DCIM/Android/.Trash/b.jpg", JPEG)

        assertEquals(listOf("DCIM/Android/.Trash/b.jpg"), scanned())
    }

    @Test
    fun `merged sources yield each path once, in source order`() {
        fun source(vararg paths: String) = object : ImageSource {
            override fun scan(): Flow<ImageRef> = flowOf(*paths.map { ImageRef(it, 1, 1) }.toTypedArray())
        }

        val merged = MergedImageSource(listOf(source("/a.jpg", "/b.jpg"), source("/b.jpg", "/sd/c.jpg")))

        assertEquals(listOf("/a.jpg", "/b.jpg", "/sd/c.jpg"), runBlocking { merged.scan().toList() }.map { it.path })
    }
}
