package io.github.bert0ns.cribrum.pipeline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ScanEstimateTest {
    private var now = 0L

    /** Stored costs in ms per item: `a` runs first, then `b`, then `c`. */
    private val costs = mapOf("a" to 100.0, "b" to 200.0, "c" to 50.0)
    private val estimate = ScanEstimate(listOf("a", "b", "c"), costs) { now }

    /** Runs [step] from item [from] to item [to], [msPerItem] each, as the pipeline reports it. */
    private fun run(step: String, from: Int, to: Int, total: Int, msPerItem: Long) {
        for (done in from + 1..to) {
            now += msPerItem
            estimate.update(step, done, total)
        }
    }

    @Test
    fun `at a steady speed the time left is the items left at the stored costs`() {
        estimate.plan(mapOf("b" to 100, "c" to 100))
        estimate.update("a", 0, 200)

        run("a", 0, 100, 200, msPerItem = 100)

        // 100 items of a at 100 ms, 100 of b at 200 ms, 100 of c at 50 ms
        assertEquals(35_000L, estimate.millisLeft())
    }

    @Test
    fun `there is no estimate during the walk, nor before the current phase has a few seconds of samples`() {
        estimate.plan(mapOf("b" to 100, "c" to 100))
        run("scan", 0, 500, 0, msPerItem = 10)
        assertNull(estimate.millisLeft())

        estimate.update("a", 0, 200)
        run("a", 0, 40, 200, msPerItem = 100)
        assertNull(estimate.millisLeft())

        run("a", 40, 50, 200, msPerItem = 100)
        assertEquals(40_000L, estimate.millisLeft())
    }

    @Test
    fun `a slowdown halfway through raises the time left of the later phases too`() {
        estimate.plan(mapOf("b" to 100, "c" to 100))
        estimate.update("a", 0, 400)
        run("a", 0, 100, 400, msPerItem = 100)

        // the phone now takes twice as long, for longer than the window
        run("a", 100, 300, 400, msPerItem = 200)

        // 100 items of a at 200 ms, then b and c at twice their stored costs: (20,000 + 5,000) × 2
        assertEquals(70_000L, estimate.millisLeft())
    }

    @Test
    fun `a phase with nothing to do adds no time`() {
        estimate.plan(mapOf("b" to 0, "c" to 100))
        estimate.update("a", 0, 200)
        run("a", 0, 100, 200, msPerItem = 100)
        // 100 items of a at 100 ms and 100 of c at 50 ms
        assertEquals(15_000L, estimate.millisLeft())

        run("a", 100, 200, 200, msPerItem = 100)
        estimate.update("b", 0, 0)
        assertNull(estimate.millisLeft())

        estimate.update("c", 0, 100)
        run("c", 0, 60, 100, msPerItem = 100)
        // c runs at twice its stored cost: 40 items at 100 ms
        assertEquals(4_000L, estimate.millisLeft())
    }

    @Test
    fun `a stopped scan keeps the costs of the phases it finished`() {
        estimate.update("a", 0, 200)
        run("a", 0, 200, 200, msPerItem = 120)
        estimate.update("b", 0, 100)
        run("b", 0, 50, 100, msPerItem = 300)

        assertEquals(mapOf("a" to 120.0), estimate.measured)
    }

    @Test
    fun `items of uneven length do not make the estimate jump`() {
        estimate.plan(mapOf("b" to 100, "c" to 100))
        estimate.update("a", 0, 1000)
        for (done in 1..800) {
            now += if (done % 2 == 0) 50 else 150
            estimate.update("a", done, 1000)
            // 100 ms an item on average: the items left of a, then 25,000 ms of b and c, within 1%
            val expected = (1000 - done) * 100.0 + 25_000
            if (done >= 100) assertEquals(expected, estimate.millisLeft()!!.toDouble(), expected / 100)
        }
    }
}
