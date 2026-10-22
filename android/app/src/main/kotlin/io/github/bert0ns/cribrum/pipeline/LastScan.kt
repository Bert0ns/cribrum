package io.github.bert0ns.cribrum.pipeline

import android.content.SharedPreferences
import androidx.core.content.edit
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** A file that failed at [step], by name only: the settings screen shows no paths. */
data class FailedFile(val step: String, val name: String)

/** What the last finished scan left, for the settings screen (docs/design.md §8). [failures] counts by step. */
data class ScanReport(val finishedAt: Long, val unreadable: Int, val failures: Map<String, Int>, val files: List<FailedFile>)

/** The report of the last scan that finished, kept with the other settings. A scan that stops leaves the previous report. */
class LastScan(private val preferences: SharedPreferences) {
    val report: ScanReport?
        get() = preferences.getString(KEY, null)?.let { runCatching { parse(JSONObject(it)) }.getOrNull() }

    fun record(stats: Stats, finishedAt: Long) {
        val json = JSONObject()
            .put("finished_at", finishedAt)
            .put("unreadable", stats.unreadable)
            .put("failures", JSONObject(stats.failures.groupingBy { it.step }.eachCount()))
            .put("files", JSONArray(stats.failures.take(MAX_FILES).map { JSONArray(listOf(it.step, File(it.path).name)) }))
        preferences.edit { putString(KEY, json.toString()) }
    }

    private fun parse(json: JSONObject): ScanReport {
        val failures = json.getJSONObject("failures")
        val files = json.getJSONArray("files")
        return ScanReport(
            json.getLong("finished_at"),
            json.getInt("unreadable"),
            failures.keys().asSequence().associateWith(failures::getInt),
            List(files.length()) { files.getJSONArray(it).let { file -> FailedFile(file.getString(0), file.getString(1)) } },
        )
    }

    companion object {
        /** More names than this would not be read, and the report lives in the settings file. */
        const val MAX_FILES = 100
        private const val KEY = "last_scan"
    }
}
