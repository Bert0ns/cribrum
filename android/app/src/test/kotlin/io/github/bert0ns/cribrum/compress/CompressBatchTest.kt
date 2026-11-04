package io.github.bert0ns.cribrum.compress

import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.ForegroundInfo
import androidx.work.ListenableWorker
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import io.github.bert0ns.cribrum.R
import io.github.bert0ns.cribrum.index.memoryIndex
import io.github.bert0ns.cribrum.metadata.readMetadata
import io.github.bert0ns.cribrum.model.ImageRef
import io.github.bert0ns.cribrum.model.toImageRef
import io.github.bert0ns.cribrum.pipeline.FakeScan
import io.github.bert0ns.cribrum.pipeline.IndexWorker
import io.github.bert0ns.cribrum.pipeline.Indexing
import io.github.bert0ns.cribrum.review.Review
import io.github.bert0ns.cribrum.trash.AppTrash
import io.github.bert0ns.cribrum.trash.copyFileFor
import io.github.bert0ns.cribrum.ui.HeatLevel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.Collections
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36])
class CompressBatchTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val app = ApplicationProvider.getApplicationContext<Context>()
    private val volume by lazy { folder.newFolder("storage") }
    private val camera by lazy { File(volume, "DCIM/Camera").apply { mkdirs() } }
    private val index = memoryIndex()
    private var flagged = emptySet<String>()
    private val trash by lazy { AppTrash({ listOf(volume) }, favorites = { it intersect flagged }) }

    /** What the worker asked the activity to mark again, and the callback that tells the batch the answer. */
    private val asked: MutableList<List<String>> = Collections.synchronizedList(mutableListOf())

    @Volatile
    private var answer: (Int) -> Unit = {}

    @Volatile
    private var wanted: () -> Boolean = { true }
    private val scans = AtomicInteger()
    private val scanGate = CompletableDeferred<Unit>()

    /** The paths the worker started compressing, in order. */
    private val started: MutableList<String> = Collections.synchronizedList(mutableListOf())

    /** Runs as a photo starts, before the compressor. Like the compressor, a cancel does not stop it. */
    private var onPhoto: suspend (ImageRef) -> Unit = {}

    @Volatile
    private var heat: HeatLevel? = null

    private val foregrounds: MutableList<ForegroundInfo> = Collections.synchronizedList(mutableListOf())

    /** Runs as a worker starts, before it takes its request. */
    private var onWork: suspend (UUID) -> Unit = {}

    private inner class TestCompress(context: Context, parameters: WorkerParameters) : CompressWorker(context, parameters) {
        override val index get() = this@CompressBatchTest.index
        override val trash get() = this@CompressBatchTest.trash
        override val coolDownMs = 10L

        override fun heat() = this@CompressBatchTest.heat

        override fun askFavorites(result: BatchResult) {
            answer = { lost -> CompressBatch.favoritesLost(result, lost) }
            wanted = { CompressBatch.isFinished(result) }
            // Last: a test waits for this, then calls the two above.
            asked += result.favorites
        }

        override suspend fun doWork(): Result {
            onWork(id)
            return super.doWork()
        }

        override suspend fun setForegroundInfo(info: ForegroundInfo) {
            foregrounds += info
        }

        override suspend fun compress(image: ImageRef, level: CompressionLevel): Compression = withContext(NonCancellable) {
            started += image.path
            onPhoto(image)
            // Robolectric's ActivityManager reports no free memory.
            Compressor(trash, index, { Long.MAX_VALUE }).compress(image, level)
        }
    }

    @Before
    fun setUp() {
        CompressBatch.reset()
        val configuration = Configuration.Builder()
            .setExecutor(SynchronousExecutor())
            .setWorkerFactory(object : WorkerFactory() {
                override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters): ListenableWorker = when (workerClassName) {
                    CompressWorker::class.java.name -> TestCompress(appContext, workerParameters)
                    else -> FakeScan(appContext, workerParameters, scans, scanGate)
                }
            })
            .build()
        WorkManagerTestInitHelper.initializeTestWorkManager(app, configuration)
    }

    /** Work left running would keep the process-wide lock for the next test. */
    @After
    fun tearDown() {
        scanGate.complete(Unit)
        heat = null
        runBlocking { withTimeout(10_000) { while (running()) yield() } }
        CompressBatch.reset()
    }

    private fun works() = WorkManager.getInstance(app).run { getWorkInfosForUniqueWork(CompressBatch.WORK).get() + getWorkInfosByTag(Indexing.TAG).get() }

    private fun running() = works().any { !it.state.isFinished }

    private fun scanEnded() = runBlocking { withTimeout(10_000) { while (WorkManager.getInstance(app).getWorkInfosByTag(Indexing.TAG).get().any { !it.state.isFinished }) yield() } }

    private fun photo(name: String, quality: Int = 100): ImageRef {
        val image = File(camera, name).apply { writeBytes(encodedJpeg(800, 600, quality)) }.toImageRef()
        runBlocking { index.record(image) }
        return image
    }

    private fun start(vararg images: ImageRef) = assertTrue(CompressBatch.start(app, images.map { it.path }, CompressionLevel.HIGH))

    private fun state(predicate: (BatchState) -> Boolean): BatchState = runBlocking { withTimeout(10_000) { CompressBatch.state.first(predicate) } }

    private fun finished() = (state { it is BatchState.Finished } as BatchState.Finished).result

    private fun waitFor(condition: () -> Boolean) = runBlocking { withTimeout(10_000) { while (!condition()) yield() } }

    /** The title and text of the batch's notification, once its title is [title]: the worker posts it after the state. */
    private fun notification(title: String): String? {
        val manager = shadowOf(app.getSystemService(NotificationManager::class.java))
        waitFor { manager.getNotification(CompressWorker.NOTIFICATION_ID)?.extras?.getString("android.title") == title }
        return manager.getNotification(CompressWorker.NOTIFICATION_ID).extras.getString("android.text")
    }

    @Test
    fun `the result counts the photos compressed, those refused and why, and the real saving`() {
        val small = photo("a.jpg")
        val notSmaller = photo("b.jpg", quality = 40)
        val notIndexed = File(camera, "c.jpg").apply { writeBytes(encodedJpeg(800, 600, 100)) }.toImageRef()

        start(small, notSmaller, notIndexed)
        val result = finished()

        assertEquals(listOf(small.path, notSmaller.path), started)
        assertEquals(3, result.total)
        assertEquals(1, result.compressed)
        assertEquals(mapOf(Refusal.NOT_SMALLER to 1, Refusal.FAILED to 1), result.refused)
        assertEquals(small.size - File(small.path).length(), result.savedBytes)
        assertEquals(listOf(small), result.originals)
        assertEquals(0, result.notReached)
        assertEquals(listOf(small), runBlocking { trash.trashed() }.map { it.original })
    }

    @Test
    fun `the compressed photos that were favourites are asked for once, after the batch, and no others`() {
        val heart = photo("heart.jpg")
        val plain = photo("plain.jpg")
        val notSmaller = photo("low.jpg", quality = 40)
        flagged = setOf(heart.path, notSmaller.path)

        start(heart, plain, notSmaller)
        val result = finished()

        assertEquals(listOf(heart.path), result.favorites)
        waitFor { asked.isNotEmpty() }
        assertEquals(listOf(listOf(heart.path)), asked)
        assertEquals(0, (CompressBatch.state.value as BatchState.Finished).favoritesLost)
    }

    @Test
    fun `a batch without a favourite asks for nothing`() {
        start(photo("a.jpg"))

        assertEquals(emptyList<String>(), finished().favorites)
        waitFor { !running() }
        assertEquals(emptyList<List<String>>(), asked)
    }

    @Test
    fun `a declined request is on the finished batch, and undo still ends it`() {
        val heart = photo("heart.jpg")
        flagged = setOf(heart.path)
        start(heart)
        val result = finished()
        waitFor { asked.isNotEmpty() }

        answer(1)

        assertEquals(BatchState.Finished(result, favoritesLost = 1), CompressBatch.state.value)
        runBlocking { CompressBatch.undo(Review(index, trash), result) }
        assertEquals(BatchState.Idle, CompressBatch.state.value)
    }

    @Test
    fun `a request for a batch the user undid is no longer wanted`() {
        val heart = photo("heart.jpg")
        flagged = setOf(heart.path)
        start(heart)
        val result = finished()
        waitFor { asked.isNotEmpty() }
        assertTrue(wanted())

        runBlocking { CompressBatch.undo(Review(index, trash), result) }

        assertFalse(wanted())
    }

    @Test
    fun `an answer for a batch that is gone changes nothing`() {
        val heart = photo("heart.jpg")
        flagged = setOf(heart.path)
        start(heart)
        waitFor { asked.isNotEmpty() }
        val late = answer
        runBlocking { CompressBatch.undo(Review(index, trash), finished()) }

        late(1)

        assertEquals(BatchState.Idle, CompressBatch.state.value)
    }

    @Test
    fun `a second batch is refused while one runs`() {
        val gate = CompletableDeferred<Unit>()
        onPhoto = { gate.await() }
        val image = photo("a.jpg")
        start(image)
        waitFor { started.isNotEmpty() }

        assertFalse(CompressBatch.start(app, listOf(image.path), CompressionLevel.SMALLEST))

        gate.complete(Unit)
        assertEquals(1, finished().compressed)
    }

    @Test
    fun `a batch asked for during a scan waits for it, and runs when it ends`() {
        val image = photo("a.jpg")
        Indexing.scanNow(app)
        waitFor { scans.get() == 1 }

        start(image)
        state { it is BatchState.WaitingForScan }

        assertEquals(BatchState.WaitingForScan(1), CompressBatch.state.value)
        val waiting = app.getString(R.string.compress_waiting)
        notification(waiting)
        assertEquals(waiting, foregrounds.single().notification.extras.getString("android.title"))
        assertEquals(emptyList<String>(), started)
        assertEquals(image, File(image.path).toImageRef())
        scanGate.complete(Unit)
        assertEquals(1, finished().compressed)
    }

    @Test
    fun `a scan asked for while a batch runs does not start, and one asked for after it does`() {
        val gate = CompletableDeferred<Unit>()
        onPhoto = { gate.await() }
        start(photo("a.jpg"))
        waitFor { started.isNotEmpty() }

        Indexing.scanNow(app)
        scanEnded()

        assertEquals(0, scans.get())
        gate.complete(Unit)
        assertEquals(1, finished().compressed)
        waitFor { !running() }
        Indexing.scanNow(app)
        waitFor { scans.get() == 1 }
    }

    @Test
    fun `when a batch and a scan are asked for at once, the batch goes first`() {
        // Held, so the batch cannot end before the scan worker has run: a scan asked for after the batch does start.
        val gate = CompletableDeferred<Unit>()
        onPhoto = { gate.await() }

        start(photo("a.jpg"))
        Indexing.scanNow(app)
        scanEnded()

        assertEquals(0, scans.get())
        gate.complete(Unit)
        assertEquals(1, finished().compressed)
        waitFor { !running() }
        assertFalse(IndexWorker.running.isLocked)
    }

    @Test
    fun `cancelling stops after the current photo, which stays compressed`() {
        val inFirst = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()
        onPhoto = {
            inFirst.complete(Unit)
            gate.await()
        }
        val first = photo("a.jpg")
        val others = listOf(photo("b.jpg"), photo("c.jpg"))
        start(first, *others.toTypedArray())
        runBlocking { withTimeout(10_000) { inFirst.await() } }

        CompressBatch.cancel(app)
        gate.complete(Unit)
        val result = finished()

        assertEquals(1, result.compressed)
        assertEquals(2, result.notReached)
        assertEquals(listOf(first), result.originals)
        assertEquals(listOf(first.path), started)
        others.forEach { assertEquals(it, File(it.path).toImageRef()) }
        waitFor { !running() }
        assertFalse(IndexWorker.running.isLocked)
    }

    @Test
    fun `cancelling a batch that waits for a scan compresses nothing`() {
        val image = photo("a.jpg")
        Indexing.scanNow(app)
        waitFor { scans.get() == 1 }
        start(image)

        CompressBatch.cancel(app)
        val result = finished()
        scanGate.complete(Unit)
        scanEnded()

        assertEquals(1, result.notReached)
        assertEquals(emptyList<String>(), started)
        assertEquals(image, File(image.path).toImageRef())
    }

    @Test
    fun `undo puts every original back and its copy in the trash`() {
        val images = listOf(photo("a.jpg"), photo("b.jpg"))
        val bytes = images.map { File(it.path).readBytes() }
        start(*images.toTypedArray())
        val result = finished()
        assertEquals(2, result.compressed)

        val stayed = runBlocking { CompressBatch.undo(Review(index, trash), result) }

        assertEquals(emptyList<ImageRef>(), stayed)
        images.zip(bytes).forEach { (image, original) -> assertArrayEquals(original, File(image.path).readBytes()) }
        val items = runBlocking { trash.trashed() }
        assertEquals(images.map { it.path }.toSet(), items.map { it.original.path }.toSet())
        assertTrue(items.none { it.original in images })
        assertEquals(BatchState.Idle, CompressBatch.state.value)
    }

    /** The photos the compress page lists, with the level a marker names, as the scan would leave them. */
    private fun listed() = runBlocking { index.compressible(36) }.map { it.size to it.info.marker?.level }

    @Test
    fun `a compressed photo shows as compressed with its level at once, with no scan, and undo shows it as compressible again`() {
        val image = photo("a.jpg")
        runBlocking {
            index.setMetadata(image.path, readMetadata(File(image.path)))
            index.setJpeg(image.path, readJpeg(File(image.path)))
        }
        assertEquals(listOf(image.size to null), listed())

        start(image)
        val result = finished()
        assertEquals(listOf(File(image.path).length() to "high"), listed())

        runBlocking { CompressBatch.undo(Review(index, trash), result) }
        assertEquals(listOf(image.size to null), listed())
        assertEquals(0, scans.get())
    }

    @Test
    fun `a severe heat pauses the batch between photos, and the notification says so`() {
        val first = photo("a.jpg")
        val second = photo("b.jpg")
        onPhoto = { if (it == first) heat = HeatLevel.SEVERE }
        start(first, second)

        val paused = state { it is BatchState.Running && it.paused } as BatchState.Running

        assertEquals(1, paused.done)
        assertEquals(listOf(first.path), started)
        assertTrue(notification("Compressing 2 of 2")!!.startsWith("Paused while the phone cools down · "))
        heat = HeatLevel.MODERATE
        assertEquals(2, finished().compressed)
    }

    @Test
    fun `a batch first discards the copies a killed compression left`() {
        val image = photo("a.jpg")
        val orphan = copyFileFor(File(image.path)).apply { writeText("left by a killed compression") }

        assertTrue(CompressBatch.start(app, emptyList(), CompressionLevel.HIGH))
        finished()

        assertFalse(orphan.exists())
        assertEquals(listOf("a.jpg"), camera.list()!!.toList())
    }

    @Test
    fun `a worker that is not the batch's own leaves its request alone`() {
        val stray = OneTimeWorkRequestBuilder<CompressWorker>().build()
        val gate = CompletableDeferred<Unit>()
        onWork = { if (it != stray.id) gate.await() }
        val image = photo("a.jpg")
        start(image)

        // As WorkManager's rerun of a worker from an earlier process.
        WorkManager.getInstance(app).enqueue(stray)
        waitFor { WorkManager.getInstance(app).getWorkInfoById(stray.id).get()!!.state.isFinished }

        assertEquals(emptyList<String>(), started)
        assertEquals(BatchState.Running(0, 1, 0), CompressBatch.state.value)
        gate.complete(Unit)
        assertEquals(1, finished().compressed)
    }

    @Test
    fun `from API 35 the batch runs as media processing, apart from the scan's dataSync budget`() {
        start(photo("a.jpg"))
        finished()

        assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING, foregrounds.single().foregroundServiceType)
    }

    @Test
    @Config(sdk = [34])
    fun `below API 35 the batch runs as dataSync`() {
        start(photo("a.jpg"))
        finished()

        assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC, foregrounds.single().foregroundServiceType)
    }

    @Test
    fun `undo waits for a running scan`() {
        val image = photo("a.jpg")
        val bytes = File(image.path).readBytes()
        start(image)
        val result = finished()
        waitFor { !running() }
        Indexing.scanNow(app)
        waitFor { scans.get() == 1 }

        val undo = CoroutineScope(Dispatchers.Default).async { CompressBatch.undo(Review(index, trash), result) }
        runBlocking { delay(300) }

        assertFalse(undo.isCompleted)
        assertFalse(bytes.contentEquals(File(image.path).readBytes()))
        scanGate.complete(Unit)
        assertEquals(emptyList<ImageRef>(), runBlocking { withTimeout(10_000) { undo.await() } })
        assertArrayEquals(bytes, File(image.path).readBytes())
    }

    @Test
    fun `a path asked for twice is compressed once`() {
        val image = photo("a.jpg")

        assertTrue(CompressBatch.start(app, listOf(image.path, image.path), CompressionLevel.HIGH))
        val result = finished()

        assertEquals(listOf(image.path), started)
        assertEquals(1, result.total)
        assertEquals(1, result.compressed)
    }

    @Test
    fun `undo also restores an original a swap left in the trash`() {
        val image = photo("a.jpg")
        val bytes = File(image.path).readBytes()
        trash.mover = { _, _ -> throw IOException("simulated") }
        start(image)
        val result = finished()
        assertEquals(mapOf(Refusal.LEFT_IN_TRASH to 1), result.refused)
        assertEquals(listOf(image), result.originals)
        assertFalse(File(image.path).exists())
        trash.mover = { from, to -> Files.move(from.toPath(), to.toPath()) }

        assertEquals(emptyList<ImageRef>(), runBlocking { CompressBatch.undo(Review(index, trash), result) })

        assertArrayEquals(bytes, File(image.path).readBytes())
        assertEquals(emptyList<ImageRef>(), runBlocking { trash.trashed() }.map { it.original })
    }

    @Test
    fun `cancelling during a pause for the heat stops before the next photo`() {
        val first = photo("a.jpg")
        val second = photo("b.jpg")
        onPhoto = { if (it == first) heat = HeatLevel.SEVERE }
        start(first, second)
        state { it is BatchState.Running && it.paused }

        CompressBatch.cancel(app)
        val result = finished()

        assertEquals(1, result.compressed)
        assertEquals(1, result.notReached)
        assertEquals(listOf(first.path), started)
        assertEquals(second, File(second.path).toImageRef())
        waitFor { !running() }
        assertFalse(IndexWorker.running.isLocked)
    }
}
