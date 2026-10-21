// The lab's tests/test_index.py, translated.
package io.github.bert0ns.cribrum.index

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.github.bert0ns.cribrum.metadata.ImageMetadata
import io.github.bert0ns.cribrum.model.Category
import io.github.bert0ns.cribrum.model.ImageRef
import io.github.bert0ns.cribrum.model.Protection
import io.github.bert0ns.cribrum.model.ReviewState
import io.github.bert0ns.cribrum.model.Signal
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.LocalDateTime

private val PHOTO = ImageRef("/photos/a.jpg", 100, 1)
private val OTHER = ImageRef("/photos/b.jpg", 5, 5)
private val META = ImageMetadata(4000, 3000, "image/jpeg", "Google", "Pixel 8", LocalDateTime.of(2024, 5, 1, 10, 20))
private val BLUR = Signal("blur", 1, Category.BLURRY, 0.7f, mapOf("variance" to "12.5"))

fun memoryIndex() = ImageIndex(
    Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), IndexDatabase::class.java).build(),
)

@RunWith(RobolectricTestRunner::class)
class ImageIndexTest {
    private val index = memoryIndex()

    @After
    fun close() = index.close()

    private fun test(block: suspend ImageIndex.() -> Unit) = runBlocking { index.block() }

    @Test
    fun `notTrashed leaves out trashed images`() = test {
        record(PHOTO)
        record(OTHER)
        setReviewState(OTHER.path, ReviewState.TRASHED)
        assertEquals(listOf(PHOTO), notTrashed())
        setReviewState(OTHER.path, ReviewState.UNREVIEWED)
        assertEquals(listOf(PHOTO, OTHER), notTrashed())
    }

    @Test
    fun `record reports new and changed images`() = test {
        assertTrue(record(PHOTO))
        assertFalse(record(PHOTO))
        assertTrue(record(ImageRef(PHOTO.path, 101, 1)))
        assertTrue(record(ImageRef(PHOTO.path, 101, 2)))
        assertEquals(listOf(ImageRef(PHOTO.path, 101, 2)), images())
    }

    @Test
    fun `changed image drops its stale data but keeps the review decision`() = test {
        record(PHOTO)
        setMetadata(PHOTO.path, META)
        setUnreadable(PHOTO.path)
        setContentHash(PHOTO.path, "0123abcd")
        setPerceptualHash(PHOTO.path, 42u)
        setEmbeddings("clip", 1, mapOf(PHOTO.path to FloatArray(4) { 1f }))
        setSignals(PHOTO.path, "blur", 1, listOf(BLUR))
        setReviewState(PHOTO.path, ReviewState.KEPT)

        record(ImageRef(PHOTO.path, 200, 5)) // edited, or just touched by a sync tool

        assertNull(metadata(PHOTO.path))
        assertEquals(emptyList<String>(), unreadable())
        assertNull(contentHash(PHOTO.path))
        assertNull(perceptualHash(PHOTO.path))
        assertEquals(emptyMap<String, FloatArray>(), embeddings("clip", 1))
        assertEquals(emptyList<Signal>(), signals(PHOTO.path))
        assertFalse(isAnalyzed(PHOTO.path, "blur", 1))
        assertEquals(ReviewState.KEPT, reviewState(PHOTO.path))
    }

    @Test
    fun `metadata round trip`() = test {
        record(PHOTO)
        for (meta in listOf(META, ImageMetadata(1080, 2400, "image/png"))) {
            setMetadata(PHOTO.path, meta)

            assertEquals(meta, metadata(PHOTO.path))
        }
    }

    @Test
    fun `content hash round trip`() = test {
        record(PHOTO)
        assertNull(contentHash(PHOTO.path))

        setContentHash(PHOTO.path, "0123abcd")

        assertEquals("0123abcd", contentHash(PHOTO.path))
    }

