package com.tabs.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch

/** How the pile is stacked. Each answers a different mood. */
enum class DeckOrder(val label: String) {
    /** Tab debt first: the ones that have been judging you the longest. */
    OLDEST("Oldest"),

    /** What you were just reading, for finishing a train of thought. */
    FRESH("Freshest"),

    /** Same-site tabs adjacent, biggest cluster first. */
    SITE("By site"),
}

enum class Screen { DECK, ARCHIVE }

data class ReviewState(
    val screen: Screen = Screen.DECK,
    val archive: List<Archived> = emptyList(),
    val archiveLoading: Boolean = false,
    val tabs: List<Tab> = emptyList(),
    val order: DeckOrder = DeckOrder.OLDEST,
    val loading: Boolean = false,
    val configured: Boolean = true,
    val error: String? = null,
    val closedThisSession: Int = 0,
    val keptThisSession: Int = 0,
)

class ReviewViewModel : ViewModel() {

    private val store = Store(BuildConfig.DB_URL, BuildConfig.SPACE_ID)

    /** Unordered truth. [ReviewState.tabs] is this, arranged. */
    private var pile: List<Tab> = emptyList()

    var state by mutableStateOf(ReviewState(configured = store.configured))
        private set

    init {
        if (store.configured) refresh()
    }

    fun refresh() {
        if (!store.configured) return
        state = state.copy(loading = true, error = null)
        viewModelScope.launch {
            runCatching { store.tabs() }
                .onSuccess { loaded ->
                    pile = loaded
                    state = state.copy(tabs = arrange(pile, state.order), loading = false)
                }
                .onFailure { state = state.copy(loading = false, error = it.message) }
        }
    }

    fun show(screen: Screen) {
        state = state.copy(screen = screen)
        // The archive only changes when this phone closes something, so it is
        // fetched on demand rather than kept live.
        if (screen == Screen.ARCHIVE && state.archive.isEmpty()) loadArchive()
    }

    fun loadArchive() {
        if (!store.configured) return
        state = state.copy(archiveLoading = true)
        viewModelScope.launch {
            runCatching { store.archive() }
                .onSuccess { state = state.copy(archive = it, archiveLoading = false) }
                .onFailure { state = state.copy(archiveLoading = false, error = it.message) }
        }
    }

    /** Drop a URL from the archive for good. Nothing else in the app deletes. */
    fun forget(archived: Archived) {
        state = state.copy(archive = state.archive.filterNot { it.hash == archived.hash })
        viewModelScope.launch {
            runCatching { store.forget(archived.hash) }.onFailure { failure ->
                state = state.copy(
                    archive = (state.archive + archived).sortedByDescending { it.closedAt },
                    error = "Couldn't forget: ${failure.message}",
                )
            }
        }
    }

    fun setOrder(order: DeckOrder) {
        state = state.copy(order = order, tabs = arrange(pile, order))
    }

    /**
     * Swipe right: leave the tab open on the desktop, just take it off today's
     * pile. v1 should resurface it in a few days rather than until next launch.
     */
    fun keep(tab: Tab) {
        pile = pile.filterNot { it.hash == tab.hash }
        state = state.copy(
            tabs = state.tabs.filterNot { it.hash == tab.hash },
            keptThisSession = state.keptThisSession + 1,
        )
    }

    /**
     * Drop the card immediately so the swipe feels instant; the desktop catches
     * up within a minute. On failure the tab comes back and says why.
     */
    fun close(tab: Tab) {
        pile = pile.filterNot { it.hash == tab.hash }
        state = state.copy(
            tabs = state.tabs.filterNot { it.hash == tab.hash },
            closedThisSession = state.closedThisSession + 1,
        )
        viewModelScope.launch {
            runCatching { store.close(tab) }
                .onSuccess { loadArchive() }
                .onFailure { failure ->
                    pile = pile + tab
                    state = state.copy(
                        tabs = arrange(pile, state.order),
                        closedThisSession = (state.closedThisSession - 1).coerceAtLeast(0),
                        error = "Couldn't close: ${failure.message}",
                    )
                }
        }
    }

    /** How many other cards still in the pile come from the same site. */
    fun siblingsOf(tab: Tab): Int = state.tabs.count { it.host == tab.host } - 1

    private fun arrange(tabs: List<Tab>, order: DeckOrder): List<Tab> = when (order) {
        DeckOrder.OLDEST -> tabs.sortedBy { it.firstSeen }
        DeckOrder.FRESH -> tabs.sortedByDescending { it.firstSeen }
        // Biggest cluster first: eleven tabs on one topic are one decision, and
        // seeing them back to back is what makes that obvious.
        DeckOrder.SITE -> tabs.groupBy { it.host }
            .entries
            .sortedWith(compareByDescending<Map.Entry<String, List<Tab>>> { it.value.size }
                .thenBy { it.key })
            .flatMap { entry -> entry.value.sortedBy { it.firstSeen } }
    }
}
