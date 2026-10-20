package io.github.bert0ns.cribrum.sources

import android.content.Context
import android.media.MediaScannerConnection
import android.os.Environment
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch

/** The deep scan finds what MediaStore misses: images in `.nomedia` folders, like WhatsApp's Sent. */
class DeepScanDeviceTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val folder = File(Environment.getExternalStorageDirectory(), "Pictures/cribrum-test")

    @Before
    fun grantAllFilesAccess() {
        InstrumentationRegistry.getInstrumentation().uiAutomation
            .executeShellCommand("appops set --uid ${context.packageName} MANAGE_EXTERNAL_STORAGE allow")
            .close()
        Thread.sleep(500)
        assertTrue("All files access was not granted", Environment.isExternalStorageManager())
    }

    @After
    fun cleanUp() {
        folder.deleteRecursively()
    }

    @Test
    fun findsImagesInNomediaFolders() = runBlocking {
        val sent = File(folder, "WhatsApp Images/Sent").apply { mkdirs() }
        File(sent, ".nomedia").createNewFile()
        val image = File(sent, "IMG-20261019-WA0001.jpg").apply { writeBytes(byteArrayOf(-1, -40, -1, -32) + ByteArray(64)) }
        val scanned = CountDownLatch(1)
        MediaScannerConnection.scanFile(context, arrayOf(image.path), null) { _, _ -> scanned.countDown() }
        scanned.await()

        val deep = FilesystemImageSource(folder).scan().toList().map { it.path }
        val media = MediaStoreImageSource(context.contentResolver).scan().toList().map { it.path }

        assertTrue(image.path in deep)
        assertFalse(image.path in media)
    }
}
