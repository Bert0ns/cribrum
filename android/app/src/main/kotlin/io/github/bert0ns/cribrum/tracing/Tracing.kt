package io.github.bert0ns.cribrum.tracing

import androidx.tracing.traceAsync
import java.util.concurrent.atomic.AtomicInteger

private val cookies = AtomicInteger()

/**
 * A trace section around code that suspends. `trace` must begin and end on one thread, and a coroutine
 * may resume on another, so this is an async section, with a cookie of its own for two that overlap.
 */
internal suspend fun <T> traceSuspend(name: String, block: suspend () -> T): T = traceAsync(name, cookies.incrementAndGet()) { block() }
