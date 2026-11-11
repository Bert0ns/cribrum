package io.github.bert0ns.cribrum.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeReloadsTest {
    /** The steps after which the home reloads, with "end" for the scan ending, once [steps] have been reported. */
    private fun reloadsAfter(vararg steps: String): List<String> {
        val reloads = HomeReloads()
        reloads.next(null) // the app opens with no scan
        var previous = "start"
        return buildList {
            for (step in steps) {
                if (reloads.next(step)) add(previous)
                previous = step
            }
            if (reloads.next(null)) add("end")
        }
    }

    @Test
    fun `the home loads once when it first comes up`() {
        val reloads = HomeReloads()
        assertTrue(reloads.next(null))
        assertFalse(reloads.next(null))
    }

    @Test
    fun `a full scan reloads after the walk, the detectors, the classification and at the end`() {
        val steps = arrayOf("", "scan", "metadata", "jpeg_headers", "detectors", "embedded_detectors", "exact_duplicate", "near_duplicate", "burst")

        assertEquals(listOf("scan", "detectors", "embedded_detectors", "end"), reloadsAfter(*steps))
    }

    @Test
    fun `a scan that skips phases still reloads after each phase that can add candidates`() {
        // A rescan with no new file goes from the walk to the group detectors.
        assertEquals(listOf("scan", "end"), reloadsAfter("scan", "exact_duplicate"))
        // Every image classified already: no classification phase.
        assertEquals(listOf("scan", "detectors", "end"), reloadsAfter("scan", "metadata", "detectors", "burst"))
    }

    @Test
    fun `a candidate phase the home never saw still reloads it`() {
        // The detectors ran too quickly for their step to reach the screen.
        assertEquals(listOf("scan", "jpeg_headers", "end"), reloadsAfter("scan", "jpeg_headers", "embedded_detectors"))
    }

    @Test
    fun `a new scan seen without the end of the last one reloads`() {
        val reloads = HomeReloads()
        reloads.next(null)
        reloads.next("burst")

        assertTrue(reloads.next("scan"))
    }

    @Test
    fun `the start of a scan and progress inside a step do not reload`() {
        val reloads = HomeReloads()
        reloads.next(null)

        assertFalse(reloads.next(""))
        assertFalse(reloads.next("scan"))
        assertFalse(reloads.next("scan"))
    }
}
