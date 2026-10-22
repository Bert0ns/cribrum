package io.github.bert0ns.cribrum.ui

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.github.bert0ns.cribrum.model.Category
import io.github.bert0ns.cribrum.model.GROUP_KEY
import io.github.bert0ns.cribrum.model.Protection
import io.github.bert0ns.cribrum.model.ROLE_KEY
import io.github.bert0ns.cribrum.model.Signal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ReasonTest {
    private fun line(category: Category, vararg evidence: Pair<String, String>): String? {
        val signal = Signal("d", 1, category, 0.9f, mapOf(*evidence))
        return reasonOf(signal)?.let { ApplicationProvider.getApplicationContext<Context>().getString(it.id, *it.args) }
    }

    @Test
    fun `document with text says how much of the image it covers`() {
        assertEquals("Text on 23% of the image", line(Category.DOCUMENT, "probability" to "0.100", "text_coverage" to "0.2300"))
    }

    @Test
    fun `document with next to no text says it looks like one`() {
        assertEquals("Looks like a document, a slide or a note", line(Category.DOCUMENT, "probability" to "0.900", "text_coverage" to "0.0020"))
    }

    @Test
    fun `exposure names the case`() {
        assertEquals("Too dark", line(Category.BAD_EXPOSURE, "case" to "dark"))
        assertEquals("Too bright", line(Category.BAD_EXPOSURE, "case" to "bright"))
        assertEquals("Flat, with almost no contrast", line(Category.BAD_EXPOSURE, "case" to "flat"))
    }

    @Test
    fun `blurry says the subject is out of focus`() {
        assertEquals("Blurry, the subject is out of focus", line(Category.BLURRY, "global_variance" to "12.0", "peak_variance" to "30.0"))
    }

    @Test
    fun `sticker names its folder`() {
        assertEquals("In a sticker folder", line(Category.STICKER, "sticker_folder" to "yes"))
    }

    @Test
    fun `screenshot gives the strongest rule that matched`() {
        assertEquals(
            "Named like a screenshot (Screenshot_2024)",
            line(Category.SCREENSHOT, "path_match" to "Screenshot_2024", "screen_size" to "yes", "camera_exif" to "no", "size" to "1080x2400"),
        )
        assertEquals(
            "The size of a phone screen (1080×2400)",
            line(Category.SCREENSHOT, "path_match" to "", "screen_size" to "yes", "camera_exif" to "no", "size" to "1080x2400"),
        )
        assertEquals(
            "No camera data, like a screenshot",
            line(Category.SCREENSHOT, "path_match" to "", "screen_size" to "no", "camera_exif" to "no", "size" to "1080x2400"),
        )
    }

    @Test
    fun `forwarded gives the strongest rule that matched`() {
        assertEquals(
            "Looks like a meme or a greeting image",
            line(Category.FORWARDED, "probability" to "0.800", "messaging_folder" to "yes", "camera_exif" to "no"),
        )
        assertEquals(
            "In a messaging app folder",
            line(Category.FORWARDED, "probability" to "0.100", "messaging_folder" to "yes", "camera_exif" to "no"),
        )
        assertEquals(
            "No camera data, like a forwarded image",
            line(Category.FORWARDED, "probability" to "0.100", "messaging_folder" to "no", "camera_exif" to "no"),
        )
    }

    @Test
    fun `burst tells the best shot from the others`() {
        assertEquals("Best of a burst of 4: the sharpest", line(Category.BURST, ROLE_KEY to "best", "shots" to "4"))
        assertEquals("One of a burst of 4, not the sharpest", line(Category.BURST, ROLE_KEY to "inferior", "shots" to "4"))
    }

    @Test
    fun `duplicates tell the best copy from the others`() {
        assertEquals("Identical to the best copy", line(Category.EXACT_DUPLICATE, GROUP_KEY to "g", ROLE_KEY to "inferior"))
        assertEquals("Best copy: the others are identical to it", line(Category.EXACT_DUPLICATE, GROUP_KEY to "g", ROLE_KEY to "best"))
        assertEquals("Looks like a copy of the best one", line(Category.NEAR_DUPLICATE, GROUP_KEY to "g", ROLE_KEY to "inferior", "distance" to "3"))
        assertEquals("Best copy: the others look like it", line(Category.NEAR_DUPLICATE, GROUP_KEY to "g", ROLE_KEY to "best", "distance" to "0"))
    }

    @Test
    fun `missing evidence still gives a plain line`() {
        assertEquals("Blurry, the subject is out of focus", line(Category.BLURRY))
        assertEquals("Looks like a document, a slide or a note", line(Category.DOCUMENT))
        assertEquals("Exposure looks wrong", line(Category.BAD_EXPOSURE))
    }

    @Test
    fun `a protection has no reason line`() {
        assertNull(reasonOf(Signal("faces", 1, Protection.FACES, 0.9f)))
    }
}
