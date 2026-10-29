package io.github.bert0ns.cribrum.pipeline

import android.content.SharedPreferences
import androidx.core.content.edit
import io.github.bert0ns.cribrum.detectors.Detector
import org.json.JSONObject
import kotlin.math.roundToLong

/** The steps of [Pipeline.run] that have a total, in the order they run. The walk has none. */
val SCAN_PHASES: List<String> = listOf("metadata", "detectors", "embeddings", "embedded_detectors") + GROUP_DETECTORS.map { it.id }

/**
 * Ms per item on a Galaxy Z Fold7, benchmark build, for a device with no history of its own. A group
 * detector's item is a library image. From the timings posted on GitHub:
 * - metadata: 2 new files read in 0.02 s (#218).
 * - detectors: no phase time was posted. About 30 ms of decode, EXIF and face preprocessing on one
 *   worker (#228, #254), plus the face model and the other detectors, on the 8 workers that keep the
 *   phase saturated (#229).
 * - embeddings: 25.4 to 27.6 s for 300 images (#229).
 * - embedded_detectors: 11.8 to 14.2 s for 300 images (#229).
 * - group detectors: 1.9 s for all three on 9,987 images (#218), split as measured on #246:
 *   0.55 to 0.66 s exact, 0.85 to 0.95 s near, 0.5 to 0.67 s burst.
 */
val DEFAULT_COSTS: Map<String, Double> = mapOf(
    "metadata" to 10.0,
    "detectors" to 20.0,
    "embeddings" to 88.0,
    "embedded_detectors" to 43.0,
    "exact_duplicate" to 0.06,
    "near_duplicate" to 0.09,
    "burst" to 0.06,
)

/**
 * The items each phase after the walk has left, from the [coverage] the index has when the walk ends:
 * one read per detector, not the phase's own filtering. A phase gets the count of the detector in it with
 * the most left, and a new embedding means the detectors that read it run again. The group detectors see
 * the whole library, and are taken to be up to date when nothing else is left.
 */
fun scanPlan(coverage: List<Coverage>): Map<String, Int> {
    val left = coverage.associate { it.id to it.needed - it.analysed }
    fun mostLeft(detectors: List<Detector>) = detectors.maxOfOrNull { left[it.id] ?: 0 } ?: 0
    val detectors = mostLeft(DETECTORS.filter { !it.usesEmbeddings })
    val embeddings = left[EMBEDDING_STEP] ?: 0
    val embedded = maxOf(embeddings, mostLeft(DETECTORS.filter { it.usesEmbeddings }))
    val library = if (detectors + embedded > 0) coverage.firstOrNull { it.id in GROUP_DETECTOR_IDS }?.needed ?: 0 else 0
    return mapOf("detectors" to detectors, "embeddings" to embeddings, "embedded_detectors" to embedded) + GROUP_DETECTORS.associate { it.id to library }
}

/** The cost per item of each phase on this device, as the last scan that finished it measured, kept with the other settings. */
class ScanCosts(private val preferences: SharedPreferences) {
    val costs: Map<String, Double>
        get() {
            val stored = preferences.getString(KEY, null)?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return DEFAULT_COSTS
            return DEFAULT_COSTS.mapValues { (phase, cost) -> stored.optDouble(phase, cost) }
        }

    fun record(measured: Map<String, Double>) {
        if (measured.isEmpty()) return
        preferences.edit { putString(KEY, JSONObject(costs + measured).toString()) }
    }

    private companion object {
        const val KEY = "scan_costs"
    }
}

/**
 * The time left for the whole scan (docs/design.md §4). Each of the [phases] has its stored [costs] in ms
 * per item. The current phase's live cost, over its last [WINDOW_MS], times its items left, plus the
 * later phases' [plan]ned items at their stored costs, scaled by the live cost over the stored one. Fed
 * every progress report, so it reads the [clock] and does arithmetic, and keeps a sample every [SAMPLE_MS].
 */
class ScanEstimate(private val phases: List<String>, private val costs: Map<String, Double>, private val clock: () -> Long) {
    private class Sample(val time: Long, val done: Int)

    private val planned = HashMap<String, Int>()
    private var current = -1
    private var done = 0
    private var total = 0

    /** The current phase's recent samples, the first one older than [WINDOW_MS] once the phase is that old. */
    private val window = ArrayDeque<Sample>()
    private var start = Sample(0, 0)

    private val phaseCosts = LinkedHashMap<String, Double>()

    /** Ms per item of each phase that reached its total, over the whole phase. */
    val measured: Map<String, Double> get() = phaseCosts

    /** The items of the phases still to come. A phase with none planned adds no time. */
    fun plan(items: Map<String, Int>) {
        planned.putAll(items)
    }

    /** A progress report of [Pipeline.run]. A step outside [phases], as the walk, is ignored. */
    fun update(step: String, done: Int, total: Int) {
        val phase = phases.indexOf(step)
        if (phase < 0) return
        val now = clock()
        if (phase != current) {
            current = phase
            window.clear()
            start = Sample(now, done)
        }
        this.done = done
        this.total = total
        if (window.isEmpty() || now - window.last().time >= SAMPLE_MS || done == total) window.addLast(Sample(now, done))
        while (window.size > 2 && now - window[1].time >= WINDOW_MS) window.removeFirst()
        if (done == total && done - start.done >= MIN_MEASURED_ITEMS && now > start.time) phaseCosts[step] = (now - start.time).toDouble() / (done - start.done)
    }

    /** Null during the walk and until the current phase has [STABLE_MS] of samples. */
    fun millisLeft(): Long? {
        if (current < 0 || window.size < 2) return null
        val first = window.first()
        val last = window.last()
        if (last.done <= first.done || last.time - first.time < STABLE_MS) return null
        val live = (last.time - first.time).toDouble() / (last.done - first.done)
        val speed = live / costs.getValue(phases[current])
        val later = phases.drop(current + 1).sumOf { (planned[it] ?: 0) * costs.getValue(it) }
        return ((total - done) * live + later * speed).roundToLong()
    }

    private companion object {
        const val WINDOW_MS = 30_000L
        const val SAMPLE_MS = 500L
        const val STABLE_MS = 5_000L
        const val MIN_MEASURED_ITEMS = 50
    }
}
