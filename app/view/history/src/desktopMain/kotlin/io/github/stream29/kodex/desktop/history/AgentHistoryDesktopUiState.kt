package io.github.stream29.kodex.desktop.history

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Desktop-local viewport, follow-tail intent, and expansion state for one Agent history. */
@Stable
public class AgentHistoryDesktopUiState(
    public val listState: LazyListState = LazyListState(
        firstVisibleItemIndex = Int.MAX_VALUE,
    ),
) {
    private val expandedItems = mutableStateMapOf<Any, Boolean>()

    public var followsLatest: Boolean by mutableStateOf(true)
        private set

    internal fun isExpanded(key: Any): Boolean = expandedItems[key] == true

    internal fun setExpanded(key: Any, expanded: Boolean) {
        if (expanded) {
            expandedItems[key] = true
        } else {
            expandedItems.remove(key)
        }
    }

    internal fun beginUserScroll() {
        followsLatest = false
    }

    internal fun beginPageScroll(towardOlder: Boolean) {
        if (towardOlder) followsLatest = false
    }

    internal fun recordUserScroll(atLatest: Boolean) {
        followsLatest = atLatest
    }

    internal fun recordPageScroll(towardOlder: Boolean, atLatest: Boolean) {
        followsLatest = when {
            atLatest -> true
            towardOlder -> false
            else -> followsLatest
        }
    }

    internal fun reconcileLatest(atLatest: Boolean) {
        if (!followsLatest && atLatest) followsLatest = true
    }
}
