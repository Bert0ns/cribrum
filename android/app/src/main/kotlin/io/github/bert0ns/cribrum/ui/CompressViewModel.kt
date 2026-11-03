// The state of the Compress page (docs/design.md §8): the photos to pick, the level, the summary and its preview.
package io.github.bert0ns.cribrum.ui

import android.app.Application
import android.os.Build
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.bert0ns.cribrum.CribrumApp
import io.github.bert0ns.cribrum.compress.BatchResult
import io.github.bert0ns.cribrum.compress.BatchState
import io.github.bert0ns.cribrum.compress.CompressibleImage
import io.github.bert0ns.cribrum.compress.CompressionLevel
import io.github.bert0ns.cribrum.compress.Compressor
import io.github.bert0ns.cribrum.compress.Refusal
import io.github.bert0ns.cribrum.sources.favoritePaths
import io.github.bert0ns.cribrum.trash.copyFileFor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private const val TAG = "compress"

/** How many photos a page of the list shows. */
const val COMPRESS_PAGE_SIZE = 50

/** The photos of page [page], counted from 0. */
private fun List<CompressibleImage>.pageOf(page: Int) = drop(page * COMPRESS_PAGE_SIZE).take(COMPRESS_PAGE_SIZE)

/** How many pages [size] photos take, at least one. */
fun compressPageCount(size: Int) = maxOf(1, (size + COMPRESS_PAGE_SIZE - 1) / COMPRESS_PAGE_SIZE)

/** How many of the selected photos lose each thing the copy loses (§9). */
data class LossCounts(val reEdit: Int = 0, val depthMap: Int = 0, val xmp: Int = 0, val c2pa: Int = 0)

/** How many photos the summary compresses for real. */
const val MAX_COMPARED = 3

/** A photo compressed for real at the chosen level, for the before and after. */
sealed interface SampleResult {
    data object Loading : SampleResult

    /** The compressed copy is in [file], with the EXIF of the original. */
    data class Ready(val file: File) : SampleResult

    data class Failed(val reason: Refusal) : SampleResult
}

/** One of the photos the summary compares, and its compressed copy. */
data class Sample(val photo: CompressibleImage, val preview: SampleResult)

/** The summary before confirming, with the photos it compares, largest first. */
data class Summary(val level: CompressionLevel, val samples: List<Sample>)

