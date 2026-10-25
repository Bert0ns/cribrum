package io.github.bert0ns.cribrum.trash

import io.github.bert0ns.cribrum.model.ImageRef
import io.github.bert0ns.cribrum.model.toImageRef
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.Properties
import java.util.UUID

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
    private var retention = Duration.ofDays(30)
    private val trash by lazy { AppTrash({ listOf(volume) }, clock, { retention }) { changed += it } }

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
        assertEquals(clock.now, item.seenAt)
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
    fun `a new retention applies to images already in the trash`() = test {
        val image = photo("DCIM/l.jpg")
        moveToTrash(listOf(image))
        clock.now += Duration.ofDays(10)

        assertEquals(emptyList<ImageRef>(), purgeExpired())
        assertEquals(Instant.parse("2026-11-18T12:00:00Z"), trashed().single().purgeAt)

        retention = Duration.ofDays(7)

        assertEquals(Instant.parse("2026-10-26T12:00:00Z"), trashed().single().purgeAt)
        assertEquals(listOf(image), purgeExpired())
        assertTrue(trashed().isEmpty())
    }

    @Test
    fun `a longer retention keeps what the old one would have purged`() = test {
        val image = photo("DCIM/m.jpg")
        moveToTrash(listOf(image))
        clock.now += Duration.ofDays(40)
        retention = Duration.ofDays(60)

        assertEquals(emptyList<ImageRef>(), purgeExpired())
        assertEquals(listOf(image), trashed().map { it.original })
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

    @Test
    fun `refuses to move a file named origin properties`() = test {
        val originFile = photo("DCIM/origin.properties")

        assertEquals(emptyList<ImageRef>(), moveToTrash(listOf(originFile)))

        assertTrue(File(originFile.path).isFile)
        assertTrue(trashed().isEmpty())
    }

    @Test
    fun `corrupted or truncated origin properties is skipped without crashing`() = test {
        val trashRoot = File(volume, TRASH_FOLDER).apply { mkdirs() }

        // Empty (0-byte truncated) origin.properties
        val emptyFolder = File(trashRoot, UUID.randomUUID().toString()).apply { mkdirs() }
        File(emptyFolder, "test.jpg").writeText("data")
        File(emptyFolder, "origin.properties").writeText("")

        // Missing required fields
        val fieldlessFolder = File(trashRoot, UUID.randomUUID().toString()).apply { mkdirs() }
        File(fieldlessFolder, "test2.jpg").writeText("data")
        File(fieldlessFolder, "origin.properties").writeText("path=/DCIM/test2.jpg\n")

        // Malformed unicode / invalid properties
        val invalidFolder = File(trashRoot, UUID.randomUUID().toString()).apply { mkdirs() }
        File(invalidFolder, "test3.jpg").writeText("data")
        File(invalidFolder, "origin.properties").writeBytes(byteArrayOf(0x00, 0x1F, 0x7F))

        assertEquals(emptyList<TrashedImage>(), trashed())
        assertEquals(emptyList<ImageRef>(), purgeExpired())
    }

    @Test
    fun `trashed_at in the future is treated as now and not purged`() = test {
        val trashRoot = File(volume, TRASH_FOLDER).apply { mkdirs() }
        val folder = File(trashRoot, UUID.randomUUID().toString()).apply { mkdirs() }
        val imgFile = File(folder, "future.jpg").apply { writeText("img") }
        val futureInstant = clock.now + Duration.ofDays(10)

        val props = Properties().apply {
            setProperty("path", "${volume.path}/DCIM/future.jpg")
            setProperty("size", imgFile.length().toString())
            setProperty("mtime_ns", "1700000000000000000")
            setProperty("trashed_at", futureInstant.toString())
            setProperty("seen_at", clock.now.toString())
        }
        File(folder, "origin.properties").outputStream().use { props.store(it, null) }

        val items = trashed()
        assertEquals(1, items.size)
        // trashedAt in future is clamped to now
        assertEquals(clock.now, items.single().trashedAt)
        assertEquals(clock.now + retention, items.single().purgeAt)

        assertEquals(emptyList<ImageRef>(), purgeExpired())
        assertTrue(imgFile.exists())
    }

    @Test
    fun `clock moving back after trash rewrites seen_at and delays purge until retention passes`() = test {
        val image = photo("DCIM/clock_rewind.jpg")
        val trashedAt = clock.now
        moveToTrash(listOf(image))

        val folder = trashed().single().file.parentFile!!
        val originFile = File(folder, "origin.properties")

        // User turns the clock back 5 days
        clock.now -= Duration.ofDays(5)
        val rewoundNow = clock.now

        // purgeExpired detects now < seen_at and rewrites seen_at to now
        assertEquals(emptyList<ImageRef>(), purgeExpired())

        val updatedProps = Properties().apply { originFile.inputStream().use(::load) }
        assertEquals(rewoundNow.toString(), updatedProps.getProperty("seen_at"))
        assertEquals(trashedAt.toString(), updatedProps.getProperty("trashed_at"))

        // Advance clock by 31 days (retention is 30 days from rewoundNow, but 26 days from trashedAt)
        // At this point: seen_at + 30 days <= clock.now, but trashed_at + 30 days > clock.now!
        clock.now = rewoundNow + Duration.ofDays(28)
        assertEquals(emptyList<ImageRef>(), purgeExpired())
        assertTrue(trashed().isNotEmpty())

        // Advance clock so both trashed_at + 30 days and seen_at + 30 days have passed
        clock.now = trashedAt + Duration.ofDays(31)
        assertEquals(listOf(image), purgeExpired())
        assertTrue(trashed().isEmpty())
    }

    @Test
    fun `item lacking seen_at has seen_at written on purgeExpired and is not purged until retention passes`() = test {
        val trashRoot = File(volume, TRASH_FOLDER).apply { mkdirs() }
        val folder = File(trashRoot, UUID.randomUUID().toString()).apply { mkdirs() }
        val imgFile = File(folder, "legacy.jpg").apply { writeText("img") }
        val oldTrashedAt = clock.now - Duration.ofDays(45)

        val props = Properties().apply {
            setProperty("path", "${volume.path}/DCIM/legacy.jpg")
            setProperty("size", imgFile.length().toString())
            setProperty("mtime_ns", "1700000000000000000")
            setProperty("trashed_at", oldTrashedAt.toString())
            // No seen_at field
        }
        val originFile = File(folder, "origin.properties")
        originFile.outputStream().use { props.store(it, null) }

        // purgeExpired writes seen_at = clock.now and does not purge yet
        assertEquals(emptyList<ImageRef>(), purgeExpired())

        val loaded = Properties().apply { originFile.inputStream().use(::load) }
        assertEquals(clock.now.toString(), loaded.getProperty("seen_at"))

        // Clock advances by 31 days (retention 30 days from seen_at)
        clock.now += Duration.ofDays(31)
        val purged = purgeExpired()
        assertEquals(1, purged.size)
        assertEquals("${volume.path}/DCIM/legacy.jpg", purged.single().path)
        assertFalse(folder.exists())
    }

    @Test
    fun `a move whose record write fails cleans up the trash folder and leaves the file in place`() = test {
        val image = photo("DCIM/write_fail.jpg")
        recordWriter = { _, _, _, _ -> throw IOException("simulated disk full") }

        assertEquals(emptyList<ImageRef>(), moveToTrash(listOf(image)))

        assertTrue(File(image.path).exists())
        val trashRoot = File(volume, TRASH_FOLDER)
        assertEquals(emptyList<File>(), trashRoot.listFiles()?.filter { it.isDirectory }.orEmpty())
    }

    @Test
    fun `a move whose rename fails cleans up the trash folder and leaves the file in place`() = test {
        val image = photo("DCIM/rename_fail/photo.jpg")
        val sourceDir = File(image.path).parentFile!!
        sourceDir.setWritable(false)
        try {
            assertEquals(emptyList<ImageRef>(), moveToTrash(listOf(image)))
            assertTrue(File(image.path).exists())
            val trashRoot = File(volume, TRASH_FOLDER)
            assertEquals(emptyList<File>(), trashRoot.listFiles()?.filter { it.isDirectory }.orEmpty())
        } finally {
            sourceDir.setWritable(true)
        }
    }

    @Test
    fun `a restore whose rename fails leaves the item in the trash`() = test {
        val image = photo("DCIM/restore_fail/photo.jpg")
        moveToTrash(listOf(image))
        val item = trashed().single()

        val parentDir = File(image.path).parentFile!!
        parentDir.setReadOnly()
        try {
            assertEquals(emptyList<ImageRef>(), restore(listOf(image)))
            assertEquals(listOf(item), trashed())
            assertTrue(item.file.exists())
        } finally {
            parentDir.setWritable(true)
        }
    }

    @Test
    fun `deleteItem refuses a folder outside the trash`() = test {
        val outsideFolder = File(volume, "DCIM/Camera").apply { mkdirs() }
        File(outsideFolder, "photo.jpg").writeText("data")

        assertThrows(IllegalStateException::class.java) {
            deleteItem(outsideFolder)
        }

        assertTrue(outsideFolder.exists())
        assertTrue(File(outsideFolder, "photo.jpg").exists())
    }

    @Test
    fun `restore onto a path that appears between the check and the rename fails without overwriting`() = test {
        val image = photo("DCIM/collision.jpg", "original photo")
        moveToTrash(listOf(image))

        File(image.path).writeText("collision file")

        assertEquals(emptyList<ImageRef>(), restore(listOf(image)))

        assertEquals("collision file", File(image.path).readText())
        assertEquals(listOf(image), trashed().map { it.original })
    }
}
