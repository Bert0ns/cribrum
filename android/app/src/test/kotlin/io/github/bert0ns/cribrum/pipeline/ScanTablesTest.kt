package io.github.bert0ns.cribrum.pipeline

import android.content.Context
import android.provider.MediaStore
import androidx.test.core.app.ApplicationProvider
import com.appmattus.crypto.Algorithm
import io.github.bert0ns.cribrum.detectors.AnalysisContext
import io.github.bert0ns.cribrum.detectors.Detector
import io.github.bert0ns.cribrum.detectors.ForwardedDetector
import io.github.bert0ns.cribrum.hashing.toHex
import io.github.bert0ns.cribrum.index.memoryIndex
import io.github.bert0ns.cribrum.model.Category
import io.github.bert0ns.cribrum.model.ImageRef
import io.github.bert0ns.cribrum.model.ReviewState
import io.github.bert0ns.cribrum.model.Signal
import io.github.bert0ns.cribrum.sources.FakeMedia
import io.github.bert0ns.cribrum.sources.FilesystemImageSource
import io.github.bert0ns.cribrum.sources.MediaStoreImageSource
import io.github.bert0ns.cribrum.sources.MergedImageSource
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.util.zip.Adler32
import java.util.zip.CRC32

/**
 * The tables a scan leaves, against a dump kept in `scan-tables.txt`, recorded before the scan's fixed costs were cut
 * (#354): a speedup of the scan must leave every row as it was. The files, detectors, embedder and hashes are made
 * here from plain arithmetic, so the dump is the same on any machine. A change that is meant to change the tables
 * writes the new dump to `build/scan-tables.txt`, to copy over the old one.
 *
 * Four scans: a first one where one perceptual hash fails, a second after the user and time changed things, a third
 * with nothing changed, which must equal the second, and a fourth after a detector's version went up. MediaStore lists
 * the walk's images, a card's, a file the walk refuses by its head, one in a folder both leave out, and a row whose
 * file is gone.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ScanTablesTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val index = memoryIndex()
    private val root by lazy { File(folder.root, "device") }
    private val card by lazy { File(folder.root, "card") }

    @After
    fun close() = index.close()

    @Test
    fun `a scan leaves the tables it left before the fixed costs were cut`() {
        val a = png("DCIM/Camera/a.png", 0)
        val copy = png("WhatsApp/Media/a-copy.png", 0)
        val screenshot = png("Pictures/Screenshots/Screenshot_1.png", 1)
        val sticker = png("WhatsApp/Media/WhatsApp Stickers/s.png", 2)
        val changed = png("DCIM/Camera/d.png", 3)
        val gone = png("DCIM/Camera/gone.png", 4)
        file("Documents/notes.txt", "not an image".toByteArray(), 5)
        png("x.png", 6, card)
        file("Pictures/broken.png", "not an image at all".toByteArray(), 9) // MediaStore lists it, the walk refuses its head
        png("Android/.Trash/t.png", 10) // MediaStore lists it, both leave it out
        val touched = png("DCIM/Camera/touched.png", 11)

        // The copy's perceptual hash fails, so the next scan has only that hash to compute for it.
        val first = scan(pipeline(failingHash = copy.path))
        // What a user and time do between two scans.
        runBlocking {
            index.setReviewState(a.path, ReviewState.KEPT)
            index.setReviewState(screenshot.path, ReviewState.TRASHED)
            // A sticker scanned before stickers had no embedded detectors.
            index.setSignals(sticker.path, "forwarded", ForwardedDetector().version, emptyList())
        }
        gone.delete()
        png("DCIM/Camera/e.png", 7)
        png(changed.relativeTo(root).path, 8).setLastModified(MTIME + 99_000)
        touched.setLastModified(MTIME + 55_000) // the same size and a new time
        val second = scan(pipeline())
        val third = scan(pipeline())
        val fourth = scan(pipeline(sizeVersion = 3))

        val actual = "first scan\n$first\nsecond scan\n$second\nfourth scan\n$fourth"
        File("build/scan-tables.txt").writeText(actual)
        assertEquals(golden(), actual)
        assertEquals("a scan with nothing changed changes no row", second, third)
    }

    private fun pipeline(sizeVersion: Int = 2, failingHash: String? = null) = Pipeline(
        index,
        detectors = listOf(SizeDetector(sizeVersion), ForwardedDetector(), FirstValueDetector()),
        embedder = ArithmeticEmbedder(),
        preprocessing = { file -> file.readBytes().let { bytes -> FloatArray(3) { c -> (bytes.size * (c + 1) % 251).toFloat() / 251f } } },
        perceptualHasher = { image, _ -> if (image.path == failingHash) throw IOException("busy") else image.size.toULong() * 0x9E3779B97F4A7C15uL },
    )

    private fun scan(pipeline: Pipeline): String {
        val stats = runBlocking { pipeline.run(source()) }
        val failures = stats.failures.map { it.toString().replace(folder.root.path, "<tmp>") }.sorted()
        return (listOf(dump(), "[scan failures]") + failures).joinToString("\n")
    }

    private fun source(): MergedImageSource {
        // MediaStore lists what the walk finds too, and a file on a card the walk does not cover.
        FakeMedia.paths = (root.walkTopDown() + card.walkTopDown()).filter { it.isFile && it.extension == "png" }.map { it.path }.toList() +
            File(root, "DCIM/Camera/ghost.png").path // a row whose file is gone
        Robolectric.setupContentProvider(FakeMedia::class.java, MediaStore.AUTHORITY)
        val resolver = ApplicationProvider.getApplicationContext<Context>().contentResolver
        return MergedImageSource(listOf(FilesystemImageSource(root, index, index::images), MediaStoreImageSource(resolver, listOf(root, card))))
    }

    /**
     * Every table but Room's, its rows sorted, with the folder's path left out, blobs as a digest and no autoincrement id.
     * The fingerprints and group ids hash the paths, so they become `#1`, `#2`... in the order they first appear.
     */
    private fun dump(): String {
        val db = index.database.openHelper.readableDatabase
        val tables = db.query("SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%' AND name NOT LIKE 'room_%' AND name != 'android_metadata' ORDER BY name").use { cursor ->
            List(cursor.count) { cursor.moveToNext().let { cursor.getString(0) } }
        }
        val text = tables.joinToString("\n") { table ->
            db.query("SELECT * FROM $table").use { cursor ->
                val columns = cursor.columnNames.withIndex().filter { it.value != "id" }
                val rows = List(cursor.count) {
                    cursor.moveToNext()
                    columns.joinToString(" | ") { (i, name) ->
                        val value = when (cursor.getType(i)) {
                            android.database.Cursor.FIELD_TYPE_NULL -> "null"
                            android.database.Cursor.FIELD_TYPE_BLOB -> Algorithm.XXH3_64().createDigest().apply { update(cursor.getBlob(i)) }.digest().toHex()
                            else -> cursor.getString(i).replace(folder.root.path, "<tmp>")
                        }
                        "$name=$value"
                    }
                }.sorted()
                (listOf("[$table]") + rows).joinToString("\n")
            }
        }
        val names = HASHED.findAll(text).map { it.groupValues[1] }.distinct().withIndex().associate { (i, hash) -> hash to "#${i + 1}" }
        return names.entries.fold(text) { result, (hash, name) -> result.replace(hash, name) }
    }

    private fun golden(): String = javaClass.getResourceAsStream("scan-tables.txt")!!.bufferedReader().readText().trimEnd('\n')

    private fun png(path: String, seed: Int, under: File = root): File = file(path, png(seed), seed, under)

    private fun file(path: String, bytes: ByteArray, seed: Int, under: File = root): File = File(under, path).apply {
        parentFile!!.mkdirs()
        writeBytes(bytes)
        setLastModified(MTIME + seed * 1_000L)
    }

    /** An 8 by 6 RGB PNG with stored (uncompressed) data, so its bytes depend on [seed] alone. */
    private fun png(seed: Int): ByteArray {
        val width = 8 + seed % 3
        val height = 6
        val raw = ByteArrayOutputStream().apply {
            repeat(height) { y ->
                write(0)
                repeat(width * 3) { x -> write((seed * 37 + y * 11 + x * 5) and 0xff) }
            }
        }.toByteArray()
        val zlib = ByteArrayOutputStream().apply {
            write(byteArrayOf(0x78, 0x01))
            write(1) // the last block, stored
            write(raw.size and 0xff)
            write(raw.size shr 8)
            write(raw.size.inv() and 0xff)
            write((raw.size.inv() shr 8) and 0xff)
            write(raw)
            val adler = Adler32().apply { update(raw) }.value.toInt()
            DataOutputStream(this).writeInt(adler)
        }.toByteArray()
        val out = ByteArrayOutputStream()
        val data = DataOutputStream(out)
        data.write(byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a))
        fun chunk(type: String, body: ByteArray) {
            data.writeInt(body.size)
            val typed = type.toByteArray() + body
            data.write(typed)
            data.writeInt(CRC32().apply { update(typed) }.value.toInt())
        }
        chunk(
            "IHDR",
            ByteArrayOutputStream().apply {
                DataOutputStream(this).run {
                    writeInt(width)
                    writeInt(height)
                    write(byteArrayOf(8, 2, 0, 0, 0))
                }
            }.toByteArray(),
        )
        chunk("IDAT", zlib)
        chunk("IEND", ByteArray(0))
        return out.toByteArray()
    }

    /** A signal from the file's size and the metadata, before the embeddings. */
    private class SizeDetector(override val version: Int = 2) : Detector {
        override val id = "size"
        override val category = Category.BLURRY

        override suspend fun analyze(image: ImageRef, context: AnalysisContext): List<Signal> {
            val metadata = context.metadata(image)
            return listOf(Signal(id, version, Category.BLURRY, (image.size % 100) / 100f, mapOf("width" to "${metadata.width}", "mime" to "${metadata.mime}")))
        }
    }

    /** A signal from the embedding's first value. */
    private class FirstValueDetector : Detector {
        override val id = "first_value"
        override val version = 1
        override val category = Category.DOCUMENT
        override val usesEmbeddings = true

        override suspend fun analyze(image: ImageRef, context: AnalysisContext): List<Signal> = listOf(Signal(id, version, Category.DOCUMENT, context.embedding(image)[0].coerceIn(0f, 1f)))
    }

    private class ArithmeticEmbedder : Embedder {
        override val id = "arithmetic"
        override val version = 1

        override fun embedPixels(pixels: List<FloatArray>): List<FloatArray> = pixels.map { values ->
            val vector = FloatArray(512) { if (it < values.size) values[it] else 0.5f }
            val norm = kotlin.math.sqrt(vector.sumOf { it.toDouble() * it }).toFloat()
            FloatArray(512) { vector[it] / norm }
        }
    }

    private companion object {
        const val MTIME = 1_700_000_000_000L
        val HASHED = Regex("""(?:fingerprint=|group_id=|"group":")([0-9a-f]{16})""")
    }
}