    @Test
    fun `perceptual hash round trip uses all 64 bits`() = test {
        record(PHOTO)
        assertNull(perceptualHash(PHOTO.path))

        setPerceptualHash(PHOTO.path, ULong.MAX_VALUE)

        assertEquals(ULong.MAX_VALUE, perceptualHash(PHOTO.path))
    }

    @Test
    fun `embeddings are stored per model version`() = test {
        record(PHOTO)
        val vector = floatArrayOf(0.5f, -0.25f, 1f)

        setEmbeddings("clip", 2, mapOf(PHOTO.path to vector))

        val stored = embeddings("clip", 2)
        assertEquals(setOf(PHOTO.path), stored.keys)
        assertArrayEquals(vector, stored.getValue(PHOTO.path), 0f)
        assertEquals(emptyMap<String, FloatArray>(), embeddings("clip", 1))
    }

    @Test
    fun `signals are stored per detector version`() = test {
        record(PHOTO)
        val screenshot = Signal("screenshot", 3, Category.SCREENSHOT, 0.9f)
        setSignals(PHOTO.path, "screenshot", 3, listOf(screenshot))
        setSignals(PHOTO.path, "blur", 1, listOf(Signal("blur", 1, Category.BLURRY, 0.2f)))

        setSignals(PHOTO.path, "blur", 2, listOf(BLUR))

        assertEquals(listOf(BLUR, screenshot), signals(PHOTO.path).sortedBy { it.detectorId })
        assertTrue(isAnalyzed(PHOTO.path, "blur", 2))
        assertFalse(isAnalyzed(PHOTO.path, "blur", 1))
    }

    @Test
    fun `detector with no signals still counts as analyzed`() = test {
        record(PHOTO)
        setSignals(PHOTO.path, "blur", 1, emptyList())

        assertTrue(isAnalyzed(PHOTO.path, "blur", 1))
    }

