// ModelRuntime: on-device ML models behind one interface (docs/design.md §7). Mirrors
// lab/src/cribrum_lab/runtime.py: both run the same ONNX files, so both compute the same outputs.
package io.github.bert0ns.cribrum.runtime

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtLoggingLevel
import ai.onnxruntime.OrtSession
import java.nio.FloatBuffer

/** Float values in row-major order, with their shape. */
class Tensor(val shape: LongArray, val values: FloatArray)

interface ModelRuntime {
    fun run(inputs: Map<String, Tensor>): Map<String, Tensor>
}

/** ONNX Runtime on the CPU, on every core unless [threads] says otherwise. */
class OnnxRuntime(model: ByteArray, threads: Int? = null) : ModelRuntime {
    private val environment = OrtEnvironment.getEnvironment()
    private val session = OrtSession.SessionOptions().use { options ->
        options.setSessionLogLevel(OrtLoggingLevel.ORT_LOGGING_LEVEL_ERROR) // warnings about unused initializers are noise
        threads?.let(options::setIntraOpNumThreads)
        environment.createSession(model, options)
    }

    override fun run(inputs: Map<String, Tensor>): Map<String, Tensor> {
        val tensors = inputs.mapValues { (_, tensor) -> OnnxTensor.createTensor(environment, FloatBuffer.wrap(tensor.values), tensor.shape) }
        try {
            session.run(tensors).use { result ->
                return result.associate { (name, value) ->
                    val tensor = value as OnnxTensor
                    val buffer = tensor.floatBuffer
                    name to Tensor(tensor.info.shape, FloatArray(buffer.remaining()).also { buffer.get(it) })
                }
            }
        } finally {
            tensors.values.forEach { it.close() }
        }
    }
}
