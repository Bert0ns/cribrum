package io.github.bert0ns.cribrum.ui

import io.github.bert0ns.cribrum.pipeline.CLASSIFY_STEP
import io.github.bert0ns.cribrum.pipeline.Indexing

/**
 * When the home reads the index again during a scan (docs/design.md §4). Each read takes the whole index and a
 * ranking, so it happens only once a phase that can add candidates is over, and when the scan ends:
 * - the walk (phase 1): a trashed image put back by hand becomes unreviewed with its signals, and the first scan
 *   ends the empty index;
 * - the detectors (phase 3) and the detectors that read the embeddings, which run with the embeddings (phase 4),
 *   store signals;
 * - the group detectors (phase 5) store signals too, and are the last steps, so the end of the scan covers them.
 *
 * The metadata and JPEG headers (phase 2) store no signal or review state.
 */
internal class HomeReloads {
    private var first = true
    private var step: String? = null

    /** Whether the home reloads now that the scan reports [step], or null once no scan runs. */
    fun next(step: String?): Boolean {
        val from = this.step
        this.step = step
        if (first) {
            first = false
            return true
        }
        if (step == from) return false
        if (step == null) return true
        if (from == null) return false
        val before = Indexing.phaseNumber(from)
        val after = Indexing.phaseNumber(step)
        // A short phase can pass unseen, so any candidate phase between the two counts; a lower phase is a new scan.
        return after < before || CANDIDATE_PHASES.any { it in before until after }
    }

    private companion object {
        val CANDIDATE_PHASES = listOf("scan", "detectors", CLASSIFY_STEP).map(Indexing::phaseNumber).toSet()
    }
}
