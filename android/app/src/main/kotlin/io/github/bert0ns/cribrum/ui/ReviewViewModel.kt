package io.github.bert0ns.cribrum.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.bert0ns.cribrum.CribrumApp
import io.github.bert0ns.cribrum.model.Category
import io.github.bert0ns.cribrum.model.ImageRef
import io.github.bert0ns.cribrum.review.CategorySummary
import io.github.bert0ns.cribrum.review.Decision
import io.github.bert0ns.cribrum.review.Review
import io.github.bert0ns.cribrum.review.ReviewGroup
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

sealed interface Screen {
    data object Home : Screen
    data class Category(val category: io.github.bert0ns.cribrum.model.Category) : Screen
}

data class ReviewState(
    val screen: Screen = Screen.Home,
    val categories: List<CategorySummary> = emptyList(),
    val page: List<ReviewGroup> = emptyList(),
    /** Candidates the open category has left, the page included. */
    val remaining: Int = 0,
    /** Paths marked for the trash on the current page. */
    val marked: Set<String> = emptySet(),
    /** The last applied page, until the user undoes it or moves on. */
    val lastDecision: Decision? = null,
    val loading: Boolean = false,
)

class ReviewViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as CribrumApp
    private val review = Review(app.index, app.trash)
    private val mutableState = MutableStateFlow(ReviewState())
    private val changes = Mutex()
    val state: StateFlow<ReviewState> = mutableState.asStateFlow()

    fun refresh() = load { it.copy(categories = review.categories()) }

    fun open(category: Category) = load {
        val page = review.page(category)
        it.copy(screen = Screen.Category(category), page = page.groups, remaining = page.remaining, marked = marked(page.groups), lastDecision = null)
    }

    fun back() = load { it.copy(screen = Screen.Home, categories = review.categories(), page = emptyList(), lastDecision = null) }

    fun toggle(image: ImageRef) = mutableState.update {
        it.copy(marked = if (image.path in it.marked) it.marked - image.path else it.marked + image.path)
    }

    /** Trashes the marked images of the page, keeps the others, and shows the next page. */
    fun applyPage() = load { state ->
        val category = (state.screen as? Screen.Category)?.category ?: return@load state
        val images = state.page.flatMap { group -> group.images.map { it.image } }
        val decision = review.apply(images.filter { it.path in state.marked }, images.filter { it.path !in state.marked })
        val page = review.page(category)
        state.copy(page = page.groups, remaining = page.remaining, marked = marked(page.groups), lastDecision = decision)
    }

    fun undo() = load { state ->
        val decision = state.lastDecision ?: return@load state
        review.undo(decision)
        val category = (state.screen as? Screen.Category)?.category ?: return@load state.copy(lastDecision = null)
        val page = review.page(category)
        state.copy(page = page.groups, remaining = page.remaining, marked = marked(page.groups), lastDecision = null)
    }

    fun dismissUndo() = mutableState.update { it.copy(lastDecision = null) }

    private fun marked(page: List<ReviewGroup>) =
        page.flatMap { it.images }.filter { it.suggestTrash }.map { it.image.path }.toSet()

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
