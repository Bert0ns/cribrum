package io.github.bert0ns.cribrum.folders

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class FoldersTest {
    @Test
    fun `any folder of the path counts, the file name does not`() {
        assertTrue(isCameraFolder("/storage/emulated/0/DCIM/Camera/a.jpg"))
        assertTrue(isMessagingFolder("/storage/emulated/0/Android/media/com.whatsapp/WhatsApp/Media/a.jpg"))
        assertTrue(isMessagingFolder("/storage/emulated/0/Pictures/Telegram/a.jpg"))
        assertFalse(isCameraFolder("/storage/emulated/0/Download/DCIM"))
        assertFalse(isMessagingFolder("/storage/emulated/0/Download/whatsapp.jpg"))
    }

    @Test
    fun `messaging folders are the lab's`() {
        val lab = File("../../lab/src/cribrum_lab/folders.py").readText()
        val block = lab.substringAfter("_MESSAGING_FOLDERS = {").substringBefore("}")

        assertEquals(Regex("\"([^\"]+)\"").findAll(block).map { it.groupValues[1] }.toSet(), MESSAGING_FOLDERS)
    }
}
