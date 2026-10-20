// The lab's tests/test_exact_duplicate.py, translated.
package io.github.bert0ns.cribrum.detectors

import io.github.bert0ns.cribrum.model.Category
import io.github.bert0ns.cribrum.model.GROUP_KEY
import io.github.bert0ns.cribrum.model.ImageRef
import io.github.bert0ns.cribrum.model.ROLE_KEY
import io.github.bert0ns.cribrum.model.Role
import io.github.bert0ns.cribrum.model.Signal
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ExactDuplicateDetectorTest {
    @get:Rule
    val folder = TemporaryFolder()

    private fun write(name: String, content: String): ImageRef {
        val file = File(folder.root, name).apply { parentFile!!.mkdirs(); writeText(content) }
        return ImageRef(file.path, content.length.toLong(), 0)
    }

    private fun run(images: List<ImageRef>, context: AnalysisContext = AnalysisContext()) =
        runBlocking { ExactDuplicateDetector().analyze(images, context) }

    private fun Map<ImageRef, List<Signal>>.evidence(image: ImageRef, key: String) = getValue(image).single().evidence[key]

    @Test
    fun `identical files form a group with one best copy`() {
        val camera = write("DCIM/Camera/a.jpg", "photo")
        val forwarded = write("WhatsApp/Media/WhatsApp Images/b.jpg", "photo")
        val downloaded = write("Download/c.jpg", "photo")

        val signals = run(listOf(forwarded, downloaded, camera))

        assertEquals(
            mapOf(camera to "best", forwarded to "inferior", downloaded to "inferior"),
            signals.keys.associateWith { signals.evidence(it, ROLE_KEY) },
        )
        assertEquals(1, signals.keys.map { signals.evidence(it, GROUP_KEY) }.toSet().size)
        assertEquals(0f, signals.getValue(camera).single().score)
        assertEquals(1f, signals.getValue(forwarded).single().score)
        assertTrue(signals.values.all { it.single().kind == Category.EXACT_DUPLICATE })
    }

    @Test
    fun `messaging copy loses to any other folder`() {
        val received = write("Android/media/com.whatsapp/WhatsApp/Media/a.jpg", "meme")
        val telegram = write("Telegram/Telegram Images/b.jpg", "meme")
        val saved = write("Pictures/c.jpg", "meme")

        assertEquals(Role.BEST.value, run(listOf(received, telegram, saved)).evidence(saved, ROLE_KEY))
    }

    @Test
    fun `same size with different content is not a duplicate`() {
        assertEquals(emptyMap<ImageRef, List<Signal>>(), run(listOf(write("a.jpg", "aaaa"), write("b.jpg", "bbbb"))))
    }

    @Test
    fun `unique sizes are never hashed`() {
        val hashed = mutableListOf<ImageRef>()
        val context = AnalysisContext(contentHash = { hashed += it; "same" })

        assertEquals(emptyMap<ImageRef, List<Signal>>(), run(listOf(write("a.jpg", "a"), write("b.jpg", "bb")), context))
        assertEquals(emptyList<ImageRef>(), hashed)
    }

    @Test
    fun `separate groups get separate ids`() {
        val images = listOf(write("a1.jpg", "aaaa"), write("a2.jpg", "aaaa"), write("b1.jpg", "bbbb"), write("b2.jpg", "bbbb"))

        val signals = run(images)

        val groups = images.map { signals.evidence(it, GROUP_KEY) }
        assertEquals(groups[0], groups[1])
        assertNotEquals(groups[1], groups[2])
        assertEquals(groups[2], groups[3])
    }

    @Test
    fun `file gone since the scan is skipped`() {
        val a = write("a.jpg", "photo")
        val b = write("b.jpg", "photo")
        val gone = write("c.jpg", "photo")
        File(gone.path).delete()

        assertEquals(setOf(a, b), run(listOf(a, b, gone)).keys)
    }
}
