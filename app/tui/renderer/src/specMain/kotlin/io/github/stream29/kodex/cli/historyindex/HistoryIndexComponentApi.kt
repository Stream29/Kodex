@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")

package io.github.stream29.kodex.cli.historyindex

import androidx.compose.runtime.Stable
import com.jakewharton.mosaic.ui.unit.IntOffset
import io.github.stream29.kodex.app.agent.contract.HistoryIndexViewModel
import io.github.stream29.kodex.cli.components.TuiPopupAnchor

/** Renderer-local anchor/pointer and captured child target; host owns popup arbitration/grace. */
@Stable
public expect class HistoryIndexInteractionRequest(
    side: HistoryIndexSide,
    viewModel: HistoryIndexViewModel,
    generation: Long,
    index: Int,
    anchor: TuiPopupAnchor,
    revision: Long = viewModel.window.value.revision,
) {
    public val side: HistoryIndexSide
    public val viewModel: HistoryIndexViewModel
    public val generation: Long
    public val index: Int
    public val anchor: TuiPopupAnchor
    public val revision: Long
    public var pointerPosition: IntOffset?

    /** No latest selected-Agent lookup. Host separately checks its selected stable child identity. */
    public fun isCurrent(): Boolean
}

/** Identity (not data equality) distinguishes fresh menu openings for the same row. */
public class HistoryIndexMenuRequest(
    public val target: HistoryIndexInteractionRequest,
    public val clickPosition: IntOffset?,
)
