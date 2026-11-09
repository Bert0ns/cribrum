package io.github.bert0ns.cribrum.runtime

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.random.Random

@RunWith(RobolectricTestRunner::class) // for the trace section of each run
class OnnxRuntimeTest {
    private val yunet = OnnxRuntime(javaClass.getResourceAsStream("/face_detection_yunet_2023mar.onnx")!!.use { it.readBytes() }, threads = 1)

    private fun Tensor.floats() = FloatArray(values.limit()) { values[it] }

    @Test
    fun `a direct buffer the model reads in place gives the outputs of the same values in an array`() {
        val random = Random(0)
        val shape = longArrayOf(1, 3, 640, 640)
        val values = FloatArray(3 * 640 * 640) { random.nextFloat() * 255f }
        val direct = floatBuffer(values.size).also { it.duplicate().put(values) }

        val fromArray = yunet.run(mapOf("input" to Tensor(shape, values)))
        val fromBuffer = yunet.run(mapOf("input" to Tensor(shape, direct)))

        assertEquals(fromArray.keys, fromBuffer.keys)
        for ((name, tensor) in fromArray) {
            assertArrayEquals(name, tensor.shape, fromBuffer.getValue(name).shape)
            assertArrayEquals(name, tensor.floats(), fromBuffer.getValue(name).floats(), 0f)
        }
        // The run read the buffer where it was: a second run, as a fallback runtime makes, reads the same values.
        assertArrayEquals(fromBuffer.getValue("cls_8").floats(), yunet.run(mapOf("input" to Tensor(shape, direct))).getValue("cls_8").floats(), 0f)
    }
}
