package io.github.bert0ns.cribrum.runtime

import ai.onnxruntime.OrtException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class FallbackRuntimeTest {
    private class Fake(val name: String, var failing: Boolean = false) : ModelRuntime {
        var runs = 0
        var closed = false

        override fun run(inputs: Map<String, Tensor>): Map<String, Tensor> {
            runs++
            if (failing) throw OrtException("$name failed")
            return mapOf(name to Tensor(longArrayOf(1), floatArrayOf(1f)))
        }

        override fun close() {
            closed = true
        }
    }

    private val input = mapOf("x" to Tensor(longArrayOf(1), floatArrayOf(0f)))

    @Test
    fun `a working gpu never builds the fallback`() {
        val gpu = Fake("gpu")
        var built = 0
        val runtime = FallbackRuntime(gpu) {
            built++
            Fake("cpu")
        }

        repeat(3) { assertEquals(setOf("gpu"), runtime.run(input).keys) }
        assertEquals(0, built)
    }

    @Test
    fun `a failing gpu run is retried on the fallback, which takes every later run`() {
        val gpu = Fake("gpu", failing = true)
        val cpu = Fake("cpu")
        var built = 0
        val runtime = FallbackRuntime(gpu) {
            built++
            cpu
        }

        assertEquals(setOf("cpu"), runtime.run(input).keys)
        assertEquals(setOf("cpu"), runtime.run(input).keys)

        assertEquals(1, gpu.runs)
        assertTrue(gpu.closed)
        assertEquals(2, cpu.runs)
        assertEquals(1, built)
        runtime.close()
        assertTrue(cpu.closed)
    }

    @Test
    fun `a fallback that fails too passes its error on, and is not rebuilt`() {
        val cpu = Fake("cpu", failing = true)
        var built = 0
        val runtime = FallbackRuntime(Fake("gpu", failing = true)) {
            built++
            cpu
        }

        assertThrows(OrtException::class.java) { runtime.run(input) }
        assertThrows(OrtException::class.java) { runtime.run(input) }
        assertEquals(1, built)
    }

    @Test
    fun `an error that is not the runtime's own does not switch to the fallback`() {
        var built = 0
        val runtime = FallbackRuntime(
            object : ModelRuntime {
                override fun run(inputs: Map<String, Tensor>): Map<String, Tensor> = throw IllegalArgumentException("bad input")
            },
        ) {
            built++
            Fake("cpu")
        }

        assertThrows(IllegalArgumentException::class.java) { runtime.run(input) }
        assertEquals(0, built)
    }

    @Test
    fun `a fallback that cannot be built leaves the failed runtime open, to fail and be closed once`() {
        val gpu = Fake("gpu", failing = true)
        val runtime = FallbackRuntime(gpu) { throw OrtException("no cpu session") }

        assertThrows(OrtException::class.java) { runtime.run(input) }
        assertThrows(OrtException::class.java) { runtime.run(input) }
        assertEquals(false, gpu.closed)
        runtime.close()
        assertTrue(gpu.closed)
    }
}
