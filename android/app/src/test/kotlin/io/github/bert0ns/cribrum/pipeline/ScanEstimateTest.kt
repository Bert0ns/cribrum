package io.github.bert0ns.cribrum.pipeline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ScanEstimateTest {
    private var now = 0L
    private val estimate = ScanEstimate { now }

    /** Runs [step] from item [from] to item [to], [msPerItem] each, as the pipeline reports it. */
    private fun run(step: String, from: Int, to: Int, total: Int, msPerItem: Long) {
        for (done in from + 1..to) {
            now += msPerItem
            estimate.update(step, done, total)
        }
    }

    @Test
    fun `at a steady speed the time left is the items left at that speed`() {
        estimate.update("a", 0, 200)

        run("a", 0, 100, 200, msPerItem = 100)

        assertEquals(10_000L, estimate.millisLeft())
    }

    @Test
    fun `there is no estimate during the walk, nor before the phase has a few seconds of samples`() {
        run("scan", 0, 500, 0, msPerItem = 10)
        assertNull(estimate.millisLeft())

        estimate.update("a", 0, 200)
        run("a", 0, 40, 200, msPerItem = 100)
        assertNull(estimate.millisLeft())

        run("a", 40, 50, 200, msPerItem = 100)
        assertEquals(15_000L, estimate.millisLeft())
    }

    @Test
    fun `a group detector, which reports one item, has no estimate however long it takes`() {
        estimate.update("a", 0, 1)
        now += 20_000
        estimate.update("a", 1, 1)

        assertNull(estimate.millisLeft())
    }

    @Test
    fun `a slowdown raises the time left once it fills the window`() {
        estimate.update("a", 0, 400)
        run("a", 0, 100, 400, msPerItem = 100)
        assertEquals(30_000L, estimate.millisLeft())

        // the phone now takes twice as long, for longer than the window
        run("a", 100, 300, 400, msPerItem = 200)

        assertEquals(20_000L, estimate.millisLeft())
    }

    @Test
    fun `a new phase starts from nothing and does not carry the speed of the one before`() {
        estimate.update("a", 0, 500)
        run("a", 0, 500, 500, msPerItem = 10)
        estimate.update("b", 0, 100)
        assertNull(estimate.millisLeft())

        run("b", 0, 10, 100, msPerItem = 100)
        assertNull(estimate.millisLeft())

        // 5 s into b, which runs at 100 ms an item whatever a did
        run("b", 10, 50, 100, msPerItem = 100)
        assertEquals(5_000L, estimate.millisLeft())
    }

    @Test
    fun `items of uneven length do not make the estimate jump`() {
        estimate.update("a", 0, 1000)
        for (done in 1..800) {
            now += if (done % 2 == 0) 50 else 150
            estimate.update("a", done, 1000)
            // 100 ms an item on average, within 1%
            val expected = (1000 - done) * 100.0
            if (done >= 100) assertEquals(expected, estimate.millisLeft()!!.toDouble(), expected / 100 + 1)
        }
    }
}
