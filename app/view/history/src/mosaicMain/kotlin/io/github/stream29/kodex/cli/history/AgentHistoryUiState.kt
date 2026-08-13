package io.github.stream29.kodex.cli.history

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.stream29.kodex.cli.components.LazyListState
import io.github.stream29.kodex.cli.components.MutableScrollInteractionSource
import io.github.stream29.kodex.cli.components.ScrollInputSource
import io.github.stream29.kodex.cli.components.ScrollInteraction
import io.github.stream29.kodex.cli.components.ScrollOrientation

/** Frontend-local scroll position and follow-latest intent for one Agent history. */
@Stable
public class AgentHistoryUiState(
    public val listState: LazyListState = LazyListState(),
) {
    public val interactionSource: MutableScrollInteractionSource =
        MutableScrollInteractionSource(::onInteractionCommitted)

    public var followsLatest: Boolean by mutableStateOf(true)
        private set

    internal fun requestLatestForContentChange() {
        if (followsLatest) listState.requestScrollToStart()
    }

    internal fun reconcileLayout() {
        if (followsLatest) {
            if (listState.canScrollForward) listState.requestScrollToStart()
        } else if (!listState.canScrollForward) {
            followsLatest = true
        }
    }

    private fun onInteractionCommitted(interaction: ScrollInteraction) {
        if (
            interaction.orientation != ScrollOrientation.Vertical ||
            interaction.consumedDelta == 0
        ) {
            return
        }
        when (interaction.source) {
            ScrollInputSource.Pointer,
            ScrollInputSource.Keyboard,
                -> if (interaction.consumedDelta < 0) {
                    followsLatest = false
                } else if (!listState.canScrollForward) {
                    followsLatest = true
                }

            ScrollInputSource.FocusRelocation,
            ScrollInputSource.Programmatic,
                -> Unit
        }
    }
}
