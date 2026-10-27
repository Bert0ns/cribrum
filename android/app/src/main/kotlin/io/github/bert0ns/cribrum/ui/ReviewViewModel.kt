package io.github.bert0ns.cribrum.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.bert0ns.cribrum.CribrumApp
import io.github.bert0ns.cribrum.model.Category
import io.github.bert0ns.cribrum.model.ImageRef
import io.github.bert0ns.cribrum.pipeline.Coverage
import io.github.bert0ns.cribrum.pipeline.ScanReport
import io.github.bert0ns.cribrum.pipeline.coverage
import io.github.bert0ns.cribrum.ranker.THRESHOLDS
import io.github.bert0ns.cribrum.review.CategorySummary
import io.github.bert0ns.cribrum.review.Decision
import io.github.bert0ns.cribrum.review.Overview
import io.github.bert0ns.cribrum.review.Page
import io.github.bert0ns.cribrum.review.Review
import io.github.bert0ns.cribrum.review.ReviewGroup
import io.github.bert0ns.cribrum.review.ReviewOrder
import io.github.bert0ns.cribrum.trash.TrashRetention
import io.github.bert0ns.cribrum.trash.TrashedImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.Duration
import java.time.temporal.WeekFields

sealed interface Screen {
    data object Home : Screen
    data class Category(val category: io.github.bert0ns.cribrum.model.Category) : Screen
    data object Trash : Screen
    data object Settings : Screen
    data object StorageMap : Screen
    data object Thresholds : Screen
}

/** A lower retention that would purge [expiring] trashed images at the next scan, waiting for the user's confirmation. */
data class RetentionChange(val days: Int, val expiring: Int)

data class ReviewState(
    val screen: Screen = Screen.Home,
    val categories: List<CategorySummary> = emptyList(),
    /** What all the candidates can free, each image counted once. */
    val freeableBytes: Long = 0,
    /** Nothing is indexed yet, so the home screen points to "Scan now". */
    val indexEmpty: Boolean = false,
    val page: List<ReviewGroup> = emptyList(),
    /** Candidates the open category has left, the page included. */
    val remaining: Int = 0,
    /** Paths marked for the trash on the current page. */
    val marked: Set<String> = emptySet(),
    val trashed: List<TrashedImage> = emptyList(),
    /** Paths selected on the trash screen. */
    val selected: Set<String> = emptySet(),
    /** The last applied page, until the user undoes it or moves on. */
    val lastDecision: Decision? = null,
    /** Ids of the groups skipped in the open category. They stay unreviewed and return when it is reopened. */
    val skipped: Set<String> = emptySet(),
    val loading: Boolean = false,
    val storageMap: List<Folder> = emptyList(),
    val retentionDays: Int = TrashRetention.DEFAULT_DAYS,
    val pendingRetention: RetentionChange? = null,
    val hapticsEnabled: Boolean = true,
    /** How the open category is grouped by date or sorted by size. Off until the user picks one. */
    val reviewOrder: ReviewOrder = ReviewOrder.OFF,
    /** Folders the user asked never to be suggested again, shown in settings. */
    val excludedFolders: List<String> = emptyList(),
    /** Embeddings stored in the index, shown in settings. */
    val embeddingCount: Int = 0,
    /** What Cribrum takes on the phone, shown in settings. */
    val storage: StorageUse? = null,
    /** What each detector analysed out of the images that need it, and the last finished scan, shown in settings. */
    val coverage: List<Coverage>? = null,
    val lastScan: ScanReport? = null,
    val lastScanFailed: Boolean = false,
    /** The thresholds in use, and how many candidates each category lists at them and at the defaults. */
    val thresholds: Map<Category, Float> = THRESHOLDS,
    val thresholdCounts: Map<Category, Int> = emptyMap(),
    val defaultCounts: Map<Category, Int> = emptyMap(),
    /** The folder the user just excluded, until they undo it or move on. */
    val lastExclusion: String? = null,
)

