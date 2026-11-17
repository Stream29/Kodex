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
internal fun ComposerView(
    viewModel: ComposerViewModel,
    columns: Int,
    rows: Int,
    newLineKey: NewLineKey,
    autoFocus: Boolean = true,
    enabled: Boolean = true,
    focusRequester: FocusRequester? = null,
) {
    if (rows <= 0) return
    ComposerContent(
        viewModel = viewModel,
        newLineKey = newLineKey,
        autoFocus = autoFocus,
        enabled = enabled,
        maximumEditorLines = rows,
        frame = { content ->
            CompositionLocalProvider(LocalTextInputFocusRequester provides focusRequester) {
                Box(Modifier.width(columns.coerceAtLeast(1)).height(rows)) { content() }
            }
        },
    )
}

internal fun boundedComposerRows(availableRows: Int, desiredRows: Int): Int {
    require(desiredRows > 0) { "Composer content must contain at least one row." }
    return minOf(desiredRows, availableRows.coerceAtLeast(1))
}

internal fun pendingSteerPreviewLines(
    pending: List<StableIndexEvent.Steerable>,
    columns: Int,
    maximumRows: Int,
): List<String> = pendingSteerPreviewContent(pending, maximumRows).map {
    it.ellipsizeToTerminalWidth(columns.coerceAtLeast(1))
}
