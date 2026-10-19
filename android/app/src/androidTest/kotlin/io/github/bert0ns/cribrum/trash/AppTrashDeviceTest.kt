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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/** Move, restore and purge on the phone's real shared storage, with All files access. */
class AppTrashDeviceTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val folder = File(Environment.getExternalStorageDirectory(), "Pictures/cribrum-test")
    private val trash = AppTrash.forDevice(context)

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
    fun cleanUp() = runBlocking {
        trash.purge(trash.trashed().map { it.original }.filter { it.path.startsWith(folder.path) })
        folder.deleteRecursively()
    }

    private fun photo(name: String): ImageRef = File(folder, name).apply { writeBytes(ByteArray(1024) { it.toByte() }) }.toImageRef()

    @Test
    fun moveAndRestore() = runBlocking {
        val image = photo("a.jpg")

        assertEquals(listOf(image), trash.moveToTrash(listOf(image)))
        assertFalse(File(image.path).exists())
        val item = trash.trashed().single { it.original == image }
        assertTrue(item.file.path.startsWith(File(Environment.getExternalStorageDirectory(), TRASH_FOLDER).path))

        assertEquals(listOf(image), trash.restore(listOf(image)))
        assertEquals(image, File(image.path).toImageRef())
    }

    @Test
    fun purgeAfterRetention() = runBlocking {
        val image = photo("b.jpg")
        trash.moveToTrash(listOf(image))
        val later = Clock.fixed(Instant.now() + RETENTION, ZoneOffset.UTC)

        assertEquals(listOf(image), AppTrash.forDevice(context, later).purgeExpired().filter { it == image })
        assertTrue(trash.trashed().none { it.original == image })
    }
}
