package io.github.bert0ns.cribrum.pipeline

import kotlin.math.roundToLong

/**
 * The time left of the current scan phase (docs/design.md §4): its items left times its live cost per
 * item, over the last [WINDOW_MS]. Nothing is carried from one phase to the next. Fed every progress
 * report, so it reads the [clock] and does arithmetic, and keeps a sample every [SAMPLE_MS].
 */
class ScanEstimate(private val clock: () -> Long) {
    private class Sample(val time: Long, val done: Int)

    private var step = ""
    private var done = 0
    private var total = 0

    /** The current phase's recent samples, the first one older than [WINDOW_MS] once the phase is that old. */
    private val window = ArrayDeque<Sample>()

    /** A progress report of [Pipeline.run]. A new [step] starts the estimate again. */
    fun update(step: String, done: Int, total: Int) {
        val now = clock()
        if (step != this.step) {
            this.step = step
            window.clear()
        }
        this.done = done
        this.total = total
        if (window.isEmpty() || now - window.last().time >= SAMPLE_MS || done == total) window.addLast(Sample(now, done))
        while (window.size > 2 && now - window[1].time >= WINDOW_MS) window.removeFirst()
    }

    /** Null until the current phase has [STABLE_MS] of samples, and for the walk and a group detector, which report no more than one item. */
    fun millisLeft(): Long? {
        if (total <= 1 || window.size < 2) return null
        val first = window.first()
        val last = window.last()
        if (last.done <= first.done || last.time - first.time < STABLE_MS) return null
        val live = (last.time - first.time).toDouble() / (last.done - first.done)
        return ((total - done).coerceAtLeast(0) * live).roundToLong()
    }

    private companion object {
        const val WINDOW_MS = 30_000L
        const val SAMPLE_MS = 500L
        const val STABLE_MS = 5_000L
    }
}
