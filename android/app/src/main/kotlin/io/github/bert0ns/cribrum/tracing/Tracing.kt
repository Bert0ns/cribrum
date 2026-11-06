package io.github.bert0ns.cribrum.tracing

import androidx.tracing.Trace
import androidx.tracing.traceAsync
import coil3.request.ErrorResult
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger

private val cookies = AtomicInteger()

/**
 * A trace section around code that suspends. `trace` must begin and end on one thread, and a coroutine
 * may resume on another, so this is an async section, with a cookie of its own for two that overlap.
 */
internal suspend fun <T> traceSuspend(name: String, block: suspend () -> T): T = traceAsync(name, cookies.incrementAndGet()) { block() }

/**
 * Makes each load of the request an async section called [name], from the start of the request to its success, error
 * or cancel. The listener changes nothing the request does. A request that starts again before the last one ended
 * ends the oldest open section first, so every section that begins ends.
 */
internal fun ImageRequest.Builder.traced(name: String): ImageRequest.Builder = listener(LoadSection(name))

private class LoadSection(private val name: String) : ImageRequest.Listener {
    private val open = ConcurrentLinkedQueue<Int>()

    override fun onStart(request: ImageRequest) {
        val cookie = cookies.incrementAndGet()
        open.add(cookie)
        Trace.beginAsyncSection(name, cookie)
    }

    private fun end() {
        open.poll()?.let { Trace.endAsyncSection(name, it) }
    }

    override fun onCancel(request: ImageRequest) = end()

    override fun onError(request: ImageRequest, result: ErrorResult) = end()

    override fun onSuccess(request: ImageRequest, result: SuccessResult) = end()
}
