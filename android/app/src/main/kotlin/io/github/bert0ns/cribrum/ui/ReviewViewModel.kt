package io.github.bert0ns.cribrum.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.bert0ns.cribrum.CribrumApp
import io.github.bert0ns.cribrum.model.Category
import io.github.bert0ns.cribrum.model.ImageRef
import io.github.bert0ns.cribrum.ranker.THRESHOLDS
import io.github.bert0ns.cribrum.review.CategorySummary
import io.github.bert0ns.cribrum.review.Decision
import io.github.bert0ns.cribrum.review.Page
import io.github.bert0ns.cribrum.review.Review
import io.github.bert0ns.cribrum.review.ReviewGroup
import io.github.bert0ns.cribrum.trash.TrashRetention
import io.github.bert0ns.cribrum.trash.TrashedImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.Duration

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
    /** Folders the user asked never to be suggested again, shown in settings. */
    val excludedFolders: List<String> = emptyList(),
    /** Embeddings stored in the index, shown in settings. */
    val embeddingCount: Int = 0,
    /** What Cribrum takes on the phone, shown in settings. */
    val storage: StorageUse? = null,
    /** The thresholds in use, and how many candidates each category lists at them and at the defaults. */
    val thresholds: Map<Category, Float> = THRESHOLDS,
    val thresholdCounts: Map<Category, Int> = emptyMap(),
    val defaultCounts: Map<Category, Int> = emptyMap(),
    /** The folder the user just excluded, until they undo it or move on. */
    val lastExclusion: String? = null,
)

class ReviewViewModel @JvmOverloads constructor(
    application: Application,
    private val review: Review = (application as CribrumApp).let { Review(it.index, it.trash, thresholds = { it.thresholds.values }) },
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

    fun refresh() = load { it.copy(categories = review.categories(), indexEmpty = app.index.imageCount() == 0) }

    fun open(category: Category) = load {
        it.copy(screen = Screen.Category(category), lastDecision = null, lastExclusion = null, skipped = emptySet()).showing(review.page(category))
    }

    fun openTrash() = load { it.copy(screen = Screen.Trash, trashed = review.trashed(), selected = emptySet()) }

    /** Only the embeddings go: the next scan computes them again. */
    fun deleteEmbeddings() = load { state ->
        app.index.deleteEmbeddings()
        state.copy(embeddingCount = app.index.embeddingCount(), storage = measureStorage(app, review))
    }

    /** Measures the sizes each time it opens: the trash and the index change on other screens and in scans. */
    fun openSettings() = load {
        it.copy(
            screen = Screen.Settings,
            excludedFolders = app.index.excludedFolders(),
            embeddingCount = app.index.embeddingCount(),
            storage = measureStorage(app, review),
        )
    }

    /** Hides the image's folder, subfolders included, and shows the page again without it. */
    fun excludeFolder(image: ImageRef) = load { state ->
        val category = (state.screen as? Screen.Category)?.category ?: return@load state
        val folder = review.excludeFolderOf(image)
        state.showing(review.page(category, state.skipped), keepMarksOf = state).copy(lastExclusion = folder)
    }

    fun undoExclusion() = load { state ->
        val folder = state.lastExclusion ?: return@load state
        review.removeExcludedFolder(folder)
        val category = (state.screen as? Screen.Category)?.category ?: return@load state.copy(lastExclusion = null)
        state.showing(review.page(category, state.skipped), keepMarksOf = state).copy(lastExclusion = null)
    }

    fun dismissExclusion() = mutableState.update { it.copy(lastExclusion = null) }

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
        mutableState.update { it.copy(hapticsEnabled = enabled) }
    }

    fun cancelRetention() = mutableState.update { it.copy(pendingRetention = null) }

    private fun ReviewState.withRetention(days: Int): ReviewState {
        app.trashRetention.days = days
        return copy(retentionDays = days, pendingRetention = null)
    }

    /** The defaults' counts are read once: they only change with a scan, and the screen stays open for one slider at a time. */
    fun openThresholds() = load {
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

    private suspend fun ReviewState.withThresholds(thresholds: Map<Category, Float>) = copy(thresholds = thresholds, thresholdCounts = review.counts(thresholds))

    fun openStorageMap() = load { state ->
        val root = buildFolderTree(review.notTrashed())
        state.copy(screen = Screen.StorageMap, storageMap = listOf(root))
    }

    fun zoomStorageMap(folder: Folder) = load { state ->
        state.copy(storageMap = state.storageMap + folder)
    }

    fun back() = load { state ->
        when {
            state.screen == Screen.StorageMap && state.storageMap.size > 1 ->
                state.copy(storageMap = state.storageMap.dropLast(1))
            state.screen == Screen.StorageMap ->
                state.copy(screen = Screen.Settings, storageMap = emptyList())
            state.screen == Screen.Thresholds ->
                state.copy(screen = Screen.Settings)
            else ->
                state.copy(screen = Screen.Home, categories = review.categories(), page = emptyList(), lastDecision = null, lastExclusion = null, skipped = emptySet())
        }
    }

    fun toggle(image: ImageRef) = mutableState.update {
        it.copy(marked = if (image.path in it.marked) it.marked - image.path else it.marked + image.path)
    }

    /** Trashes the marked images of the page, keeps the others, and shows the next page. */
    fun applyPage() = load { state ->
        val category = (state.screen as? Screen.Category)?.category ?: return@load state
        val images = state.page.flatMap { group -> group.images.map { it.image } }
        val decision = review.apply(images.filter { it.path in state.marked }, images.filter { it.path !in state.marked })
        state.showing(review.page(category, state.skipped)).copy(lastDecision = decision)
    }

    /** Leaves the groups [ids] for later without deciding anything: their images stay unreviewed. */
    fun skip(ids: Set<String>) = load { state ->
        val category = (state.screen as? Screen.Category)?.category ?: return@load state
        val skipped = state.skipped + ids
        state.showing(review.page(category, skipped)).copy(lastDecision = null, skipped = skipped)
    }

    fun undo() = load { state ->
        val decision = state.lastDecision ?: return@load state
        review.undo(decision)
        val category = (state.screen as? Screen.Category)?.category ?: return@load state.copy(lastDecision = null)
        state.showing(review.page(category, state.skipped)).copy(lastDecision = null)
    }

    fun dismissUndo() = mutableState.update { it.copy(lastDecision = null) }

    fun select(item: TrashedImage) = mutableState.update {
        val path = item.original.path
        it.copy(selected = if (path in it.selected) it.selected - path else it.selected + path)
    }

    fun selectAll(items: List<TrashedImage>, select: Boolean) = mutableState.update {
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

    /** Candidates start marked. With [keepMarksOf], images that were already on its page keep the user's marks. */
    private fun ReviewState.showing(page: Page, keepMarksOf: ReviewState? = null): ReviewState {
        val before = keepMarksOf?.page.orEmpty().flatMap { group -> group.images.map { it.image.path } }.toSet()
        val marked = page.groups.flatMap { it.images }
            .filter { if (keepMarksOf != null && it.image.path in before) it.image.path in keepMarksOf.marked else it.suggestTrash }
            .map { it.image.path }.toSet()
        return copy(page = page.groups, remaining = page.remaining, marked = marked)
    }

    /** One change at a time: the index and the trash see decisions in the order they were made. */
    private fun load(change: suspend (ReviewState) -> ReviewState) {
        viewModelScope.launch {
            changes.withLock {
                mutableState.update { it.copy(loading = true) }
                mutableState.value = change(mutableState.value).copy(loading = false)
            }
        }
    }
}
