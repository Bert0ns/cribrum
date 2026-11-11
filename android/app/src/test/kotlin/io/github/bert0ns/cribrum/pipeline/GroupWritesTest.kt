package io.github.bert0ns.cribrum.pipeline

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.github.bert0ns.cribrum.detectors.AnalysisContext
import io.github.bert0ns.cribrum.detectors.Detector
import io.github.bert0ns.cribrum.detectors.GroupDetector
import io.github.bert0ns.cribrum.imaging.TestImage
import io.github.bert0ns.cribrum.index.ImageIndex
import io.github.bert0ns.cribrum.index.IndexDatabase
import io.github.bert0ns.cribrum.model.Category
import io.github.bert0ns.cribrum.model.GROUP_KEY
import io.github.bert0ns.cribrum.model.ImageRef
import io.github.bert0ns.cribrum.model.ROLE_KEY
import io.github.bert0ns.cribrum.model.ReviewState
import io.github.bert0ns.cribrum.model.Role
import io.github.bert0ns.cribrum.model.Signal
import io.github.bert0ns.cribrum.sources.FilesystemImageSource
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * The group step writes only the images whose rows change (#355). At every store of a group detector in real scans,
 * this checks the tables against what the write before that, [ImageIndex.setSignalsOfMany] on every image, leaves on
 * a copy of the index taken just before the store: the same `analyses`, `signals` with ids aside, `kept_signals` and
 * `kept_detectors`, and every image's signals in the same order, which is the order the ranker and the review read.
 * The scans keep images before and after the group detectors ran, put back a kept state that lacks them, rewrite a
 * detector's signals after the group ones, and forget a row while the group step runs.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class GroupWritesTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val index = ImageIndex(Room.inMemoryDatabaseBuilder(context, IndexDatabase::class.java).build())
    private val root by lazy { File(folder.root, "device") }

    /** What the old write left on the copy, to compare with the index once the store is over. */
    private var expected: String? = null
    private var checks = 0

    @After
    fun close() = index.close()

    @Test
    fun `the group step leaves the tables and the signal order that rewriting every image left`() {
        val a = save("DCIM/Camera/a.png", 0)
        val copy = copy(a, "WhatsApp/Media/a-copy.png")
        val b = save("DCIM/Camera/b.png", 1)
        val c = save("DCIM/Camera/c.png", 2)
        val e = save("DCIM/Camera/e.png", 3)
        // The group detectors have not run when the copy is kept, so their first result is seen.
        scan(groups = emptyList())
        keep(copy)
        scripted = mapOf(b to listOf(member("s1", Role.BEST)), c to listOf(member("s1", Role.INFERIOR), member("s2", Role.INFERIOR)))
        scan()
        // Kept after the group detectors, and kept again by an undo that remembers only the size detector.
        keep(b)
        runBlocking { index.restoreReviewState(c.path, ReviewState.KEPT, emptySet(), setOf("size")) }

        // A new copy of b, a role that changes, and the size detector rewritten after the group signals.
        copy(b, "WhatsApp/Media/b-copy.png")
        scripted = mapOf(b to listOf(member("s1", Role.INFERIOR)), c to listOf(member("s1", Role.INFERIOR), member("s2", Role.INFERIOR)), e to listOf(member("s1", Role.BEST)))
        scan(sizeVersion = 3)
        val tables = dump(index)
        val version = index.version
        val before = checks

        scan(sizeVersion = 3)
        assertEquals("a scan with nothing changed runs no group detector", before, checks)
        assertEquals(tables, dump(index))
        assertEquals("and keeps the review's ranking", version, index.version)

        // A changed file, a scripted signal that moves, and a row forgotten while the near duplicates are compared.
        File(root, "DCIM/Camera/c.png").setLastModified(c.mtimeNs / 1_000_000 + 60_000)
        scripted = mapOf(c to listOf(member("s2", Role.INFERIOR), member("s1", Role.INFERIOR)), e to listOf(member("s1", Role.BEST)))
        val stats = scan(sizeVersion = 3, forget = "near_duplicate" to e.path)
        assertEquals(listOf("near_duplicate", "burst", "scripted"), stats.failures.map { it.step })
        assertTrue(stats.failures.all { "NoSuchElementException" in it.error })

        // The next scan finds e again as a new file, and a new exact duplicate version replaces every analysis.
        scan(sizeVersion = 3, exactVersion = 2)
        assertEquals(4 * 4, checks)
    }

    private var scripted: Map<ImageRef, List<Signal>> = emptyMap()

    private fun scan(
        sizeVersion: Int = 2,
        groups: List<GroupDetector>? = null,
        exactVersion: Int? = null,
        forget: Pair<String, String>? = null,
    ): Stats {
        val detectors = groups ?: (GROUP_DETECTORS + Scripted()).map { detector ->
            Checked(detector, version = if (detector.id == "exact_duplicate") exactVersion ?: detector.version else detector.version, forget = forget?.takeIf { it.first == detector.id }?.second)
        }
        val pipeline = Pipeline(index, detectors = listOf(SizeDetector(sizeVersion)), groupDetectors = detectors)
        return runBlocking { pipeline.run(FilesystemImageSource(root)) }.also { compare() }
    }

    /** Runs [detector] and, before handing its result to the store, writes it the old way on a copy of the index. */
    private inner class Checked(private val detector: GroupDetector, override val version: Int, private val forget: String?) : GroupDetector {
        override val id = detector.id
        override val category = detector.category

        override suspend fun analyze(images: List<ImageRef>, context: AnalysisContext): Map<ImageRef, List<Signal>> {
            compare()
            val signals = detector.analyze(images, context)
            forget?.let { index.forget(listOf(it)) } // the user's purge, between the analysis and the store
            val file = File(folder.root, "copy-${checks + 1}.sqlite")
            index.database.openHelper.writableDatabase.execSQL("VACUUM INTO '${file.path}'")
            ImageIndex(Room.databaseBuilder(this@GroupWritesTest.context, IndexDatabase::class.java, file.path).build()).use { old ->
                runCatching { old.setSignalsOfMany(id, version, images.associate { it.path to signals[it].orEmpty() }) }
                expected = dump(old)
            }
            return signals
        }
    }

    /** Compares the index with what the old write left, once the store it stands for is over. */
    private fun compare() {
        val want = expected ?: return
        expected = null
        checks++
        assertEquals("store $checks", want, dump(index))
    }

    /** The four tables, rows sorted, and the signals in each image's id order, with the ids and the folder left out. */
    private fun dump(index: ImageIndex): String {
        val db = index.database.openHelper.readableDatabase
        fun rows(sql: String, sorted: Boolean): String = db.query(sql).use { cursor ->
            val columns = cursor.columnNames.withIndex().filter { it.value != "id" }
            val lines = List(cursor.count) {
                cursor.moveToNext()
                columns.joinToString(" | ") { (i, name) -> "$name=${cursor.getString(i)}" }.replace(root.path, "")
            }
            (if (sorted) lines.sorted() else lines).joinToString("\n")
        }
        return listOf(
            "[analyses]\n" + rows("SELECT * FROM analyses", sorted = true),
            "[signals]\n" + rows("SELECT * FROM signals ORDER BY path, id", sorted = false),
            "[kept_signals]\n" + rows("SELECT * FROM kept_signals", sorted = true),
            "[kept_detectors]\n" + rows("SELECT * FROM kept_detectors", sorted = true),
        ).joinToString("\n")
    }

    private fun keep(image: ImageRef) = runBlocking { index.setReviewState(image.path, ReviewState.KEPT) }

    private fun save(path: String, seed: Int): ImageRef = TestImage.texture(64, 48, seed).save(File(root, path).apply { parentFile!!.mkdirs() }).let(::ref)

    private fun copy(image: ImageRef, path: String): ImageRef = File(image.path).copyTo(File(root, path).apply { parentFile!!.mkdirs() }).let(::ref)

    private fun ref(file: File) = ImageRef(file.path, file.length(), file.lastModified() * 1_000_000)

    private fun member(group: String, role: Role) = Signal("scripted", 1, Category.BURST, if (role == Role.BEST) 0f else 0.8f, mapOf(GROUP_KEY to group, ROLE_KEY to role.value))

    /** A group detector whose signals the test sets: several per image, in an order that can change. */
    private inner class Scripted : GroupDetector {
        override val id = "scripted"
        override val version = 1
        override val category = Category.BURST

        override suspend fun analyze(images: List<ImageRef>, context: AnalysisContext): Map<ImageRef, List<Signal>> {
            val byPath = scripted.mapKeys { it.key.path }
            return images.mapNotNull { image -> byPath[image.path]?.let { image to it } }.toMap()
        }
    }

    /** A per-image detector whose version makes the pipeline rewrite its signals after the group ones. */
    private class SizeDetector(override val version: Int) : Detector {
        override val id = "size"
        override val category = Category.BLURRY

        override suspend fun analyze(image: ImageRef, context: AnalysisContext): List<Signal> = listOf(Signal(id, version, Category.BLURRY, (image.size % 100) / 100f))
    }
}
