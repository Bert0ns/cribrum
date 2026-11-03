// The state of the Compress page (docs/design.md §8): the photos to pick, the level, the summary and its preview.
package io.github.bert0ns.cribrum.ui

import android.app.Application
import android.os.Build
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.bert0ns.cribrum.CribrumApp
import io.github.bert0ns.cribrum.compress.BatchResult
import io.github.bert0ns.cribrum.compress.CompressibleImage
import io.github.bert0ns.cribrum.compress.CompressionLevel
import io.github.bert0ns.cribrum.compress.Compressor
import io.github.bert0ns.cribrum.compress.Refusal
import io.github.bert0ns.cribrum.sources.favoritePaths
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private const val TAG = "compress"

/** How many of the selected photos lose each thing the copy loses (§9). */
data class LossCounts(val reEdit: Int = 0, val depthMap: Int = 0, val xmp: Int = 0, val c2pa: Int = 0)

/** A photo compressed for real at the chosen level, for the before and after. */
sealed interface SampleResult {
    data object Loading : SampleResult

    /** The compressed copy is in [file], with the EXIF of the original. */
    data class Ready(val file: File) : SampleResult

    data class Failed(val reason: Refusal) : SampleResult
}

/** The summary before confirming: [sample] is the photo the preview shows. */
data class Summary(val sample: CompressibleImage, val level: CompressionLevel, val preview: SampleResult)

data class CompressState(
    val loaded: Boolean = false,
    /** Largest first, as the index gives them. */
    val photos: List<CompressibleImage> = emptyList(),
    /** The photos that carry the marker: compressed once already. */
    val compressed: List<CompressibleImage> = emptyList(),
    val favorites: Set<String> = emptySet(),
    val selected: Set<String> = emptySet(),
    /** Not preselected: the user picks it. */
    val level: CompressionLevel? = null,
    val compressedExpanded: Boolean = false,
    val summary: Summary? = null,
    /** The result of a batch the user has read and closed. */
    val dismissed: BatchResult? = null,
    /** Originals the last Undo could not put back, because a file other than the copy took their path. */
    val stayedInTrash: Int = 0,
    val retentionDays: Int = 30,
) {
    val selectedPhotos: List<CompressibleImage> get() = (photos + compressed).filter { it.path in selected }

    fun estimatedSaving(level: CompressionLevel): Long = selectedPhotos.sumOf { it.saving(level) }

    fun lossCounts(): LossCounts {
        val losses = selectedPhotos.map { it.info.losses }
        return LossCounts(losses.count { it.reEdit }, losses.count { it.depthMap }, losses.count { it.xmp }, losses.count { it.c2pa })
    }
}

class CompressViewModel @JvmOverloads constructor(
    application: Application,
    val batches: CompressBatches = DeviceBatches(application),
    private val photos: suspend () -> List<CompressibleImage> = { (application as CribrumApp).index.compressible(Build.VERSION.SDK_INT) },
    private val favorites: suspend () -> Set<String> = { favoritePaths(application.contentResolver) },
    private val retentionDays: () -> Int = { (application as CribrumApp).trashRetention.days },
    /** Compresses the first file at the level into the third, like a real compression, and says why it could not. */
    preview: (suspend (File, CompressionLevel, File) -> Refusal?)? = null,
    private val previewFile: File = File(application.cacheDir, "compress-preview.jpg"),
) : AndroidViewModel(application) {
    private val mutableState = MutableStateFlow(CompressState())
    val state: StateFlow<CompressState> = mutableState.asStateFlow()
    private var previewJob: Job? = null
    private val makePreview = preview ?: (application as CribrumApp).let { Compressor.forDevice(it, it.trash, it.index)::preview }

    /** Reads the list again: after a scan, a batch, or when the page opens. A selected photo that left the list is dropped. */
    fun refresh() {
        viewModelScope.launch {
            try {
                val all = photos()
                val favorite = favorites()
                val days = withContext(Dispatchers.IO) { retentionDays() }
                val (done, todo) = all.partition { it.info.marker != null }
                mutableState.update { state ->
                    val selected = state.selected intersect all.mapTo(HashSet()) { it.path }
                    state.copy(
                        loaded = true,
                        photos = todo,
                        compressed = done,
                        favorites = favorite,
                        selected = selected,
                        retentionDays = days,
                        summary = state.summary.takeIf { selected == state.selected },
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Could not read the photos", e)
            }
        }
    }

    fun toggle(path: String) = mutableState.update { it.copy(selected = if (path in it.selected) it.selected - path else it.selected + path) }

    /** Selects or clears the photos not compressed yet. The already compressed ones are only picked one by one. */
    fun selectAll(select: Boolean) = mutableState.update {
        val others = it.selected - it.photos.mapTo(HashSet()) { photo -> photo.path }
        it.copy(selected = if (select) others + it.photos.map { photo -> photo.path } else others)
    }

    fun chooseLevel(level: CompressionLevel) = mutableState.update { it.copy(level = level) }

    fun toggleCompressed() = mutableState.update { it.copy(compressedExpanded = !it.compressedExpanded) }

    /** Opens the summary, and compresses the largest selected photo at the chosen level for its before and after. */
    fun openSummary() {
        val state = mutableState.value
        val level = state.level ?: return
        val sample = state.selectedPhotos.firstOrNull() ?: return
        previewJob?.cancel()
        mutableState.update { it.copy(summary = Summary(sample, level, SampleResult.Loading)) }
        previewJob = viewModelScope.launch {
            val refusal = makePreview(File(sample.path), level, previewFile)
            val result = if (refusal == null) SampleResult.Ready(previewFile) else SampleResult.Failed(refusal)
            // The summary may have been closed meanwhile.
            mutableState.update { state -> state.copy(summary = state.summary?.copy(preview = result)) }
        }
    }

    fun closeSummary() {
        previewJob?.cancel()
        mutableState.update { it.copy(summary = null) }
    }

    /** Starts the batch with the selection, largest first. */
    fun confirm() {
        val state = mutableState.value
        val level = state.level ?: return
        val paths = state.selectedPhotos.map { it.path }
        if (paths.isEmpty()) return
        previewJob?.cancel()
        // False when a batch is already on: the page shows it, and the selection stays.
        val started = batches.start(paths, level)
        mutableState.update { if (started) it.copy(selected = emptySet(), level = null, summary = null, dismissed = null, stayedInTrash = 0) else it.copy(summary = null) }
    }

    fun dismissResult(result: BatchResult) = mutableState.update { it.copy(dismissed = result) }

    /** Puts the originals of a finished batch back. The state goes to Idle, and the list is read again. */
    fun undo(result: BatchResult) {
        viewModelScope.launch {
            val stayed = batches.undo(result)
            mutableState.update { it.copy(stayedInTrash = stayed.size) }
            refresh()
        }
    }
}
