package io.github.bert0ns.cribrum.pipeline

import android.content.SharedPreferences
import android.os.SystemClock
import android.util.Log
import androidx.core.content.edit
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** A file that failed at [step], by name only: the settings screen shows no paths. */
data class FailedFile(val step: String, val name: String)

/**
 * What the last finished scan left, for the settings screen (docs/design.md §8). [failures] counts by step.
 * [durationMs] is null and [phaseMs] empty in a report saved before the times were kept.
 */
data class ScanReport(
    val finishedAt: Long,
    val unreadable: Int,
    val failures: Map<String, Int>,
    val files: List<FailedFile>,
    val unreadableFiles: List<String> = emptyList(),
    val durationMs: Long? = null,
    val phaseMs: Map<String, Long> = emptyMap(),
)

/** The report of the last scan that finished, kept with the other settings. A scan that stops leaves the previous report. */
class LastScan(private val preferences: SharedPreferences) {
    val report: ScanReport?
        get() = preferences.getString(KEY, null)?.let { runCatching { parse(JSONObject(it)) }.getOrNull() }

    val failed: Boolean
        get() = preferences.getBoolean(KEY_FAILED, false)

    fun recordFailure() {
        preferences.edit { putBoolean(KEY_FAILED, true) }
    }

    /**
     * Runs [scan] and keeps its report, with how long it took on the monotonic [elapsed] clock, and logs the
     * times and the image count under [TIME_TAG]. A scan that throws or is cancelled leaves the previous one.
     */
    suspend fun run(now: () -> Long = System::currentTimeMillis, elapsed: () -> Long = SystemClock::elapsedRealtime, scan: suspend () -> Stats): Stats {
        val start = elapsed()
        val stats = scan()
        val durationMs = elapsed() - start
        record(stats, now(), durationMs)
        Log.i(TIME_TAG, "images ${stats.images}")
        stats.phaseMs.forEach { (phase, ms) -> Log.i(TIME_TAG, "$phase $ms ms") }
        Log.i(TIME_TAG, "total $durationMs ms")
        return stats
    }

    fun record(stats: Stats, finishedAt: Long, durationMs: Long? = null) {
        val json = JSONObject()
            .put("finished_at", finishedAt)
            .put("unreadable", stats.unreadable)
            .put("failures", JSONObject(stats.failures.groupingBy { it.step }.eachCount()))
            .put("files", JSONArray(stats.failures.take(MAX_FILES).map { JSONArray(listOf(it.step, File(it.path).name)) }))
            .put("unreadable_files", JSONArray(stats.unreadablePaths.take(MAX_FILES).map { File(it).name }))
            .put("phase_ms", JSONObject(stats.phaseMs))
        if (durationMs != null) json.put("duration_ms", durationMs)
        val hasFailed = stats.failures.any { it.path.isEmpty() || it.step == "trash" || it.step in GROUP_DETECTOR_IDS }
        preferences.edit {
            putString(KEY, json.toString())
            putBoolean(KEY_FAILED, hasFailed)
        }
    }

    private fun parse(json: JSONObject): ScanReport {
        val failures = json.getJSONObject("failures")
        val files = json.getJSONArray("files")
        val unreadableFiles = json.optJSONArray("unreadable_files") // absent in a report saved before the names were kept
        val phases = json.optJSONObject("phase_ms") // absent, with duration_ms, in a report saved before the times were kept
        return ScanReport(
            json.getLong("finished_at"),
            json.getInt("unreadable"),
            failures.keys().asSequence().associateWith(failures::getInt),
            List(files.length()) { files.getJSONArray(it).let { file -> FailedFile(file.getString(0), file.getString(1)) } },
            unreadableFiles?.let { a -> List(a.length()) { a.getString(it) } }.orEmpty(),
            if (json.has("duration_ms")) json.getLong("duration_ms") else null,
            phases?.let { p -> p.keys().asSequence().associateWith(p::getLong) }.orEmpty(),
        )
    }

    companion object {
        /** More names than this would not be read, and the report lives in the settings file. */
        const val MAX_FILES = 100
        private const val KEY = "last_scan"
        private const val KEY_FAILED = "last_scan_failed"

        /** The log tag of the scan times, for `adb logcat -s`. */
        const val TIME_TAG = "scan-time"
    }
}
