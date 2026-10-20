// The lab's tests/test_screenshot.py, translated.
package io.github.bert0ns.cribrum.detectors

import io.github.bert0ns.cribrum.metadata.ImageMetadata
import io.github.bert0ns.cribrum.model.Category
import io.github.bert0ns.cribrum.model.ImageRef
import io.github.bert0ns.cribrum.model.Signal
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

private val SCREEN = 1080 to 2400
private val PHOTO = 4000 to 3000

private fun analyze(
    path: String,
    size: Pair<Int, Int>,
    camera: Boolean = false,
    screenSizes: Set<Pair<Int, Int>> = SCREEN_SIZES,
): Signal {
    val meta = ImageMetadata(size.first, size.second, "image/jpeg", cameraModel = if (camera) "Pixel 8" else null)
    val context = AnalysisContext(metadata = { meta }, screenSizes = screenSizes)
    val signal = runBlocking { ScreenshotDetector().analyze(ImageRef(path, 0, 0), context) }.single()
    assertEquals(Category.SCREENSHOT, signal.kind)
    return signal
}

class ScreenshotDetectorTest {
    @Test
    fun `screenshot paths in several languages`() {
        for (path in listOf(
            "/sdcard/Pictures/Screenshots/IMG_0001.png",
            "/sdcard/DCIM/Screenshots/Screenshot_20240501-102030_Chrome.jpg",
            "/sdcard/Download/Screen Shot 2020-01-01 at 10.00.00.png",
            "/sdcard/Download/Schermata 2024-05-01 alle 10.20.30.png",
            "/sdcard/Download/Bildschirmfoto vom 2024-05-01.png",
            "/sdcard/Download/Capture d'écran 2024-05-01.png",
            "/sdcard/Download/Captura de pantalla 2024-05-01.png",
            "/sdcard/Download/СКРИНШОТ 2024.png",
        )) {
            val signal = analyze(path, PHOTO, camera = true)

            assertTrue(path, signal.score >= 0.9f)
            assertNotEquals(path, "", signal.evidence["path_match"])
        }
    }

    @Test
    fun `camera photo scores zero`() {
        val signal = analyze("/sdcard/DCIM/Camera/IMG_20240501_102030.jpg", PHOTO, camera = true)

        assertEquals(0f, signal.score)
        assertEquals(
            mapOf("path_match" to "", "size" to "4000x3000", "screen_size" to "no", "camera_exif" to "yes"),
            signal.evidence,
        )
    }

    @Test
    fun `folder outside the last three path parts is ignored`() {
        assertEquals(0f, analyze("/home/me/screenshots-project/data/DCIM/Camera/a.jpg", PHOTO, camera = true).score)
    }

    @Test
    fun `missing camera exif alone is weak evidence`() {
        val signal = analyze("/sdcard/Download/a.jpg", PHOTO)

        assertTrue(signal.score > 0f && signal.score < 0.5f)
        assertEquals("no", signal.evidence["camera_exif"])
    }

    @Test
    fun `screen size in either orientation`() {
        for (size in listOf(SCREEN, SCREEN.second to SCREEN.first)) {
            val signal = analyze("/sdcard/Download/a.jpg", size, camera = true)

            assertTrue(signal.score > 0f)
            assertEquals("yes", signal.evidence["screen_size"])
        }
    }

    @Test
    fun `screen size without camera exif scores higher than either alone`() {
        val both = analyze("/sdcard/Download/a.jpg", SCREEN).score
        val sizeOnly = analyze("/sdcard/Download/a.jpg", SCREEN, camera = true).score
        val noExifOnly = analyze("/sdcard/Download/a.jpg", PHOTO).score

        assertTrue(both > maxOf(sizeOnly, noExifOnly))
        assertTrue(both >= 0.5f)
    }

    @Test
    fun `every rule together scores highest`() {
        val path = "/sdcard/Pictures/Screenshots/Screenshot_20240501-102030.png"

        assertTrue(analyze(path, SCREEN).score > analyze(path, PHOTO, camera = true).score)
    }

    @Test
    fun `screen sizes come from the context`() {
        val signal = analyze("/sdcard/Download/a.jpg", 500 to 700, camera = true, screenSizes = setOf(500 to 700))

        assertEquals("yes", signal.evidence["screen_size"])
    }

    @Test
    fun `screen sizes are the lab's`() {
        val lab = File("../../lab/src/cribrum_lab/screens.py").readText()
        val sizes = Regex("""\((\d+), (\d+)\)""").findAll(lab).map { it.groupValues[1].toInt() to it.groupValues[2].toInt() }

        assertEquals(sizes.toSet(), SCREEN_SIZES)
    }
}
