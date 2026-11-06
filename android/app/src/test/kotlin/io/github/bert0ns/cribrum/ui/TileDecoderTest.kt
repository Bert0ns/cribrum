package io.github.bert0ns.cribrum.ui

import coil3.decode.DecodeResult
import coil3.decode.Decoder
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class TileDecoderTest {
    @Test
    fun `permits follow the cores and stay between 2 and 6`() {
        assertEquals(2, tilePermits(1))
        assertEquals(2, tilePermits(4))
        assertEquals(4, tilePermits(6))
        assertEquals(6, tilePermits(8))
        assertEquals(6, tilePermits(16))
    }

    @Test
    fun `size buckets round up to a power of two`() {
        val mb = 1024L * 1024L
        assertEquals("?", sizeBucket(null))
        assertEquals("<=1MB", sizeBucket(0))
        assertEquals("<=1MB", sizeBucket(mb))
        assertEquals("<=2MB", sizeBucket(mb + 1))
        assertEquals("<=8MB", sizeBucket(5 * mb))
        assertEquals("<=32MB", sizeBucket(32 * mb))
        assertEquals(">32MB", sizeBucket(32 * mb + 1))
    }

    @Test
    fun `no more decodes run at once than there are permits, and all of them finish`() = runTest {
        val permits = Semaphore(3)
        var running = 0
        var most = 0
        val inner = object : Decoder {
            override suspend fun decode(): DecodeResult? {
                running++
                most = maxOf(most, running)
                delay(10)
                running--
                return null
            }
        }
        List(10) { async { PermitDecoder(inner, permits, "<=1MB").decode() } }.awaitAll()
        assertEquals(3, most)
        assertEquals(3, permits.availablePermits)
    }

    @Test
    fun `a decode that fails gives its permit back`() = runTest {
        val permits = Semaphore(1)
        val failing = object : Decoder {
            override suspend fun decode(): DecodeResult? = error("broken file")
        }
        runCatching { PermitDecoder(failing, permits, "?").decode() }
        assertEquals(1, permits.availablePermits)
    }

    @Test
    fun `a cancelled tile gives its permit back, whether it waits or decodes`() = runTest {
        val permits = Semaphore(1)
        val hanging = object : Decoder {
            override suspend fun decode(): DecodeResult? = awaitCancellation()
        }
        val decoding = launch { PermitDecoder(hanging, permits, "?").decode() }
        val waiting = launch { PermitDecoder(hanging, permits, "?").decode() }
        testScheduler.runCurrent()
        assertEquals(0, permits.availablePermits)
        waiting.cancel()
        decoding.cancel()
        testScheduler.runCurrent()
        assertEquals(1, permits.availablePermits)
    }
}
