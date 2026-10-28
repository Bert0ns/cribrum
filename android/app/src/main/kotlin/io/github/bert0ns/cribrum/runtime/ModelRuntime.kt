// ModelRuntime: on-device ML models behind one interface (docs/design.md §7). Mirrors
// lab/src/cribrum_lab/runtime.py: both run the same ONNX files, so both compute the same outputs.
package io.github.bert0ns.cribrum.runtime

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtLoggingLevel
import ai.onnxruntime.OrtSession
import androidx.tracing.trace
import java.nio.FloatBuffer

/** Float values in row-major order, with their shape. */
class Tensor(val shape: LongArray, val values: FloatArray)

interface ModelRuntime : AutoCloseable {
    fun run(inputs: Map<String, Tensor>): Map<String, Tensor>

    /** Frees what the model holds. Most runtimes live as long as the process and have nothing to free. */
    override fun close() {}
}

/** ONNX Runtime on the CPU, on every core unless [threads] says otherwise. */
class OnnxRuntime(model: ByteArray, threads: Int? = null) : ModelRuntime {
    private val environment = OrtEnvironment.getEnvironment()
    private val session = OrtSession.SessionOptions().use { options ->
        options.setSessionLogLevel(OrtLoggingLevel.ORT_LOGGING_LEVEL_ERROR) // warnings about unused initializers are noise
        threads?.let(options::setIntraOpNumThreads)
        environment.createSession(model, options)
    }

    override fun run(inputs: Map<String, Tensor>): Map<String, Tensor> = trace("Onnx.run") {
        val tensors = LinkedHashMap<String, OnnxTensor>()
        try {
            for ((name, tensor) in inputs) tensors[name] = OnnxTensor.createTensor(environment, FloatBuffer.wrap(tensor.values), tensor.shape)
            session.run(tensors).use { result ->
                result.associate { (name, value) ->
                    val tensor = value as OnnxTensor
                    val buffer = tensor.floatBuffer
                    name to Tensor(tensor.info.shape, FloatArray(buffer.remaining()).also { buffer.get(it) })
                }
            }
        } finally {
            tensors.values.forEach { it.close() }
        }
    }

    /** The session holds the model in native memory, so it is closed and not left to the garbage collector. */
    override fun close() = session.close()
}
