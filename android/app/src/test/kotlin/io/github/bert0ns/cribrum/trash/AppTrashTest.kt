package io.github.bert0ns.cribrum.trash

import io.github.bert0ns.cribrum.model.ImageRef
import io.github.bert0ns.cribrum.model.toImageRef
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

private class MovableClock(var now: Instant) : Clock() {
    override fun instant() = now
    override fun getZone() = ZoneOffset.UTC
    override fun withZone(zone: java.time.ZoneId) = this
}

class AppTrashTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val volume by lazy { folder.newFolder("storage") }
    private val clock = MovableClock(Instant.parse("2026-10-19T12:00:00Z"))
    private val changed = mutableListOf<String>()
    private val trash by lazy { AppTrash({ listOf(volume) }, clock) { changed += it } }

    private fun photo(path: String, bytes: String = "jpeg bytes"): ImageRef {
        val file = File(volume, path)
        file.parentFile!!.mkdirs()
        file.writeText(bytes)
        file.setLastModified(1_700_000_000_000)
        return file.toImageRef()
    }

    private fun test(block: suspend AppTrash.() -> Unit) = runBlocking { trash.block() }

    @Test
    fun `moving takes the file out of its folder and keeps where it was`() = test {
        val image = photo("DCIM/Camera/a.jpg")

        assertEquals(listOf(image), moveToTrash(listOf(image)))

        assertFalse(File(image.path).exists())
        val item = trashed().single()
        assertEquals(image, item.original)
        assertEquals(clock.now, item.trashedAt)
        assertEquals("a.jpg", item.file.name)
        assertTrue(File(volume, "$TRASH_FOLDER/.nomedia").exists())
        assertEquals(listOf(image.path), changed)
    }

    @Test
    fun `restoring puts back the same file with the same size and mtime`() = test {
        val image = photo("WhatsApp/Media/WhatsApp Images/Sent/b.jpg")
        moveToTrash(listOf(image))

        assertEquals(listOf(image), restore(listOf(image)))

        assertEquals(image, File(image.path).toImageRef())
        assertEquals(emptyList<TrashedImage>(), trashed())
        assertEquals(listOf(volume.resolve(TRASH_FOLDER).resolve(".nomedia")), volume.resolve(TRASH_FOLDER).listFiles()!!.toList())
    }

    @Test
    fun `restoring recreates a folder that is gone`() = test {
        val image = photo("Pictures/Old/c.jpg")
        moveToTrash(listOf(image))
        File(volume, "Pictures/Old").delete()

        assertEquals(listOf(image), restore(listOf(image)))

        assertTrue(File(image.path).isFile)
    }

    @Test
    fun `restoring never overwrites a file that took the original path`() = test {
        val image = photo("DCIM/d.jpg")
        moveToTrash(listOf(image))
        photo("DCIM/d.jpg", "a new photo")

        assertEquals(emptyList<ImageRef>(), restore(listOf(image)))

        assertEquals("a new photo", File(image.path).readText())
        assertEquals(listOf(image), trashed().map { it.original })
    }

    @Test
    fun `a file changed or gone since its scan is not moved`() = test {
        val changedFile = photo("DCIM/e.jpg")
        File(changedFile.path).writeText("edited since the scan")
        val gone = photo("DCIM/f.jpg")
        File(gone.path).delete()

        assertEquals(emptyList<ImageRef>(), moveToTrash(listOf(changedFile, gone)))

        assertTrue(File(changedFile.path).exists())
        assertEquals(emptyList<TrashedImage>(), trashed())
    }

    @Test
    fun `a file outside every volume is not moved`() = test {
        val outside = File(folder.root, "elsewhere.jpg").apply { writeText("x") }.toImageRef()

        assertEquals(emptyList<ImageRef>(), moveToTrash(listOf(outside)))

        assertTrue(File(outside.path).exists())
    }

    @Test
    fun `two files with the same name are kept apart`() = test {
        val first = photo("DCIM/g.jpg", "first")
        val second = photo("Download/g.jpg", "second")

        moveToTrash(listOf(first, second))
        restore(listOf(first, second))

        assertEquals("first", File(first.path).readText())
        assertEquals("second", File(second.path).readText())
    }

    @Test
    fun `purging after 30 days deletes only what expired`() = test {
        val old = photo("DCIM/old.jpg")
        moveToTrash(listOf(old))
        clock.now += Duration.ofDays(10)
        val recent = photo("DCIM/recent.jpg")
        moveToTrash(listOf(recent))

        clock.now += Duration.ofDays(20)

        assertEquals(listOf(old), purgeExpired())
        assertEquals(listOf(recent), trashed().map { it.original })
        assertEquals(
            listOf(".nomedia", trashed().single().file.parentFile!!.name).sorted(),
            volume.resolve(TRASH_FOLDER).list()!!.sorted(),
        )
    }

    @Test
    fun `purging chosen images deletes them now`() = test {
        val image = photo("DCIM/h.jpg")
        val other = photo("DCIM/i.jpg")
        moveToTrash(listOf(image, other))

        purge(listOf(image))

        assertEquals(listOf(other), trashed().map { it.original })
        assertFalse(File(image.path).exists())
    }

    @Test
    fun `the trash survives a new instance, as after a reinstall`() {
        val image = photo("DCIM/j.jpg")
        runBlocking { trash.moveToTrash(listOf(image)) }

        val again = AppTrash({ listOf(volume) }, clock)

        assertEquals(listOf(image), runBlocking { again.restore(listOf(image)) })
    }

    @Test
    fun `folders the trash did not write are left alone`() = test {
        val stranger = File(volume, "$TRASH_FOLDER/not-ours").apply { mkdirs() }
        File(stranger, "k.jpg").writeText("x")

        assertEquals(emptyList<TrashedImage>(), trashed())
        clock.now += Duration.ofDays(365)
        purgeExpired()

        assertTrue(File(stranger, "k.jpg").exists())
    }
}
