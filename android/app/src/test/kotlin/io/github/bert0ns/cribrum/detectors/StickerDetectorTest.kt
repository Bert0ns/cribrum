// The lab's tests/test_sticker.py, translated.
package io.github.bert0ns.cribrum.detectors

import io.github.bert0ns.cribrum.model.Category
import io.github.bert0ns.cribrum.model.ImageRef
import io.github.bert0ns.cribrum.model.Signal
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

private const val MEDIA = "/sdcard/Android/media/com.whatsapp/WhatsApp/Media"

private fun analyze(path: String): Signal {
    val signal = runBlocking { StickerDetector().analyze(ImageRef(path, 0, 0), AnalysisContext()) }.single()
    assertEquals(Category.STICKER, signal.kind)
    return signal
}

class StickerDetectorTest {
    @Test
    fun `images in a messaging sticker folder are stickers`() {
        for (path in listOf(
            "$MEDIA/WhatsApp Stickers/STK-20240501-WA0001.webp",
            "$MEDIA/WhatsApp Backup Excluded Stickers/STK-20240501-WA0002.webp",
            "/sdcard/WhatsApp Business/Media/WhatsApp Business Stickers/a.webp",
        )) {
            val signal = analyze(path)

            assertEquals(path, 1f, signal.score)
            assertEquals(mapOf("sticker_folder" to "yes"), signal.evidence)
        }
    }

    @Test
    fun `other images are not`() {
        for (path in listOf(
            "$MEDIA/WhatsApp Images/IMG-20240501-WA0001.jpg",
            "/sdcard/Pictures/Stickers/mine.webp", // not a messaging app's folder
            "$MEDIA/WhatsApp Images/stickers.jpg", // the file name says nothing
        )) {
            assertEquals(path, 0f, analyze(path).score)
        }
    }
}
