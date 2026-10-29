package io.github.bert0ns.cribrum.pipeline

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ScanCostsTest {
    private val preferences = ApplicationProvider.getApplicationContext<Context>().getSharedPreferences("scan-costs-test", Context.MODE_PRIVATE)

    @Test
    fun `with no history every phase has its default cost`() {
        val costs = ScanCosts(preferences).costs

        assertEquals(SCAN_PHASES.toSet(), costs.keys)
        assertEquals(DEFAULT_COSTS, costs)
    }

    @Test
    fun `a measured cost replaces the default of its phase only, and a new instance reads it back`() {
        ScanCosts(preferences).record(mapOf("embeddings" to 42.5))
        ScanCosts(preferences).record(mapOf("metadata" to 1.5))

        assertEquals(DEFAULT_COSTS + mapOf("embeddings" to 42.5, "metadata" to 1.5), ScanCosts(preferences).costs)
    }
}
