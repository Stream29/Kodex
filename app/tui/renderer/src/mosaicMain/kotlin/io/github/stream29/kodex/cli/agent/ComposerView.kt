package io.github.stream29.kodex.cli.agent

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import com.jakewharton.mosaic.focus.FocusRequester
import com.jakewharton.mosaic.layout.height
import com.jakewharton.mosaic.layout.width
import com.jakewharton.mosaic.modifier.Modifier
import com.jakewharton.mosaic.ui.Box
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableIndexEvent
import io.github.stream29.kodex.app.agent.contract.ComposerViewModel
import io.github.stream29.kodex.cli.components.LocalTextInputFocusRequester
import io.github.stream29.kodex.cli.components.ellipsizeToTerminalWidth
import io.github.stream29.kodex.cli.settings.NewLineKey

@Composable
public actual fun ComposerView(
    viewModel: ComposerViewModel,
    columns: Int,
    rows: Int,
    newLineKey: NewLineKey,
    autoFocus: Boolean,
    enabled: Boolean,
    focusRequester: FocusRequester?,
    showPendingSteer: Boolean,
    showFailure: Boolean,
) {
    if (rows <= 0) return
    ComposerContent(
        viewModel, newLineKey, autoFocus, enabled, showPendingSteer, showFailure,
        maximumPreviewLines = minOf(6, (rows - 1).coerceAtLeast(0)),
        maximumEditorLines = rows,
        frame = { content ->
            CompositionLocalProvider(LocalTextInputFocusRequester provides focusRequester) {
                Box(Modifier.width(columns.coerceAtLeast(1)).height(rows)) { content() }
            }
        },
    )
}

public actual fun boundedComposerRows(availableRows: Int, desiredRows: Int): Int {
    require(desiredRows > 0) { "Composer content must contain at least one row." }
    return minOf(desiredRows, availableRows.coerceAtLeast(1))
}

public actual fun pendingSteerPreviewLines(
    pending: List<StableIndexEvent.Steerable>,
    columns: Int,
    maximumRows: Int,
): List<String> = pendingSteerPreviewContent(pending, maximumRows).map {
    it.ellipsizeToTerminalWidth(columns.coerceAtLeast(1))
}
