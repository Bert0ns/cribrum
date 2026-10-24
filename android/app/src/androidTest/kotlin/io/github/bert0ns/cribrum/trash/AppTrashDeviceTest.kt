package io.github.bert0ns.cribrum.trash

import android.content.Context
import android.os.Environment
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import io.github.bert0ns.cribrum.model.ImageRef
import io.github.bert0ns.cribrum.model.toImageRef
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.Properties
import java.util.UUID

/** Move, restore and purge on the phone's real shared storage, with All files access. */
class AppTrashDeviceTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val storageRoot = Environment.getExternalStorageDirectory()
    private val realTrashFolder = File(storageRoot, TRASH_FOLDER)
    private val folder = File(storageRoot, "Pictures/cribrum-test")
    private fun testTrash(clock: Clock = Clock.systemUTC()) = AppTrash(volumes = { listOf(folder) }, clock = clock)
    private val trash = testTrash()
    private var createdRealTrashFolder = false
    private var sentinelDir: File? = null

    @Before
    fun grantAllFilesAccess() {
        InstrumentationRegistry.getInstrumentation().uiAutomation
            .executeShellCommand("appops set --uid ${context.packageName} MANAGE_EXTERNAL_STORAGE allow")
            .close()
        Thread.sleep(500)
        assertTrue("All files access was not granted", Environment.isExternalStorageManager())
        folder.mkdirs()
    }

    @After
    fun cleanUp() {
        sentinelDir?.deleteRecursively()
        sentinelDir = null
        if (createdRealTrashFolder && realTrashFolder.list()?.isEmpty() == true) {
            realTrashFolder.delete()
        }
        folder.deleteRecursively()
    }

    private fun photo(name: String): ImageRef = File(folder, name).apply { writeBytes(ByteArray(1024) { it.toByte() }) }.toImageRef()

    private fun plantRealTrashSentinel(): File {
        if (!realTrashFolder.exists()) {
            realTrashFolder.mkdirs()
            createdRealTrashFolder = true
        }
        val dir = File(realTrashFolder, "cribrum-test-${UUID.randomUUID()}").apply { mkdirs() }
        sentinelDir = dir
        val file = File(dir, "sentinel.jpg").apply { writeBytes(ByteArray(1024) { it.toByte() }) }
        val origin = Properties().apply {
            setProperty("path", File(storageRoot, "Pictures/sentinel.jpg").path)
            setProperty("size", "1024")
            setProperty("mtime_ns", "0")
            setProperty("trashed_at", Instant.EPOCH.toString())
        }
        File(dir, "origin.properties").outputStream().use {
            origin.store(it, "Cribrum trash: test item")
        }
        return file
    }

    @Test
    fun forDeviceReadsStorageVolumes() = runBlocking {
        assertNotNull(AppTrash.forDevice(context).trashed())
    }

    @Test
    fun moveAndRestore() = runBlocking {
        val image = photo("a.jpg")

        assertEquals(listOf(image), trash.moveToTrash(listOf(image)))
        assertFalse(File(image.path).exists())
        val item = trash.trashed().single { it.original == image }
        assertTrue(item.file.path.startsWith(File(folder, TRASH_FOLDER).path))

        assertEquals(listOf(image), trash.restore(listOf(image)))
        assertEquals(image, File(image.path).toImageRef())
    }

    @Test
    fun purgeAfterRetention() = runBlocking {
        val sentinelFile = plantRealTrashSentinel()
        assertTrue(
            "Sentinel must be recognized by device trash",
            AppTrash.forDevice(context).trashed().any { it.file == sentinelFile },
        )

        val image = photo("b.jpg")
        trash.moveToTrash(listOf(image))
        val later = Clock.fixed(Instant.now() + TrashRetention.DEFAULT, ZoneOffset.UTC)

        assertEquals(listOf(image), testTrash(later).purgeExpired())
        assertTrue(trash.trashed().none { it.original == image })
        assertTrue("Real device trash item must not be purged", sentinelFile.exists())
    }
}