class ReviewViewModel @JvmOverloads constructor(
    application: Application,
    private val review: Review = (application as CribrumApp).let {
        Review(
            it.index,
            it.trash,
            thresholds = { it.thresholds.values },
            // The locale the screen formats its date labels with (LocalConfiguration), so a week and its label agree.
            firstDayOfWeek = { WeekFields.of(it.resources.configuration.locales[0]).firstDayOfWeek },
        )
    },
) : AndroidViewModel(application) {
    private val app = application as CribrumApp
    private val mutableState = MutableStateFlow(ReviewState())
    private val changes = Mutex()
    val state: StateFlow<ReviewState> = mutableState.asStateFlow()

    init {
        // The first read of the preferences can block, so it stays off the main thread.
        load {
            withContext(Dispatchers.IO) { it.copy(retentionDays = app.trashRetention.days, hapticsEnabled = app.haptics.enabled) }
        }
    }

    fun refresh() = load { it.copy(indexEmpty = app.index.imageCount() == 0).withOverview(review.overview()) }

    fun open(category: Category) = load {
        val grouping = app.reviewOrder[category]
        it.copy(screen = Screen.Category(category), lastDecision = null, lastExclusion = null, skipped = emptySet(), reviewOrder = grouping)
            .showing(pageFor(category, it, skipped = emptySet(), grouping = grouping))
    }

    /** Remembered for the category, and applied to the page at once. The user's marks on images that stay on the page stay. */
    fun setReviewOrder(grouping: ReviewOrder) = load { state ->
        val category = (state.screen as? Screen.Category)?.category ?: return@load state
        if (grouping !in ReviewOrder.optionsFor(category)) return@load state
        app.reviewOrder[category] = grouping
        state.copy(reviewOrder = grouping).showing(pageFor(category, state, grouping = grouping), keepMarksOf = state)
    }

    fun openTrash() = load { it.copy(screen = Screen.Trash, trashed = review.trashed(), selected = emptySet()) }

    /** Only the embeddings go: the next scan computes them again. */
    fun deleteEmbeddings() {
        val deleted = load { state ->
            app.index.deleteEmbeddings()
            state.copy(embeddingCount = app.index.embeddingCount())
        }
        refreshStorage(after = deleted)
    }

    /** Measures the sizes each time it opens, after the screen is up: the trash and the index change elsewhere and in scans. */
    fun openSettings() = showSettings(onlyIfOpen = false)

    /** Reads settings again after a scan changed something. Does nothing once the user has left it, even if this was queued before. */
    fun reloadSettings() = showSettings(onlyIfOpen = true)

    private fun showSettings(onlyIfOpen: Boolean) {
        load {
            if (onlyIfOpen && it.screen != Screen.Settings) return@load it
            it.copy(
                screen = Screen.Settings,
                excludedFolders = app.index.excludedFolders(),
                embeddingCount = app.index.embeddingCount(),
                storage = if (it.screen == Screen.Settings) it.storage else null, // a scan re-opens it: keep the old sizes meanwhile
            )
        }
        refreshStorage()
        refreshScanStatus()
    }

    /** Counts outside the lock of [load] too, and under the same rule as [refreshStorage]. */
    private fun refreshScanStatus() {
        scanStatusJob?.cancel() // a slower, older read must not overwrite a newer one
        scanStatusJob = viewModelScope.launch { readScanStatus() }
    }

    private var scanStatusJob: Job? = null

    private suspend fun readScanStatus() {
        val coverage = withContext(Dispatchers.Default) { app.index.coverage() }
        val lastScan = withContext(Dispatchers.IO) { app.lastScan.report }
        val lastScanFailed = withContext(Dispatchers.IO) { app.lastScan.failed }
        load { if (it.screen == Screen.Settings) it.copy(coverage = coverage, lastScan = lastScan, lastScanFailed = lastScanFailed) else it }
    }

    /**
     * Measures outside the lock of [load]: it only reads, so Back and other changes need not wait for a big trash.
     * The sizes are kept only if settings is still open, and are written in the order of the loads before them.
     */
    private fun refreshStorage(after: Job? = null) = viewModelScope.launch {
        after?.join()
        val storage = measureStorage(app, review)
        load { if (it.screen == Screen.Settings) it.copy(storage = storage) else it }
    }

    /** Hides the image's folder, subfolders included, and shows the page again without it. */
    fun excludeFolder(image: ImageRef) = load { state ->
        val category = (state.screen as? Screen.Category)?.category ?: return@load state
        val folder = review.excludeFolderOf(image)
        state.showing(pageFor(category, state), keepMarksOf = state).copy(lastExclusion = folder)
    }

    /** Hides the folder from the storage map and from the review, subfolders included. The map starts again from its root. */
    fun excludeFolder(folder: String) = load { state ->
        review.excludeFolder(folder)
        state.copy(storageMap = storageMapRoot(), lastExclusion = folder)
    }

    fun undoExclusion() = load { state ->
        val folder = state.lastExclusion ?: return@load state
        review.removeExcludedFolder(folder)
        val undone = state.copy(lastExclusion = null)
        when (val screen = state.screen) {
            is Screen.Category -> undone.showing(pageFor(screen.category, state), keepMarksOf = state)
            Screen.StorageMap -> undone.copy(storageMap = storageMapRoot())
            Screen.Settings -> undone.copy(excludedFolders = app.index.excludedFolders())
            else -> undone
        }
    }

    /** Dismisses [folder], the exclusion the snackbar shows, and only while it is still the last one. */
    fun dismissExclusion(folder: String) = queue { if (it.lastExclusion == folder) it.copy(lastExclusion = null) else it }

    fun removeExcludedFolder(folder: String) = load { state ->
        review.removeExcludedFolder(folder)
        state.copy(excludedFolders = app.index.excludedFolders())
    }

    /** Asks first when the new retention would purge trashed images at the next scan: that cannot be undone. */
    fun chooseRetention(days: Int) = load { state ->
        val expiring = if (days < state.retentionDays) review.expiring(Duration.ofDays(days.toLong())) else 0
        if (expiring > 0) state.copy(pendingRetention = RetentionChange(days, expiring)) else state.withRetention(days)
    }

    fun confirmRetention() = load { state -> state.pendingRetention?.let { state.withRetention(it.days) } ?: state }

    fun setHaptics(enabled: Boolean) {
        app.haptics.enabled = enabled
        queue { it.copy(hapticsEnabled = enabled) }
    }

    fun cancelRetention() = queue { it.copy(pendingRetention = null) }

    private fun ReviewState.withRetention(days: Int): ReviewState {
        app.trashRetention.days = days
        return copy(retentionDays = days, pendingRetention = null)
    }

    /** Reads counts for the defaults and current thresholds; reloaded when opened and when a scan progresses. */
    fun openThresholds() = showThresholds(onlyIfOpen = false)

    /** Reads the counts again after a scan changed something. Does nothing once the user has left the screen, even if this was queued before. */
    fun reloadThresholds() = showThresholds(onlyIfOpen = true)

    private fun showThresholds(onlyIfOpen: Boolean) = load {
        if (onlyIfOpen && it.screen != Screen.Thresholds) return@load it
        it.copy(screen = Screen.Thresholds, defaultCounts = review.counts(THRESHOLDS)).withThresholds(app.thresholds.values)
    }

    /** Applies at once: the signals are in the index, so the lists only need ranking again. */
    fun setThreshold(category: Category, value: Float) = load {
        app.thresholds[category] = value
        it.withThresholds(app.thresholds.values)
    }

    fun resetThresholds() = load {
        app.thresholds.reset()
        it.withThresholds(app.thresholds.values)
    }

    private fun ReviewState.withOverview(overview: Overview) = copy(categories = overview.categories, freeableBytes = overview.freeableBytes)

    private suspend fun ReviewState.withThresholds(thresholds: Map<Category, Float>) = copy(thresholds = thresholds, thresholdCounts = review.counts(thresholds))

    fun openStorageMap() = load { state ->
        state.copy(screen = Screen.StorageMap, storageMap = storageMapRoot())
    }

    private suspend fun storageMapRoot() = listOf(buildFolderTree(review.notTrashed()))

    fun zoomStorageMap(folder: Folder) = load { state ->
        state.copy(storageMap = state.storageMap + folder)
    }

    fun back() = load { state ->
        when {
            state.screen == Screen.StorageMap && state.storageMap.size > 1 ->
                state.copy(storageMap = state.storageMap.dropLast(1))
            state.screen == Screen.StorageMap ->
                state.copy(screen = Screen.Settings, storageMap = emptyList(), excludedFolders = app.index.excludedFolders())
            state.screen == Screen.Thresholds ->
                state.copy(screen = Screen.Settings)
            else ->
                state.copy(screen = Screen.Home, page = emptyList(), lastDecision = null, lastExclusion = null, skipped = emptySet()).withOverview(review.overview())
        }
    }

    /** An image that left the page while the tap waited for a change has no mark to flip. */
    fun toggle(image: ImageRef) = queue {
        if (it.page.none { group -> group.images.any { item -> item.image.path == image.path } }) return@queue it
        it.copy(marked = if (image.path in it.marked) it.marked - image.path else it.marked + image.path)
    }

    /**
     * Trashes [toTrash], keeps [toKeep] and shows the next page. They are what the user confirmed, so a
     * confirmation of a page that is no longer on screen, like a second tap during an apply, does nothing.
     */
    fun applyPage(toTrash: Set<String>, toKeep: Set<String>) = load { state ->
        val category = (state.screen as? Screen.Category)?.category ?: return@load state
        val shown = state.page.flatMap { group -> group.images.map { it.image.path } }.toSet()
        if (toTrash != state.marked || toTrash + toKeep != shown) return@load state
        val decision = review.applyPage(state.page, toTrash)
        state.showing(pageFor(category, state)).copy(lastDecision = decision)
    }

    /** Leaves the groups [ids] for later without deciding anything: their images stay unreviewed. */
    fun skip(ids: Set<String>) = load { state ->
        val category = (state.screen as? Screen.Category)?.category ?: return@load state
        val skipped = state.skipped + ids
        state.showing(pageFor(category, state, skipped)).copy(skipped = skipped)
    }

    /** Undoes [decision], the one the snackbar shows, and only while it is still the last one: an older Undo must not undo a newer page. */
    fun undo(decision: Decision) = load { state ->
        if (state.lastDecision != decision) return@load state
        review.undo(decision)
        val category = (state.screen as? Screen.Category)?.category ?: return@load state.copy(lastDecision = null)
        state.showing(pageFor(category, state)).copy(lastDecision = null)
    }

    /** Dismisses [decision], the one the snackbar shows, and only while it is still the last one: a late dismiss must not drop the Undo of the next apply. */
    fun dismissUndo(decision: Decision) = queue { if (it.lastDecision == decision) it.copy(lastDecision = null) else it }

    fun select(item: TrashedImage) = queue {
        val path = item.original.path
        it.copy(selected = if (path in it.selected) it.selected - path else it.selected + path)
    }

    fun selectAll(items: List<TrashedImage>, select: Boolean) = queue {
        val paths = items.map { item -> item.original.path }.toSet()
        it.copy(selected = if (select) it.selected + paths else it.selected - paths)
    }

    fun restoreSelected() = load { state ->
        review.restore(state.trashed.filter { it.original.path in state.selected }.map { it.original })
        state.copy(trashed = review.trashed(), selected = emptySet())
    }

    fun emptyTrash() = load { state ->
        review.purge(state.trashed.map { it.original })
        state.copy(trashed = review.trashed(), selected = emptySet())
    }

    /** The one way to read a page, so none forgets the skipped groups or the review order of the open category. */
    private suspend fun pageFor(category: Category, state: ReviewState, skipped: Set<String> = state.skipped, grouping: ReviewOrder = state.reviewOrder) = review.page(category, skipped, grouping)

    /** Candidates start marked. With [keepMarksOf], images that were already on its page keep the user's marks. */
    private fun ReviewState.showing(page: Page, keepMarksOf: ReviewState? = null): ReviewState {
        val before = keepMarksOf?.page.orEmpty().flatMap { group -> group.images.map { it.image.path } }.toSet()
        val marked = page.groups.flatMap { it.images }
            .filter { if (keepMarksOf != null && it.image.path in before) it.image.path in keepMarksOf.marked else it.suggestTrash }
            .map { it.image.path }.toSet()
        return copy(page = page.groups, remaining = page.remaining, marked = marked)
    }

    /** A change that needs no loading, in the same line as [load]: it never lands between a load's read and its write. */
    private fun queue(change: (ReviewState) -> ReviewState) = viewModelScope.launch {
        changes.withLock { mutableState.update(change) }
    }

    /** One change at a time: the index and the trash see decisions in the order they were made. */
    private fun load(change: suspend (ReviewState) -> ReviewState) = viewModelScope.launch {
        changes.withLock {
            mutableState.update { it.copy(loading = true) }
            mutableState.value = change(mutableState.value).copy(loading = false)
        }
    }
}
