@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")

package io.github.stream29.kodex.cli.historyindex

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import com.jakewharton.mosaic.ui.BoxScope
import com.jakewharton.mosaic.ui.unit.IntOffset
import io.github.stream29.kodex.app.agent.contract.HistoryIndexEntry
import io.github.stream29.kodex.app.agent.contract.HistoryIndexEntryDetail
import io.github.stream29.kodex.app.agent.contract.HistoryIndexReadState
import io.github.stream29.kodex.app.agent.contract.HistoryIndexViewModel
import io.github.stream29.kodex.cli.components.LazyListState
import io.github.stream29.kodex.cli.components.TuiPopupAnchor

/** Placement policy only; does not identify or own an Agent. */
public enum class HistoryIndexSide { Left, Right }

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

/**
 * Complete index body, borrowing one stable child. Each composed row owns/releases its read
 * handle. Two bodies share window authority but have independent viewport/interaction lifetimes.
 * Pointer/keyboard upward scroll disables follow-latest; reaching the end restores it.
 */
@Composable
public expect fun HistoryIndexSidebarBody(
    viewModel: HistoryIndexViewModel,
    columns: Int,
    rows: Int,
    side: HistoryIndexSide = HistoryIndexSide.Left,
    onHoverChanged: (HistoryIndexInteractionRequest, Boolean) -> Unit = { _, _ -> },
    onOpenMenu: (HistoryIndexMenuRequest) -> Unit = {},
    listState: LazyListState = remember(viewModel) { LazyListState().apply { requestScrollToEnd() } },
): Unit

/** Pure row rendering entrypoint, also useful for renderer snapshot coverage. */
@Composable
public expect fun HistoryIndexRowContent(
    state: HistoryIndexReadState<HistoryIndexEntry>,
    graph: String,
    hovered: Boolean = false,
    pressed: Boolean = false,
): Unit

/**
 * Delays hover locally, then acquires/releases a detail handle for this exact request.
 * Host filters selected child and popup priority before supplying request; hover exit grace
 * remains host-local. Invalid/released target is hidden, never shown as a new target's failure.
 */
@Composable
public expect fun BoxScope.HistoryIndexHoverPopup(
    request: HistoryIndexInteractionRequest?,
    contentColumns: Int,
    contentRows: Int,
    onHoverChanged: (Boolean) -> Unit,
    onDismissRequest: () -> Unit = {},
): Unit

/** Full hover rendering including shared questions/options/hidden answers and scrollable detail. */
@Composable
public expect fun BoxScope.HistoryIndexHoverContent(
    anchor: TuiPopupAnchor,
    side: HistoryIndexSide,
    pointerPosition: IntOffset?,
    state: HistoryIndexReadState<HistoryIndexEntryDetail>,
    contentColumns: Int,
    contentRows: Int,
    onHoverChanged: (Boolean) -> Unit,
): Unit

/**
 * Exact menu opening owns a timestamp handle. Only Ready(non-null) displays timestamp.
 * Check out calls the child's captured-Agent port; it never obtains an Agent from the host.
 * Host supplies only selected-child-matching requests and arbitrates shared Shell/index popup.
 */
@Composable
public expect fun BoxScope.HistoryIndexContextMenu(
    request: HistoryIndexMenuRequest?,
    onDismissRequest: () -> Unit,
): Unit

/** Information + Check out only; null click uses keyboard anchor, null timestamp omits its field. */
@Composable
public expect fun BoxScope.HistoryIndexContextMenuPopup(
    anchor: TuiPopupAnchor,
    clickPosition: IntOffset?,
    index: Int,
    onDismissRequest: () -> Unit,
    onCheckOut: () -> Unit,
    timestamp: String? = null,
): Unit
