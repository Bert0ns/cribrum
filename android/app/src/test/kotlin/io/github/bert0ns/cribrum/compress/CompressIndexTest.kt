package io.github.bert0ns.cribrum.compress

import io.github.bert0ns.cribrum.index.memoryIndex
import io.github.bert0ns.cribrum.metadata.ImageMetadata
import io.github.bert0ns.cribrum.model.ImageRef
import io.github.bert0ns.cribrum.model.ReviewState
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

private val ELIGIBLE = JpegInfo(JpegStatus.ELIGIBLE, quality = 95)

@RunWith(RobolectricTestRunner::class)
class CompressIndexTest {
    private val index = memoryIndex()

    @After
    fun close() = index.close()

    private fun test(block: suspend () -> Unit) = runBlocking { block() }

    private suspend fun photo(path: String, size: Long, info: JpegInfo? = ELIGIBLE, mime: String = "image/jpeg"): ImageRef {
        val image = ImageRef(path, size, 1)
        index.record(image)
        index.setMetadata(path, ImageMetadata(4000, 3000, mime))
        if (info != null) index.setJpeg(path, info)
        return image
    }

    private suspend fun listed(sdk: Int = 34) = index.compressible(sdk).map { it.path }

    @Test
    fun `the compress list is largest first and keeps what the scan read`() = test {
        photo("/p/small.jpg", 1_000)
        val marker = Marker(1, "balanced", "2026-11-03T10:15:30Z")
        val losses = Losses(reEdit = true, depthMap = false, xmp = true, c2pa = true)
        photo("/p/large.jpg", 9_000, JpegInfo(JpegStatus.ELIGIBLE, gainMap = true, quality = 92, marker = marker, losses = losses))
        photo("/p/middle.jpg", 5_000)

        assertEquals(listOf("/p/large.jpg", "/p/middle.jpg", "/p/small.jpg"), listed())
        val large = index.compressible(34).first()
        assertEquals(CompressibleImage("/p/large.jpg", 9_000, 4000, 3000, JpegInfo(JpegStatus.ELIGIBLE, true, 92, marker, losses)), large)
    }

    @Test
    fun `the list leaves out ineligible, trashed and excluded photos, and Ultra HDR below API 34`() = test {
        photo("/p/a.jpg", 10)
        photo("/p/motion.jpg", 20, JpegInfo(JpegStatus.MOTION_PHOTO))
        photo("/p/trashed.jpg", 30)
        index.setReviewState("/p/trashed.jpg", ReviewState.TRASHED)
        photo("/p/hidden/b.jpg", 40)
        index.excludeFolder("/p/hidden")
        photo("/p/hdr.jpg", 50, JpegInfo(JpegStatus.ELIGIBLE, gainMap = true, quality = 95))

        assertEquals(listOf("/p/a.jpg"), listed(sdk = 33))
        assertEquals(listOf("/p/hdr.jpg", "/p/a.jpg"), listed(sdk = 34))
    }

    @Test
    fun `only JPEGs not read yet are to read, and a changed file is read again`() = test {
        photo("/p/read.jpg", 10)
        photo("/p/new.jpg", 10, info = null)
        photo("/p/a.png", 10, info = null, mime = "image/png")

        assertEquals(listOf("/p/new.jpg"), index.jpegsToRead().map { it.path })
        index.record(ImageRef("/p/read.jpg", 11, 2))
        index.setMetadata("/p/read.jpg", ImageMetadata(4000, 3000, "image/jpeg"))
        assertEquals(listOf("/p/new.jpg", "/p/read.jpg"), index.jpegsToRead().map { it.path })
        assertEquals(emptyList<String>(), listed())
    }
}
