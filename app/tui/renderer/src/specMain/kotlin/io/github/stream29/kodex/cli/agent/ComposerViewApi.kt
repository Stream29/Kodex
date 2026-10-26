package io.github.stream29.kodex.cli.agent

import androidx.compose.runtime.Composable
import com.jakewharton.mosaic.focus.FocusRequester
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableIndexEvent
import io.github.stream29.kodex.app.agent.contract.ComposerState
import io.github.stream29.kodex.app.agent.contract.ComposerViewModel
import io.github.stream29.kodex.cli.settings.NewLineKey

/**
 * Renders one Composer child. All draft and command state comes from [viewModel]; this renderer
 * keeps only Mosaic's widget buffer, focus, cursor, viewport and layout.
 *
 * Empty/ready/running/steer/request-input/error/closed branches are explicit in
 * [ComposerState.mode].
 * The Enter handler captures the displayed revision and invokes the component's revision-bound
 * submit command; it does not append messages, steer, clear drafts or inspect a sibling child.
 * Hosts may render the pending preview and failure themselves; hiding either never hides
 * the remaining status (including the running draft's submit-to-steer hint).
 */
@Composable
public expect fun ComposerView(
    viewModel: ComposerViewModel,
    columns: Int,
    rows: Int,
    newLineKey: NewLineKey,
    autoFocus: Boolean = true,
    enabled: Boolean = true,
    focusRequester: FocusRequester? = null,
    showPendingSteer: Boolean = true,
    showFailure: Boolean = true,
): Unit

/**
 * Caps composer content at the available rows, reserving one row even when the host has no space.
 *
 * @throws IllegalArgumentException if [desiredRows] is not positive.
 */
public expect fun boundedComposerRows(availableRows: Int, desiredRows: Int): Int

/**
 * Builds the shared host/Composer pending preview in queue order without claiming its inputs.
 *
 * Returns no rows for an empty queue or nonpositive [maximumRows]. Width is at least one column.
 * When truncation and capacity allow, a final row reports the omitted count.
 */
public expect fun pendingSteerPreviewLines(
    pending: List<StableIndexEvent.Steerable>,
    columns: Int,
    maximumRows: Int,
): List<String>
