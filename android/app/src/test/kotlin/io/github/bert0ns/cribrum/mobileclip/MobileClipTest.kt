package io.github.bert0ns.cribrum.mobileclip

import io.github.bert0ns.cribrum.runtime.ModelRuntime
import io.github.bert0ns.cribrum.runtime.Tensor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class MobileClipTest {
    private class Runtime : ModelRuntime {
        var closed = 0

        override fun run(inputs: Map<String, Tensor>): Map<String, Tensor> = emptyMap()

        override fun close() {
            closed++
        }
    }

    @Test
    fun `loading twice makes one model`() {
        var loads = 0
        val clip = MobileClip { Runtime().also { loads++ } }

        clip.load()
        clip.load()

        assertEquals(1, loads)
    }

    @Test
    fun `closing closes the model that was loaded`() {
        val runtime = Runtime()
        val clip = MobileClip { runtime }
        clip.load()

        clip.close()

        assertEquals(1, runtime.closed)
    }

    @Test
    fun `closing before the model was needed loads nothing`() {
        var loads = 0
        val clip = MobileClip { Runtime().also { loads++ } }

        clip.close()

        assertEquals(0, loads)
    }

    @Test
    fun `a model that does not load throws from load`() {
        val clip = MobileClip { throw IllegalStateException("damaged") }

        assertThrows(IllegalStateException::class.java) { clip.load() }
        clip.close()
    }
}
