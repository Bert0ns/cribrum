package io.github.bert0ns.cribrum.compress

import android.graphics.BitmapFactory
import androidx.exifinterface.media.ExifInterface
import io.github.bert0ns.cribrum.detectors.AnalysisContext
import io.github.bert0ns.cribrum.detectors.Detector
import io.github.bert0ns.cribrum.detectors.GroupDetector
import io.github.bert0ns.cribrum.hashing.contentHash
import io.github.bert0ns.cribrum.hashing.perceptualHash
import io.github.bert0ns.cribrum.index.memoryIndex
import io.github.bert0ns.cribrum.metadata.readMetadata
import io.github.bert0ns.cribrum.model.Category
import io.github.bert0ns.cribrum.model.ImageRef
import io.github.bert0ns.cribrum.model.ReviewState
import io.github.bert0ns.cribrum.model.Signal
import io.github.bert0ns.cribrum.model.toImageRef
import io.github.bert0ns.cribrum.pipeline.Embedder
import io.github.bert0ns.cribrum.pipeline.Pipeline
import io.github.bert0ns.cribrum.review.Review
import io.github.bert0ns.cribrum.sources.FilesystemImageSource
import io.github.bert0ns.cribrum.trash.AppTrash
import io.github.bert0ns.cribrum.trash.copyFileFor
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.attribute.FileTime
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.Collections
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
    private var flagged = emptySet<String>()
    private val trash by lazy {
        AppTrash({ listOf(volume) }, clock, favorites = { it intersect flagged }) { paths ->
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
    fun `a swap that leaves the original in the trash says so and marks its row trashed`() = runBlocking {
        val image = photo("a.jpg", encodedJpeg(800, 600, 100))
        index.record(image)
        trash.mover = { _, _ -> throw IOException("simulated") }

        assertEquals(Compression.Refused(Refusal.LEFT_IN_TRASH), compressor.compress(image, CompressionLevel.HIGH))

        assertEquals(listOf(image), trash.trashed().map { it.original })
        assertEquals(ReviewState.TRASHED, index.reviewState(image.path))
        assertEquals(emptyList<String>(), camera.list()!!.toList())
    }

    @Test
    fun `a swap is done even when the index cannot follow it`() {
        val image = photo("a.jpg", encodedJpeg(800, 600, 100))
        index.close()

        val copy = (compress(image, CompressionLevel.HIGH) as Compression.Done).copy

        assertEquals(copy, File(image.path).toImageRef())
        assertEquals(listOf(image), runBlocking { trash.trashed() }.map { it.original })
    }

    /** A scan of the volume with no detectors: the walk, the metadata and the JPEG headers. */
    private fun scan() = runBlocking { Pipeline(index, detectors = emptyList(), groupDetectors = emptyList(), trash = trash).run(FilesystemImageSource(volume)) }

    private fun listed() = runBlocking { index.compressible(36) }

    @Test
    fun `a copy shows as compressed before any scan, and a restore shows the original as compressible again`() {
        val image = photo("a.jpg", encodedJpeg(800, 600, 100))
        scan()
        val original = listed()
        assertEquals(listOf(image.size to null), original.map { it.size to it.info.marker?.level })

        val copy = (compress(image, CompressionLevel.HIGH) as Compression.Done).copy
        val compressed = listed()
        assertEquals(listOf(copy.size to "high"), compressed.map { it.size to it.info.marker?.level })
        // What the page showed before the scan is what the scan reads from the copy.
        scan()
        assertEquals(compressed, listed())

        assertEquals(listOf(image), runBlocking { Review(index, trash).restore(listOf(image)) })
        assertEquals(original, listed())
        scan()
        assertEquals(original, listed())
    }

    @Test
    fun `the scan after a compression reads, hashes, embeds and analyzes the copy again, as any changed file`() = runBlocking {
        val image = photo("a.jpg", encodedJpeg(800, 600, 100))
        val read: MutableList<String> = Collections.synchronizedList(mutableListOf())
        val detector = object : Detector {
            override val id = "seen"
            override val version = 1

            override suspend fun analyze(image: ImageRef, context: AnalysisContext): List<Signal> = emptyList<Signal>().also { read += "detector ${image.size}" }
        }
        val group = object : GroupDetector {
            override val id = "group"
            override val version = 1

            override suspend fun analyze(images: List<ImageRef>, context: AnalysisContext): Map<ImageRef, List<Signal>> = images.associateWith {
                read += "group ${it.size}"
                context.contentHash(it)
                emptyList()
            }
        }
        val embedder = object : Embedder {
            override val id = "embedder"
            override val version = 1

            override fun embedPixels(pixels: List<FloatArray>) = pixels.map { FloatArray(512) { 1f } }
        }
        val pipeline = Pipeline(
            index, listOf(detector), listOf(group), embedder,
            preprocessing = {
                read += "embedding ${it.length()}"
                FloatArray(3)
            },
            trash = trash,
            metadataReader = {
                read += "metadata ${it.length()}"
                readMetadata(it)
            },
            jpegReader = {
                read += "jpeg ${it.length()}"
                readJpeg(it)
            },
            perceptualHasher = {
                read += "perceptual hash ${it.size}"
                perceptualHash(it)
            },
            contentHasher = {
                read += "content hash ${it.size}"
                contentHash(it)
            },
        )
        pipeline.run(FilesystemImageSource(volume))
        val originalHash = index.contentHash(image.path)

        val copy = (compressor.compress(image, CompressionLevel.HIGH) as Compression.Done).copy
        read.clear()
        pipeline.run(FilesystemImageSource(volume))

        val steps = listOf("metadata", "jpeg", "perceptual hash", "detector", "embedding", "group", "content hash")
        assertEquals(steps.map { "$it ${copy.size}" }.toSet(), read.toSet())
        assertNotEquals(originalHash, index.contentHash(image.path))
        assertEquals(contentHash(copy), index.contentHash(image.path))
        assertEquals(emptyList<ImageRef>(), index.jpegsToRead())
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
        val encoding = mapOf(
            ExifInterface.TAG_Y_CB_CR_SUB_SAMPLING to "2,1",
            ExifInterface.TAG_BITS_PER_SAMPLE to "8,8,8",
            ExifInterface.TAG_PHOTOMETRIC_INTERPRETATION to "2",
            ExifInterface.TAG_SAMPLES_PER_PIXEL to "3",
            ExifInterface.TAG_PLANAR_CONFIGURATION to "1",
        )
        val image = photo("big.jpg", encodedJpeg(4096, 3072, 100), tags + encoding)
        assertEquals(encoding, encoding.mapValues { ExifInterface(image.path).getAttribute(it.key) })

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
        assertEquals(encoding.mapValues { null }, encoding.mapValues { exif.getAttribute(it.key) })
        assertEquals(MTIME_NS, copy.mtimeNs)
    }

    @Test
    fun `a photo the gallery marked as a favourite is reported as one, since its copy is a new MediaStore row`() {
        val heart = photo("heart.jpg", encodedJpeg(800, 600, 100))
        val plain = photo("plain.jpg", encodedJpeg(800, 600, 100))
        flagged = setOf(heart.path)

        assertTrue((compress(heart, CompressionLevel.HIGH) as Compression.Done).favorite)
        assertFalse((compress(plain, CompressionLevel.HIGH) as Compression.Done).favorite)
    }

    @Test
    fun `the GPS reference tags are kept, the altitude one included, since without it a depth reads as a height`() {
        val tags = mapOf(
            ExifInterface.TAG_GPS_ALTITUDE to "35/1",
            ExifInterface.TAG_GPS_ALTITUDE_REF to "1",
            ExifInterface.TAG_GPS_LATITUDE to "45/1,28/1,0/1",
            ExifInterface.TAG_GPS_LATITUDE_REF to "S",
            ExifInterface.TAG_GPS_LONGITUDE to "9/1,11/1,0/1",
            ExifInterface.TAG_GPS_LONGITUDE_REF to "W",
            ExifInterface.TAG_GPS_SPEED to "5/1",
            ExifInterface.TAG_GPS_SPEED_REF to "N",
            ExifInterface.TAG_GPS_IMG_DIRECTION to "90/1",
            ExifInterface.TAG_GPS_IMG_DIRECTION_REF to "M",
            ExifInterface.TAG_GPS_DEST_BEARING to "180/1",
            ExifInterface.TAG_GPS_DEST_BEARING_REF to "M",
        )
        val image = photo("gps.jpg", encodedJpeg(800, 600, 100), tags)
        assertEquals(tags, tags.mapValues { ExifInterface(image.path).getAttribute(it.key) })

        val copy = (compress(image, CompressionLevel.HIGH) as Compression.Done).copy

        assertEquals(tags, tags.mapValues { ExifInterface(copy.path).getAttribute(it.key) })
    }

    @Test
    fun `an altitude above sea level keeps its reference too`() {
        val image = photo("high.jpg", encodedJpeg(800, 600, 100), mapOf(ExifInterface.TAG_GPS_ALTITUDE to "812/1", ExifInterface.TAG_GPS_ALTITUDE_REF to "0"))

        val copy = (compress(image, CompressionLevel.HIGH) as Compression.Done).copy

        assertEquals("0", ExifInterface(copy.path).getAttribute(ExifInterface.TAG_GPS_ALTITUDE_REF))
    }

    /** [jpeg] with an Exif whose GPS IFD holds only the altitude reference, stored in the TIFF [format] the camera chose. */
    private fun withAltitudeRef(jpeg: ByteArray, format: Int, value: Int, bigEndian: Boolean = false): ByteArray {
        val tiff = ByteBuffer.allocate(8 + 2 + 12 + 4 + 2 + 12 + 4).order(if (bigEndian) ByteOrder.BIG_ENDIAN else ByteOrder.LITTLE_ENDIAN)
        tiff.put((if (bigEndian) "MM" else "II").toByteArray()).putShort(42).putInt(8)
        tiff.putShort(1).putShort(0x8825.toShort()).putShort(4).putInt(1).putInt(26).putInt(0) // IFD0: the GPS IFD is at 26
        tiff.putShort(1).putShort(5).putShort(format.toShort()).putInt(if (format == 2) 2 else 1)
        when (format) {
            3 -> tiff.putShort(value.toShort()).putShort(0)
            4 -> tiff.putInt(value)
            2 -> tiff.put(('0' + value).code.toByte()).put(0).putShort(0)
            else -> tiff.put(value.toByte()).put(0).putShort(0)
        }
        tiff.putInt(0)
        val app1 = "Exif\u0000\u0000".toByteArray(Charsets.ISO_8859_1) + tiff.array()
        return jpeg.copyOfRange(0, 2) + byteArrayOf(0xFF.toByte(), 0xE1.toByte(), ((app1.size + 2) shr 8).toByte(), (app1.size + 2).toByte()) + app1 + jpeg.copyOfRange(2, jpeg.size)
    }

    @Test
    fun `an altitude reference that ExifInterface cannot read is read from the Exif and kept, so a depth does not become a height`() {
        // ExifInterface drops a tag stored in a format other than the one it expects, and the phone stored this one as one of them.
        for ((format, bigEndian) in listOf(3 to false, 4 to false, 2 to false, 3 to true)) {
            val name = "alt-$format-$bigEndian.jpg"
            val image = photo(name, withAltitudeRef(encodedJpeg(800, 600, 100), format, 1, bigEndian), emptyMap())
            assertEquals(name, null, ExifInterface(image.path).getAttribute(ExifInterface.TAG_GPS_ALTITUDE_REF))

            val copy = (compress(image, CompressionLevel.HIGH) as Compression.Done).copy

            assertEquals(name, "1", ExifInterface(copy.path).getAttribute(ExifInterface.TAG_GPS_ALTITUDE_REF))
        }
    }

    @Test
    fun `an unreadable altitude reference of 0 is kept as 0, and a file without one gets none`() {
        val above = photo("above.jpg", withAltitudeRef(encodedJpeg(800, 600, 100), 3, 0), emptyMap())
        val none = photo("none.jpg", encodedJpeg(800, 600, 100), emptyMap())

        val copy = (compress(above, CompressionLevel.HIGH) as Compression.Done).copy
        val bare = (compress(none, CompressionLevel.HIGH) as Compression.Done).copy

        assertEquals("0", ExifInterface(copy.path).getAttribute(ExifInterface.TAG_GPS_ALTITUDE_REF))
        assertEquals(null, ExifInterface(bare.path).getAttribute(ExifInterface.TAG_GPS_ALTITUDE_REF))
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
    fun `a copy written without the gain map is refused`() {
        // As if the decoder kept the gain map: Robolectric's encoder then writes none, as an encoder that drops it would.
        val image = photo("hdr.jpg", decodableUltraHdr(800, 600), emptyMap())
        val bytes = File(image.path).readBytes()
        compressor.bitmapKeepsGainMap = { true }

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

    @Test
    fun `a preview is a compressed copy of the photo and touches nothing else`() {
        val image = photo("a.jpg", encodedJpeg(800, 600, 100))
        val bytes = File(image.path).readBytes()
        val out = copyFileFor(File(volume, "cache/x")).apply { parentFile.mkdirs() }

        val refusal = runBlocking { compressor.preview(File(image.path), CompressionLevel.SMALLEST, out) }

        assertEquals(null, refusal)
        assertTrue(out.length() in 1 until image.size)
        assertEquals(TAKEN, ExifInterface(out).getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL))
        assertEquals(null, readJpeg(out).marker)
        assertUntouched(image, bytes)
        assertEquals(emptyList<ImageRef>(), runBlocking { index.images() })
        assertEquals(emptyList<Triple<String, String?, Long>>(), told)
    }

    @Test
    fun `a preview says why a photo cannot be compressed`() {
        val image = photo("a.jpg", encodedJpeg(800, 600, 30))
        val out = copyFileFor(File(volume, "x"))

        assertEquals(Refusal.NOT_SMALLER, runBlocking { compressor.preview(File(image.path), CompressionLevel.HIGH, out) })
        assertFalse("a refused preview leaves no file", out.exists())
        // The same memory check as a compression: 2.5 times the decoded bitmap against the free memory.
        free = { 1 }
        assertEquals(Refusal.LOW_MEMORY, runBlocking { compressor.preview(File(image.path), CompressionLevel.HIGH, out) })
        assertFalse(out.exists())
    }

    @Test
    fun `a cancelled preview stops between steps, deletes its file and lets the next one run`() {
        val image = photo("a.jpg", encodedJpeg(800, 600, 100))
        val out = copyFileFor(File(volume, "x"))
        runBlocking {
            val job = launch(Dispatchers.IO, start = CoroutineStart.LAZY) { compressor.preview(File(image.path), CompressionLevel.HIGH, out) }
            // The memory check runs first, so the cancel lands before the decode, and the next check sees it.
            free = {
                job.cancel()
                Long.MAX_VALUE
            }
            job.start()
            job.join()
            assertTrue(job.isCancelled)
            assertFalse("a cancelled preview leaves no file", out.exists())
            free = { Long.MAX_VALUE }
            assertEquals(null, compressor.preview(File(image.path), CompressionLevel.SMALLEST, out))
            assertTrue(out.exists())
        }
    }
}
