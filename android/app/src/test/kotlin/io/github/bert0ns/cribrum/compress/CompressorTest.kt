package io.github.bert0ns.cribrum.compress

import android.graphics.BitmapFactory
import androidx.exifinterface.media.ExifInterface
import io.github.bert0ns.cribrum.index.memoryIndex
import io.github.bert0ns.cribrum.model.Category
import io.github.bert0ns.cribrum.model.ImageRef
import io.github.bert0ns.cribrum.model.ReviewState
import io.github.bert0ns.cribrum.model.Signal
import io.github.bert0ns.cribrum.model.toImageRef
import io.github.bert0ns.cribrum.trash.AppTrash
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.FileTime
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.TimeUnit

private const val TAKEN = "2024:05:01 10:20:30"
private const val MTIME_NS = 1_714_558_830_123_456_789L

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36])
class CompressorTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val volume by lazy { folder.newFolder("storage") }
    private val camera by lazy { File(volume, "DCIM/Camera").apply { mkdirs() } }
    private val now = Instant.parse("2026-11-03T12:00:00.250Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)

    /** What MediaStore was told, with the date taken and the mtime of the file at that path when it was told. */
    private val told = mutableListOf<Triple<String, String?, Long>>()
    private val trash by lazy {
        AppTrash({ listOf(volume) }, clock) { paths ->
            paths.forEach { told += Triple(it, ExifInterface(it).getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL), File(it).toImageRef().mtimeNs) }
        }
    }
    private val index = memoryIndex()
    private var free: () -> Long = { Long.MAX_VALUE }
    private val compressor by lazy { Compressor(trash, index, { free() }, clock) }

    private fun photo(name: String, bytes: ByteArray, tags: Map<String, String> = mapOf(ExifInterface.TAG_DATETIME_ORIGINAL to TAKEN)): ImageRef {
        val file = File(camera, name)
        file.writeBytes(bytes)
        if (tags.isNotEmpty()) {
            ExifInterface(file).apply { tags.forEach(::setAttribute) }.saveAttributes()
        }
        Files.setLastModifiedTime(file.toPath(), FileTime.from(MTIME_NS, TimeUnit.NANOSECONDS))
        return file.toImageRef()
    }

    private fun compress(image: ImageRef, level: CompressionLevel) = runBlocking { compressor.compress(image, level) }

    private fun assertUntouched(image: ImageRef, bytes: ByteArray) {
        assertEquals(image, File(image.path).toImageRef())
        assertArrayEquals(bytes, File(image.path).readBytes())
        assertEquals(listOf(File(image.path).name), camera.list()!!.toList())
        assertEquals(0, runBlocking { trash.trashed() }.size)
    }

    @Test
    fun `a compressed photo takes the original's path and the original goes to the trash`() {
        val image = photo("a.jpg", encodedJpeg(800, 600, 100))
        val originalBytes = File(image.path).readBytes()

        val copy = (compress(image, CompressionLevel.HIGH) as Compression.Done).copy

        assertEquals(image.path, copy.path)
        assertTrue(copy.size < image.size)
        assertEquals(copy, File(image.path).toImageRef())
        assertEquals(listOf("a.jpg"), camera.list()!!.toList())
        val item = runBlocking { trash.trashed() }.single()
        assertEquals(image, item.original)
        assertEquals(copy, item.compressedCopy)
        assertArrayEquals(originalBytes, item.file.readBytes())
        // Robolectric has no MediaProvider: DATE_TAKEN comes from these two, and the phone has to check it.
        assertEquals(listOf(Triple(image.path, TAKEN, MTIME_NS)), told)
    }

    @Test
    fun `the index keeps the review state and analyzes the copy again`() = runBlocking {
        val image = photo("a.jpg", encodedJpeg(800, 600, 100))
        index.record(image)
        index.setSignals(image.path, "blur", 1, listOf(Signal("blur", 1, Category.BLURRY, 0.2f)))
        index.setReviewState(image.path, ReviewState.KEPT)

        val copy = (compressor.compress(image, CompressionLevel.HIGH) as Compression.Done).copy

        assertEquals(listOf(copy), index.images())
        assertEquals(ReviewState.KEPT, index.reviewState(image.path))
        assertEquals(emptyList<Signal>(), index.signals(image.path))
        assertFalse(index.isAnalyzed(image.path, "blur", 1))
    }

    @Test
    fun `the EXIF and the mtime are kept, the pixel size is the new one and the old XMP is gone`() {
        val tags = mapOf(
            ExifInterface.TAG_DATETIME_ORIGINAL to TAKEN,
            ExifInterface.TAG_OFFSET_TIME_ORIGINAL to "+02:00",
            ExifInterface.TAG_SUBSEC_TIME_ORIGINAL to "123",
            ExifInterface.TAG_MAKE to "samsung",
            ExifInterface.TAG_MODEL to "SM-F966B",
            ExifInterface.TAG_ORIENTATION to ExifInterface.ORIENTATION_ROTATE_90.toString(),
            ExifInterface.TAG_GPS_LATITUDE to "45/1,28/1,0/1",
            ExifInterface.TAG_GPS_LATITUDE_REF to "N",
            ExifInterface.TAG_GPS_LONGITUDE to "9/1,11/1,0/1",
            ExifInterface.TAG_GPS_LONGITUDE_REF to "E",
            ExifInterface.TAG_IMAGE_WIDTH to "4096",
            ExifInterface.TAG_IMAGE_LENGTH to "3072",
            ExifInterface.TAG_PIXEL_X_DIMENSION to "4096",
            ExifInterface.TAG_PIXEL_Y_DIMENSION to "3072",
            ExifInterface.TAG_XMP to "<x:xmpmeta xmlns:x=\"adobe:ns:meta/\">original edits</x:xmpmeta>",
        )
        val image = photo("big.jpg", encodedJpeg(4096, 3072, 100), tags)

        val copy = (compress(image, CompressionLevel.SMALLEST) as Compression.Done).copy

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(copy.path, bounds)
        assertEquals(4000 to 3000, bounds.outWidth to bounds.outHeight)
        val exif = ExifInterface(copy.path)
        for ((tag, value) in tags - ExifInterface.TAG_XMP - ExifInterface.TAG_IMAGE_WIDTH - ExifInterface.TAG_IMAGE_LENGTH - ExifInterface.TAG_PIXEL_X_DIMENSION - ExifInterface.TAG_PIXEL_Y_DIMENSION) {
            assertEquals(tag, value, exif.getAttribute(tag))
        }
        assertEquals("4000", exif.getAttribute(ExifInterface.TAG_IMAGE_WIDTH))
        assertEquals("3000", exif.getAttribute(ExifInterface.TAG_IMAGE_LENGTH))
        assertEquals("4000", exif.getAttribute(ExifInterface.TAG_PIXEL_X_DIMENSION))
        assertEquals("3000", exif.getAttribute(ExifInterface.TAG_PIXEL_Y_DIMENSION))
        assertFalse(String(File(copy.path).readBytes(), Charsets.ISO_8859_1).contains("original edits"))
        assertEquals(MTIME_NS, copy.mtimeNs)
    }

    @Test
    fun `the marker follows the Exif`() {
        val image = photo("a.jpg", encodedJpeg(800, 600, 100))

        val copy = (compress(image, CompressionLevel.BALANCED) as Compression.Done).copy

        val found = headerSegments(File(copy.path).readBytes())
        val exif = found.indexOfFirst { it.code == 0xE1 && it.startsWith("Exif\u0000\u0000") }
        val marker = found[exif + 1]
        assertEquals(0xEF, marker.code)
        assertEquals("cribrum\u0000version=1\nlevel=balanced\ncompressed_at=2026-11-03T12:00:00Z\n", String(marker.payload, Charsets.US_ASCII))
    }

    @Test
    fun `a result that is not smaller is refused and the original stays`() {
        val image = photo("a.jpg", encodedJpeg(800, 600, 40))
        val bytes = File(image.path).readBytes()

        assertEquals(Compression.Refused(Refusal.NOT_SMALLER), compress(image, CompressionLevel.HIGH))

        assertUntouched(image, bytes)
        assertEquals(emptyList<Triple<String, String?, Long>>(), told)
    }

    @Test
    fun `too little free memory refuses High quality on a 200 MP photo, read for each photo`() {
        val large = photo("large.jpg", withSize(encodedJpeg(64, 48, 90), 16320, 12240), emptyMap())
        val small = photo("small.jpg", encodedJpeg(800, 600, 100))
        val readings = ArrayDeque(listOf(1_500_000_000L, 1_500_000_000L))
        free = { readings.removeFirst() }
        val bytes = File(large.path).readBytes()

        assertEquals(Compression.Refused(Refusal.LOW_MEMORY), compress(large, CompressionLevel.HIGH))
        assertTrue(compress(small, CompressionLevel.HIGH) is Compression.Done)

        assertTrue(readings.isEmpty())
        assertArrayEquals(bytes, File(large.path).readBytes())
        assertEquals(setOf("large.jpg", "small.jpg"), camera.list()!!.toSet())
    }

    @Test
    fun `an out of memory error is a refusal`() {
        val image = photo("a.jpg", encodedJpeg(800, 600, 100))
        val bytes = File(image.path).readBytes()
        free = { throw OutOfMemoryError("simulated") }

        assertEquals(Compression.Refused(Refusal.LOW_MEMORY), compress(image, CompressionLevel.HIGH))

        assertUntouched(image, bytes)
    }

    @Test
    fun `a photo whose bitmap lost its gain map is refused`() {
        // Robolectric's decoder reads no gain map at all, so every Ultra HDR photo loses it here.
        val image = photo("hdr.jpg", decodableUltraHdr(800, 600), emptyMap())
        val bytes = File(image.path).readBytes()

        assertEquals(Compression.Refused(Refusal.GAIN_MAP_LOST), compress(image, CompressionLevel.HIGH))

        assertUntouched(image, bytes)
    }

    @Test
    fun `the Ultra HDR markers survive the EXIF save and the marker`() {
        // Robolectric neither decodes nor writes a gain map, so this starts from what Bitmap.compress writes on
        // the phone and checks the bytes. That the gain map still decodes is left to the phone.
        val original = File(photo("a.jpg", encodedJpeg(64, 48, 100)).path)
        val gainMapImage = jpeg(xmp(rdf("""<rdf:Description xmlns:hdrgm="http://ns.adobe.com/hdr-gain-map/1.0/" hdrgm:Version="1.0" hdrgm:GainMapMax="2"/>""")))
        val withAllThree = ultraHdr(gainMapBytes = gainMapImage) { headers -> jpeg(*(headers + ISO_21496).toTypedArray()) }
        val copy = File(camera, "copy.jpg").apply { writeBytes(withAllThree) }

        copyExif(original, copy, 800, 600)
        val bytes = withMarker(copy.readBytes(), markerSegment(CompressionLevel.HIGH, now))

        val found = headerSegments(bytes)
        val marker = found.indexOfFirst { it.code == 0xEF }
        val mpf = found.indexOfFirst { it.code == 0xE2 && it.startsWith("MPF\u0000") }
        assertTrue(found.any { it.code == 0xE1 && it.startsWith("http://ns.adobe.com/xap/1.0/") && String(it.payload).contains("hdrgm:Version") })
        assertTrue(found.any { it.code == 0xE2 && it.startsWith("urn:iso:std:iso:ts:21496:-1\u0000") })
        assertTrue(marker in 0 until mpf)
        assertEquals(TAKEN, ExifInterface(bytes.inputStream()).getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL))
        assertArrayEquals(gainMapImage, bytes.copyOfRange(gainMapOffset(bytes), bytes.size))
        val read = readJpeg(bytes)
        assertEquals(JpegStatus.ELIGIBLE, read.status)
        assertTrue(read.gainMap)
        assertEquals("high", read.marker?.level)
    }
}