data class CompressState(
    val loaded: Boolean = false,
    /** Largest first, as the index gives them. */
    val photos: List<CompressibleImage> = emptyList(),
    /** The photos that carry the marker: compressed once already. */
    val compressed: List<CompressibleImage> = emptyList(),
    val favorites: Set<String> = emptySet(),
    val selected: Set<String> = emptySet(),
    /** Balanced until the user moves the slider (§8). Photos, unlike the level, are never preselected. */
    val level: CompressionLevel = CompressionLevel.BALANCED,
    val compressedExpanded: Boolean = false,
    /** The page of [photos] and of [compressed] on screen, counted from 0. */
    val page: Int = 0,
    val compressedPage: Int = 0,
    val summary: Summary? = null,
    /** The result of a batch the user has read and closed. */
    val dismissed: BatchResult? = null,
    /** Originals the last Undo could not put back, because a file other than the copy took their path. */
    val stayedInTrash: Int = 0,
    /** An undo is on: it may be waiting for a scan or a batch to end. */
    val undoing: Boolean = false,
    val retentionDays: Int = 30,
) {
    val pagePhotos: List<CompressibleImage> get() = photos.pageOf(page)
    val pageCompressed: List<CompressibleImage> get() = compressed.pageOf(compressedPage)

    /** Largest first, as the batch runs them, whichever section a photo is in. */
    val selectedPhotos: List<CompressibleImage> get() = (photos + compressed).filter { it.path in selected }.sortedWith(compareByDescending<CompressibleImage> { it.size }.thenBy { it.path })

    /** The photos the summary compares: all of them up to [MAX_COMPARED], else the largest, the middle one by size and the smallest. */
    val comparedPhotos: List<CompressibleImage>
        get() = selectedPhotos.let { if (it.size <= MAX_COMPARED) it else listOf(it.first(), it[it.size / 2], it.last()) }

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
    /** One for each photo compared, named like a compressed copy so only the trash may delete them. */
    private val previewFiles: List<File> = List(MAX_COMPARED) { copyFileFor(File(application.cacheDir, "preview")) },
    /** Deletes a preview file, through the trash. */
    private val discard: (File) -> Unit = { (application as CribrumApp).trash.discardCopy(it) },
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
                        page = state.page.coerceAtMost(compressPageCount(todo.size) - 1),
                        compressedPage = state.compressedPage.coerceAtMost(compressPageCount(done.size) - 1),
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

    /** Selects or clears the photos of the current page. The other pages keep their selection, and the already compressed ones are only picked one by one. */
    fun selectAll(select: Boolean) = mutableState.update {
        val shown = it.pagePhotos.map { photo -> photo.path }
        it.copy(selected = if (select) it.selected + shown else it.selected - shown.toSet())
    }

    fun goToPage(page: Int) = mutableState.update { it.copy(page = page.coerceIn(0, compressPageCount(it.photos.size) - 1)) }

    fun goToCompressedPage(page: Int) = mutableState.update { it.copy(compressedPage = page.coerceIn(0, compressPageCount(it.compressed.size) - 1)) }

    fun chooseLevel(level: CompressionLevel) = mutableState.update { it.copy(level = level) }

    fun toggleCompressed() = mutableState.update { it.copy(compressedExpanded = !it.compressedExpanded) }

    /** Opens the summary, and compresses the photos it compares at the chosen level, one after the other. */
    fun openSummary() {
        val state = mutableState.value
        val level = state.level
        val photos = state.comparedPhotos
        if (photos.isEmpty()) return
        // A preview is a second full decode: it never runs next to a batch's.
        if (batches.state.value.let { it is BatchState.WaitingForScan || it is BatchState.Running }) return
        previewJob?.cancel()
        mutableState.update { it.copy(summary = Summary(level, photos.map { photo -> Sample(photo, SampleResult.Loading) })) }
        previewJob = viewModelScope.launch {
            // One at a time: each is a full decode and encode of a photo, and the first shows while the others are made.
            photos.forEachIndexed { index, photo ->
                val refusal = makePreview(File(photo.path), level, previewFiles[index])
                val result = if (refusal == null) SampleResult.Ready(previewFiles[index]) else SampleResult.Failed(refusal)
                // The summary may have been closed meanwhile.
                mutableState.update { state -> state.copy(summary = state.summary?.let { it.copy(samples = it.samples.mapIndexed { i, sample -> if (i == index) sample.copy(preview = result) else sample }) }) }
            }
        }
    }

    fun closeSummary() {
        dropPreview()
        mutableState.update { it.copy(summary = null) }
    }

    private fun dropPreview() {
        previewJob?.cancel()
        previewFiles.forEach { file -> runCatching { discard(file) }.onFailure { Log.w(TAG, "Could not delete the preview", it) } }
    }

    override fun onCleared() = dropPreview()

    /** Starts the batch with the selection, largest first. */
    fun confirm() {
        val state = mutableState.value
        val level = state.level
        val paths = state.selectedPhotos.map { it.path }
        if (paths.isEmpty()) return
        dropPreview()
        // False when a batch is already on: the page shows it, and the selection stays.
        val started = batches.start(paths, level)
        mutableState.update { if (started) it.copy(selected = emptySet(), summary = null, dismissed = null, stayedInTrash = 0) else it.copy(summary = null) }
    }

    fun dismissResult(result: BatchResult) = mutableState.update { it.copy(dismissed = result) }

    /**
     * Puts the originals of a finished batch back. The undo waits for a scan or a batch that runs, which can take
     * minutes, so [CompressState.undoing] says so meanwhile. The state goes to Idle, and the list is read again.
     */
    fun undo(result: BatchResult) {
        if (mutableState.value.undoing) return
        mutableState.update { it.copy(undoing = true) }
        viewModelScope.launch {
            try {
                // Not cancellable: a restore stopped halfway would leave originals in the trash.
                val stayed = withContext(NonCancellable) { batches.undo(result) }
                mutableState.update { it.copy(stayedInTrash = stayed.size) }
            } catch (e: Exception) {
                Log.w(TAG, "Could not undo the batch", e)
            } finally {
                mutableState.update { it.copy(undoing = false) }
            }
            refresh()
        }
    }
}