    @Test
    fun `review state persists`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        runBlocking {
            ImageIndex.open(context, "persist.sqlite").use {
                it.record(PHOTO)
                assertEquals(ReviewState.UNREVIEWED, it.reviewState(PHOTO.path))
                it.setReviewState(PHOTO.path, ReviewState.KEPT)
            }
            ImageIndex.open(context, "persist.sqlite").use {
                assertEquals(ReviewState.KEPT, it.reviewState(PHOTO.path))
            }
        }
    }

    @Test
    fun `unrecorded image throws`() {
        val calls: List<suspend ImageIndex.() -> Unit> = listOf(
            { setMetadata(PHOTO.path, META) },
            { setContentHash(PHOTO.path, "0123abcd") },
            { setUnreadable(PHOTO.path) },
            { setPerceptualHash(PHOTO.path, 42u) },
            { perceptualHash(PHOTO.path) },
            { contentHash(PHOTO.path) },
            { setSignals(PHOTO.path, "blur", 1, listOf(BLUR)) },
            { setReviewState(PHOTO.path, ReviewState.KEPT) },
            { reviewState(PHOTO.path) },
        )
        for (call in calls) {
            assertThrows(NoSuchElementException::class.java) { test(call) }
        }
    }

    @Test
    fun `keeping records the current signals`() = test {
        record(PHOTO)
        setSignals(PHOTO.path, "blur", 1, listOf(BLUR))

        setReviewState(PHOTO.path, ReviewState.KEPT)
        setSignals(PHOTO.path, "blur", 2, listOf(Signal("blur", 2, Category.BLURRY, 0.9f)))

        val image = indexedImages().single()
        assertEquals(ReviewState.KEPT, image.reviewState)
        assertEquals(listOf(Signal("blur", 2, Category.BLURRY, 0.9f)), image.signals)
        assertEquals(setOf(BLUR.identity), image.seenWhenKept)
    }

    @Test
    fun `leaving kept forgets the recorded signals`() = test {
        record(PHOTO)
        setSignals(PHOTO.path, "blur", 1, listOf(BLUR))
        setReviewState(PHOTO.path, ReviewState.KEPT)

        setReviewState(PHOTO.path, ReviewState.UNREVIEWED)

        assertEquals(emptySet<Any>(), indexedImages().single().seenWhenKept)
    }

    @Test
    fun `indexed images lists unanalyzed images too`() = test {
        record(PHOTO)
        record(OTHER)
        setSignals(OTHER.path, "blur", 1, listOf(BLUR))

        assertEquals(
            listOf(PHOTO to emptyList(), OTHER to listOf(BLUR)),
            indexedImages().map { it.ref to it.signals },
        )
    }

    @Test
    fun `images without metadata leave out unreadable ones`() = test {
        val broken = ImageRef("/photos/c.jpg", 5, 5)
        for (image in listOf(PHOTO, OTHER, broken)) record(image)
        setMetadata(PHOTO.path, META)
        setUnreadable(broken.path)

        assertEquals(listOf(OTHER), withoutMetadata())
        assertEquals(listOf(broken.path), unreadable())
    }

    @Test
    fun `forget removes an image and its data`() = test {
        record(PHOTO)
        setSignals(PHOTO.path, "blur", 1, listOf(BLUR))

        forget(listOf(PHOTO.path))

        assertEquals(emptyList<ImageRef>(), images())
        assertEquals(emptyList<Signal>(), signals(PHOTO.path))
    }

    @Test
    fun `forget takes more paths than one statement can bind`() = test {
        val many = (0 until 1200).map { ImageRef("/photos/$it.jpg", 1, 1) }
        many.forEach { record(it) }

        forget(many.map { it.path })

        assertEquals(emptyList<ImageRef>(), images())
    }

    @Test
    fun `signals of many images at once`() = test {
        record(PHOTO)
        record(OTHER)

        setSignalsOfMany("blur", 1, mapOf(PHOTO.path to listOf(BLUR), OTHER.path to emptyList()))

        assertEquals(listOf(BLUR), signals(PHOTO.path))
        assertTrue(isAnalyzed(OTHER.path, "blur", 1))
    }

    @Test
    fun `signals of many store nothing if one image is missing`() = test {
        record(PHOTO)

        assertThrows(NoSuchElementException::class.java) {
            runBlocking { setSignalsOfMany("blur", 1, mapOf(PHOTO.path to listOf(BLUR), "/nope.jpg" to emptyList())) }
        }

        assertEquals(emptyList<Signal>(), signals(PHOTO.path))
    }

    @Test
    fun `group fingerprint is per detector version`() = test {
        assertNull(groupFingerprint("exact_duplicate", 1))

        setGroupFingerprint("exact_duplicate", 1, "abc")

        assertEquals("abc", groupFingerprint("exact_duplicate", 1))
        assertNull(groupFingerprint("exact_duplicate", 2))
    }

    @Test
    fun `protection signals round trip`() = test {
        record(PHOTO)
        val faces = Signal("faces", 1, Protection.FACES, 0.8f, mapOf("faces" to "3"))

        setSignals(PHOTO.path, "faces", 1, listOf(faces))

        assertEquals(listOf(faces), signals(PHOTO.path))
        assertEquals(listOf(faces), indexedImages().single().signals)
    }

    @Test
    fun `excluded folders are listed, added once and removed`() = test {
        excludeFolder("/photos/b")
        excludeFolder("/photos/a")
        excludeFolder("/photos/a")

        assertEquals(listOf("/photos/a", "/photos/b"), excludedFolders())

        removeExcludedFolder("/photos/a")

        assertEquals(listOf("/photos/b"), excludedFolders())
    }

    @Test
    fun `excluding a folder leaves its images and their signals alone`() = test {
        record(PHOTO)
        setSignals(PHOTO.path, "blur", 1, listOf(BLUR))

        excludeFolder("/photos")
        removeExcludedFolder("/photos")

        assertEquals(listOf(BLUR), signals(PHOTO.path))
        assertEquals(ReviewState.UNREVIEWED, reviewState(PHOTO.path))
    }
}
