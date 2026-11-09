package io.github.bert0ns.cribrum.ui

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import coil3.size.Size
import io.github.bert0ns.cribrum.compress.BatchState
import io.github.bert0ns.cribrum.compress.CompressBatch
import io.github.bert0ns.cribrum.pipeline.IndexWorker
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import kotlin.coroutines.CoroutineContext

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class TilePreparingTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val photos = List(120) { File("/photos/$it.jpg") }
    private val prepared = mutableListOf<File>()
    private var size: Size? = Size(300, 300)

    /** True from the photo at this position on. */
    private var hotFrom = Int.MAX_VALUE

    /** Runs before the photo at its position is prepared. */
    private val before = mutableMapOf<Int, () -> Unit>()

    private inner class Fake(context: Context, parameters: WorkerParameters) : TilePrepareWorker(context, parameters) {
        override fun tileSize() = size

        override suspend fun photos() = this@TilePreparingTest.photos

        override suspend fun prepare(file: File, size: Size, thread: CoroutineContext) {
            assertEquals(this@TilePreparingTest.size, size)
            prepared += file
            before[prepared.size]?.invoke()
        }

        override fun hot() = prepared.size >= hotFrom
    }

    private fun run(): ListenableWorker.Result = runBlocking {
        TestListenableWorkerBuilder<TilePrepareWorker>(context).setWorkerFactory(object : WorkerFactory() {
            override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters) = Fake(appContext, workerParameters)
        }).build().doWork()
    }

    @After
    fun tearDown() {
        if (IndexWorker.running.isLocked) IndexWorker.running.unlock()
        CompressBatch.reset()
    }

    @Test
    fun `it prepares the first two Compress pages, in the list's order`() {
        assertEquals(ListenableWorker.Result.success(), run())
        assertEquals(photos.take(2 * COMPRESS_PAGE_SIZE), prepared)
    }

    @Test
    fun `before the Compress page has shown a tile it has no size, and prepares nothing`() {
        size = null
        assertEquals(ListenableWorker.Result.success(), run())
        assertEquals(emptyList<File>(), prepared)
    }

    @Test
    fun `it stops when a scan starts`() {
        before[3] = { runBlocking { IndexWorker.running.lock() } }
        assertEquals(ListenableWorker.Result.success(), run())
        assertEquals(photos.take(3), prepared)
    }

    @Test
    fun `it gives way to a compression batch and is retried`() {
        before[2] = { CompressBatch.publish(BatchState.Running(0, 5, 0)) }
        assertEquals(ListenableWorker.Result.retry(), run())
        assertEquals(photos.take(2), prepared)
    }

    @Test
    fun `it stops when the phone is hot`() {
        hotFrom = 4
        assertEquals(ListenableWorker.Result.success(), run())
        assertEquals(photos.take(4), prepared)
    }
}
