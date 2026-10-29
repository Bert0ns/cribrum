package io.github.bert0ns.cribrum.pipeline

import org.junit.Assert.assertEquals
import org.junit.Test

class ScanPlanTest {
    /** Coverage rows of 200 readable images, 100 of them embeddable, in a library of 250, with [left] items left per id. */
    private fun coverage(left: Map<String, Int>): List<Coverage> {
        fun row(id: String, needed: Int) = Coverage(id, 1, needed - (left[id] ?: 0), needed)
        return listOf(row(EMBEDDING_STEP, 100)) +
            DETECTORS.map { row(it.id, if (it.usesEmbeddings) 100 else 200) } +
            GROUP_DETECTORS.map { row(it.id, 250) }
    }

    @Test
    fun `each phase gets the images its slowest detector has left, and the group detectors the whole library`() {
        val plan = scanPlan(coverage(mapOf("blur" to 50, "exposure" to 20, EMBEDDING_STEP to 60, "document" to 80, "forwarded" to 30)))

        assertEquals(
            mapOf("detectors" to 50, "embeddings" to 60, "embedded_detectors" to 80, "exact_duplicate" to 250, "near_duplicate" to 250, "burst" to 250),
            plan,
        )
    }

    @Test
    fun `a new embedding means the detectors that read it run again`() {
        val plan = scanPlan(coverage(mapOf(EMBEDDING_STEP to 60)))

        assertEquals(60, plan["embedded_detectors"])
    }

    @Test
    fun `with nothing left the group detectors are taken to be up to date`() {
        val plan = scanPlan(coverage(emptyMap()))

        assertEquals(SCAN_PHASES.drop(1).associateWith { 0 }, plan)
    }
}
