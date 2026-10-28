// The lab's tests/test_index.py, translated.
package io.github.bert0ns.cribrum.index

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.github.bert0ns.cribrum.metadata.ImageMetadata
import io.github.bert0ns.cribrum.model.Category
import io.github.bert0ns.cribrum.model.GROUP_KEY
import io.github.bert0ns.cribrum.model.ImageRef
import io.github.bert0ns.cribrum.model.Protection
import io.github.bert0ns.cribrum.model.ROLE_KEY
import io.github.bert0ns.cribrum.model.ReviewState
import io.github.bert0ns.cribrum.model.Role
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
import java.lang.reflect.Modifier
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
    fun `recordAll counts the new and changed images and leaves the rest as they were`() = test {
        record(PHOTO)
        record(OTHER)
        setMetadata(OTHER.path, META)
        val fresh = ImageRef("/photos/c.jpg", 7, 7)

        assertEquals(2, recordAll(listOf(PHOTO, ImageRef(OTHER.path, 6, 5), fresh)))

        assertEquals(listOf(PHOTO, ImageRef(OTHER.path, 6, 5), fresh), images())
        assertNull(metadata(OTHER.path)) // the changed one was reset, like record does
    }

    @Test
    fun `recordAll of images already recorded keeps the version`() = test {
        recordAll(listOf(PHOTO, OTHER))
        val before = version

        assertEquals(0, recordAll(listOf(PHOTO, OTHER)))

        assertEquals(before, version)
    }

    @Test
    fun `a batch that fails part way leaves the index as it was`() = test {
        record(PHOTO)
        val before = version
        val batch = object : AbstractList<ImageRef>() {
            override val size = 3
            override fun get(index: Int) = if (index == 2) error("disk full") else ImageRef("/photos/new-$index.jpg", 1, 1)
        }

        assertThrows(IllegalStateException::class.java) { runBlocking { recordAll(batch) } }

        assertEquals(listOf(PHOTO), images())
        assertEquals(before, version)
        assertEquals(2, recordAll(batch.take(2))) // the index still takes the same files
    }

    @Test
    fun `a new file at the path of a trashed image starts unreviewed`() = test {
        record(PHOTO)
        setReviewState(PHOTO.path, ReviewState.TRASHED)

        assertTrue(record(ImageRef(PHOTO.path, 101, 1)))

        assertEquals(ReviewState.UNREVIEWED, reviewState(PHOTO.path))
    }

    @Test
    fun `recording a trashed image again unchanged leaves it trashed`() = test {
        record(PHOTO)
        setReviewState(PHOTO.path, ReviewState.TRASHED)

        assertFalse(record(PHOTO))

        assertEquals(ReviewState.TRASHED, reviewState(PHOTO.path))
    }

    @Test
    fun `forgetTrashed forgets trashed rows and leaves the others`() = test {
        record(PHOTO)
        record(OTHER)
        setReviewState(PHOTO.path, ReviewState.TRASHED)
        setReviewState(OTHER.path, ReviewState.KEPT)

        forgetTrashed(listOf(PHOTO.path, OTHER.path))

        assertEquals(listOf(OTHER), images())
        assertEquals(ReviewState.KEPT, reviewState(OTHER.path))
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
    fun `deleting the embeddings leaves the rest of the index as it was`() = test {
        record(PHOTO)
        record(OTHER)
        setMetadata(PHOTO.path, META)
        setEmbeddings("clip", 1, mapOf(PHOTO.path to floatArrayOf(1f), OTHER.path to floatArrayOf(2f)))
        setSignals(PHOTO.path, "blur", 1, listOf(BLUR))
        setReviewState(PHOTO.path, ReviewState.KEPT)
        excludeFolder("/photos/memes")

        deleteEmbeddings()

        assertEquals(emptyMap<String, FloatArray>(), embeddings("clip", 1))
        assertEquals(listOf(PHOTO, OTHER), images())
        assertEquals(META, metadata(PHOTO.path))
        assertEquals(listOf(BLUR), signals(PHOTO.path))
        assertTrue(isAnalyzed(PHOTO.path, "blur", 1))
        assertEquals(ReviewState.KEPT, reviewState(PHOTO.path))
        assertEquals(listOf("/photos/memes"), excludedFolders())
    }

    @Test
    fun `the embeddings estimate is one page per embedding`() = test {
        record(PHOTO)
        record(OTHER)
        assertEquals(0L, embeddingBytes())

        setEmbeddings("clip", 1, mapOf(PHOTO.path to FloatArray(512), OTHER.path to FloatArray(512)))
        assertEquals(2 * 4096L, embeddingBytes())

        deleteEmbeddings()
        assertEquals(0L, embeddingBytes())
    }

    @Test
    fun `the file size grows with the data and is zero without a file`() = test {
        assertEquals(0L, fileBytes()) // in memory
        val onDisk = ImageIndex.open(ApplicationProvider.getApplicationContext(), "size-test.sqlite")
        try {
            val empty = onDisk.fileBytes()
            repeat(300) { onDisk.record(ImageRef("/photos/$it.jpg", 1, 1)) }
            assertTrue(onDisk.fileBytes() > empty)
        } finally {
            onDisk.close()
            ApplicationProvider.getApplicationContext<android.content.Context>().deleteDatabase("size-test.sqlite")
        }
    }

    /** The index line (file minus the estimate) is what the file is left with after the delete. */
    @Test
    fun `the embeddings estimate is close to what deleting them frees`() = test {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val vectors = (0 until 300).associate { "/photos/$it.jpg" to FloatArray(512) { i -> i.toFloat() } }

        // Closing checkpoints the write-ahead log, as the system does in time, so the files hold what the data takes.
        fun <T> onDisk(block: suspend ImageIndex.() -> T): T {
            val opened = ImageIndex.open(context, "estimate-test.sqlite")
            return try {
                runBlocking { opened.block() }
            } finally {
                opened.close()
            }
        }
        try {
            onDisk {
                vectors.keys.forEach { record(ImageRef(it, 1, 1)) }
                setEmbeddings("clip", 1, vectors)
            }
            val shown = onDisk { fileBytes() - embeddingBytes() }
            onDisk { deleteEmbeddings() }
            val left = onDisk { fileBytes() }

            assertTrue("shown $shown, left $left", kotlin.math.abs(shown - left) <= left / 5)
        } finally {
            context.deleteDatabase("estimate-test.sqlite")
        }
    }

    @Test
    fun `deleting the embeddings gives their pages back to the file`() = test {
        repeat(100) {
            record(ImageRef("/photos/$it.jpg", 1, 1))
            setEmbeddings("clip", 1, mapOf("/photos/$it.jpg" to FloatArray(512) { value -> value.toFloat() }))
        }
        assertEquals(100, embeddingCount())

        deleteEmbeddings()

        assertEquals(0, embeddingCount())
        val db = database.openHelper.readableDatabase
        db.query("PRAGMA freelist_count").use {
            it.moveToFirst()
            assertEquals(0, it.getInt(0)) // a plain DELETE leaves the pages in the file
        }
    }

    @Test
    fun `forgetting analyses drops the signals of the chosen detectors only`() = test {
        record(PHOTO)
        setSignals(PHOTO.path, "blur", 1, listOf(BLUR))
        setSignals(PHOTO.path, "document", 1, listOf(Signal("document", 1, Category.DOCUMENT, 0.9f)))

        forgetAnalyses(listOf(PHOTO.path), listOf("document"))

        assertFalse(isAnalyzed(PHOTO.path, "document", 1))
        assertEquals(listOf(BLUR), signals(PHOTO.path))
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
        assertEquals(setOf("blur"), keptDetectors(PHOTO.path))
    }

    private fun group(id: String, role: Role) = Signal(
        "near_duplicate",
        1,
        Category.NEAR_DUPLICATE,
        if (role == Role.BEST) 0f else 0.8f,
        mapOf(GROUP_KEY to id, ROLE_KEY to role.value),
    )

    @Test
    fun `a detector's first result after the keep counts as seen, a later group does not`() = test {
        record(PHOTO)
        setSignals(PHOTO.path, "blur", 1, listOf(BLUR))
        setReviewState(PHOTO.path, ReviewState.KEPT)

        setSignals(PHOTO.path, "near_duplicate", 1, listOf(group("g", Role.BEST)))
        assertEquals(setOf(BLUR.identity, group("g", Role.BEST).identity), indexedImages().single().seenWhenKept)
        assertEquals(setOf("blur", "near_duplicate"), keptDetectors(PHOTO.path))

        setSignals(PHOTO.path, "near_duplicate", 1, listOf(group("g", Role.INFERIOR)))
        assertEquals(setOf(BLUR.identity, group("g", Role.BEST).identity), indexedImages().single().seenWhenKept)
    }

    @Test
    fun `a first result for an image that is not kept is not recorded`() = test {
        record(PHOTO)

        setSignals(PHOTO.path, "blur", 1, listOf(BLUR))

        assertEquals(emptySet<Any>(), indexedImages().single().seenWhenKept)
        assertEquals(emptySet<Any>(), keptDetectors(PHOTO.path))
    }

    @Test
    fun `leaving kept forgets the recorded signals`() = test {
        record(PHOTO)
        setSignals(PHOTO.path, "blur", 1, listOf(BLUR))
        setReviewState(PHOTO.path, ReviewState.KEPT)

        setReviewState(PHOTO.path, ReviewState.UNREVIEWED)

        assertEquals(emptySet<Any>(), indexedImages().single().seenWhenKept)
        assertEquals(emptySet<Any>(), keptDetectors(PHOTO.path))
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
    fun `a group fingerprint keeps the paths whose content hash failed, until the next one replaces it`() = test {
        assertEquals(emptyList<String>(), groupHashFailed("exact_duplicate", 1))

        setGroupFingerprint("exact_duplicate", 1, "abc", listOf("/photos/a [1].jpg", "/photos/b\"\n.jpg"))

        assertEquals("abc", groupFingerprint("exact_duplicate", 1))
        assertEquals(listOf("/photos/a [1].jpg", "/photos/b\"\n.jpg"), groupHashFailed("exact_duplicate", 1))

        setGroupFingerprint("exact_duplicate", 1, "def")

        assertEquals("def", groupFingerprint("exact_duplicate", 1))
        assertEquals(emptyList<String>(), groupHashFailed("exact_duplicate", 1))
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

    @Test
    fun `a trailing separator does not change the excluded folder`() = test {
        excludeFolder("/photos/a/")
        excludeFolder("/photos/a")

        assertEquals(listOf("/photos/a"), excludedFolders())
    }

    /** Every public write of the index, each run once on an index holding [PHOTO] with [BLUR]. */
    private val writes: Map<String, suspend ImageIndex.() -> Unit> = mapOf(
        "record" to { record(OTHER) },
        "recordAll" to { recordAll(listOf(OTHER)) },
        "forget" to { forget(listOf(PHOTO.path)) },
        "forgetTrashed" to { forgetTrashed(listOf(PHOTO.path)) },
        "setUnreadable" to { setUnreadable(PHOTO.path) },
        "setMetadata" to { setMetadata(PHOTO.path, META) },
        "setContentHash" to { setContentHash(PHOTO.path, "abc") },
        "setPerceptualHash" to { setPerceptualHash(PHOTO.path, 1uL) },
        "deleteEmbeddings" to { deleteEmbeddings() },
        "forgetAnalyses" to { forgetAnalyses(listOf(PHOTO.path), listOf("blur")) },
        "setEmbeddings" to { setEmbeddings("clip", 1, mapOf(PHOTO.path to floatArrayOf(1f))) },
        "setSignals" to { setSignals(PHOTO.path, "blur", 1, emptyList()) },
        "setSignalsOfMany" to { setSignalsOfMany("blur", 1, mapOf(PHOTO.path to emptyList())) },
        "setGroupFingerprint" to { setGroupFingerprint("exact_duplicate", 1, "abc") },
        "setReviewState" to { setReviewState(PHOTO.path, ReviewState.KEPT) },
        "restoreReviewState" to { restoreReviewState(PHOTO.path, ReviewState.KEPT, emptySet(), emptySet()) },
        "excludeFolder" to { excludeFolder("/photos") },
        "removeExcludedFolder" to { removeExcludedFolder("/photos") },
    )

    /**
     * Every public read, each run once on the same index. A method that writes anything must never go here: the review
     * would keep showing what it ranked before the write. It goes in [writes].
     */
    private val reads: Map<String, suspend ImageIndex.() -> Unit> = mapOf(
        "images" to { images() },
        "withoutMetadata" to { withoutMetadata() },
        "unreadable" to { unreadable() },
        "metadata" to { metadata(PHOTO.path) },
        "contentHash" to { contentHash(PHOTO.path) },
        "perceptualHash" to { perceptualHash(PHOTO.path) },
        "hashedPaths" to { hashedPaths() },
        "reviewStates" to { reviewStates() },
        "embeddingCount" to { embeddingCount() },
        "imageCount" to { imageCount() },
        "embeddingBytes" to { embeddingBytes() },
        "fileBytes" to { fileBytes() },
        "embeddings" to { embeddings("clip", 1) },
        "analyzedPaths" to { analyzedPaths("blur", 1) },
        "embeddedPaths" to { embeddedPaths("clip", 1) },
        "readableNotTrashed" to { readableNotTrashed() },
        "groupFingerprint" to { groupFingerprint("exact_duplicate", 1) },
        "groupHashFailed" to { groupHashFailed("exact_duplicate", 1) },
        "isAnalyzed" to { isAnalyzed(PHOTO.path, "blur", 1) },
        "signals" to { signals(PHOTO.path) },
        "keptSignals" to { keptSignals(PHOTO.path) },
        "keptDetectors" to { keptDetectors(PHOTO.path) },
        "reviewState" to { reviewState(PHOTO.path) },
        "excludedFolders" to { excludedFolders() },
        "notTrashed" to { notTrashed() },
        "capturedAt" to { capturedAt() },
        "indexedImages" to { indexedImages() },
        "getVersion" to { version },
    )

    @Test
    fun `every public method is known as a read or a write`() {
        val methods = ImageIndex::class.java.declaredMethods
            .filter { Modifier.isPublic(it.modifiers) && !it.isSynthetic && '$' !in it.name }
            .map { it.name.substringBefore('-') } // Kotlin mangles names that take inline classes like ULong
            .toSet()
        // A new write must bump the version, or the review shows a stale ranking: add it to [writes].
        assertEquals(writes.keys + reads.keys + "close", methods)
    }

    @Test
    fun `every write bumps the version after its last statement`() {
        for ((name, write) in writes) {
            lateinit var index: ImageIndex
            val seen = mutableListOf<Pair<String, Long>>()
            index = ImageIndex(
                Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), IndexDatabase::class.java)
                    // Room reports its transaction markers late and checks its own tables after the commit: only the index's statements count.
                    .setQueryCallback({ sql, _ -> if ("TRANSACTION" !in sql && "room_" !in sql) seen += sql to index.version }, Runnable::run)
                    .build(),
            )
            index.use {
                runBlocking {
                    index.record(PHOTO)
                    index.setSignals(PHOTO.path, "blur", 1, listOf(BLUR))
                    val before = index.version
                    seen.clear()
                    index.write()
                    assertTrue(name, seen.isNotEmpty())
                    // The statements run at the old version: a reader that sees the new one finds the write.
                    assertEquals(name, emptyList<Pair<String, Long>>(), seen.filter { it.second != before })
                    assertTrue(name, index.version > before)
                }
            }
        }
    }

    @Test
    fun `an unchanged record keeps the version`() = test {
        record(PHOTO)
        val before = version

        assertFalse(record(PHOTO))

        assertEquals(before, version)
    }

    @Test
    fun `reads leave the version alone`() = test {
        record(PHOTO)
        setSignals(PHOTO.path, "blur", 1, listOf(BLUR))
        val before = version
        for ((name, read) in reads) {
            read()
            assertEquals(name, before, version)
        }
    }
}
