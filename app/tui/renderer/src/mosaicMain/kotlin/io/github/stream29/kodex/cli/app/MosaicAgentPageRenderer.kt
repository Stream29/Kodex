package io.github.stream29.kodex.cli.app

import androidx.compose.runtime.Composable
import com.jakewharton.mosaic.focus.FocusRequester
import com.jakewharton.mosaic.layout.height
import com.jakewharton.mosaic.layout.width
import com.jakewharton.mosaic.modifier.Modifier
import com.jakewharton.mosaic.ui.*
import com.jakewharton.mosaic.ui.unit.IntOffset
import io.github.stream29.kodex.app.agent.contract.*
import io.github.stream29.kodex.app.history.contract.AgentHistoryViewModel
import io.github.stream29.kodex.app.history.contract.item.HistoryItemViewModel
import io.github.stream29.kodex.cli.agent.*
import io.github.stream29.kodex.cli.components.*
import io.github.stream29.kodex.cli.history.AgentHistoryView
import io.github.stream29.kodex.cli.history.AgentHistoryViewState
import io.github.stream29.kodex.cli.runtimeconfiguration.RuntimeConfigurationDropdowns
import io.github.stream29.kodex.cli.settings.NewLineKey

@Composable
public actual fun AgentRuntimeScreen(
    viewModel: AgentViewModel,
    columns: Int,
    rows: Int,
    newLineKey: NewLineKey,
    dropdowns: RuntimeConfigurationDropdowns,
    onOpenHistoryEntryContextMenu: (
        generation: Long, storageIndex: Int, item: HistoryItemViewModel,
        anchor: TuiPopupAnchor, clickPosition: IntOffset?,
    ) -> Unit,
    onBrowseWorkingDirectory: () -> Unit,
    onOpenSettings: () -> Unit,
    composerFocusRequester: FocusRequester?,
    historyViewState: AgentHistoryViewState,
) {
    AgentPageContent(
        viewModel, newLineKey,
        MosaicAgentPageRenderer(
            columns, rows, dropdowns, onOpenHistoryEntryContextMenu,
            composerFocusRequester, historyViewState,
        ),
        onBrowseWorkingDirectory, onOpenSettings,
    )
}

private class MosaicAgentPageRenderer(
    private val columns: Int,
    private val rows: Int,
    dropdowns: RuntimeConfigurationDropdowns,
    private val onOpenHistoryEntryContextMenu: (Long, Int, HistoryItemViewModel, TuiPopupAnchor, IntOffset?) -> Unit,
    private val composerFocusRequester: FocusRequester?,
    private val historyViewState: AgentHistoryViewState,
) : AgentPageRenderer {
    override val status = MosaicAgentStatusRenderer(columns, dropdowns)
    override val separator = MosaicHistorySeparatorRenderer(columns)

    @Composable
    override fun Frame(inputs: AgentPageMeasureInputs, content: @Composable (AgentPageRenderScope) -> Unit) {
        val layout = measureAgentPage(inputs, columns, rows)
        val slots = MosaicAgentPageScope(
            columns, layout, onOpenHistoryEntryContextMenu, composerFocusRequester, historyViewState,
        )
        Column(Modifier.width(columns).height(rows)) { content(slots) }
    }
}

private class MosaicAgentPageScope(
    private val columns: Int,
    private val layout: AgentPageLayout,
    private val onOpenHistoryEntryContextMenu: (Long, Int, HistoryItemViewModel, TuiPopupAnchor, IntOffset?) -> Unit,
    private val composerFocusRequester: FocusRequester?,
    private val historyViewState: AgentHistoryViewState,
) : AgentPageRenderScope {
    @Composable
    override fun History(viewModel: AgentHistoryViewModel, shellSessions: AgentShellSessionRegistry, canEdit: Boolean) {
        Box(Modifier.width(columns).height(layout.historyRows)) {
            AgentHistoryView(
                model = viewModel, shellSessions = shellSessions, viewState = historyViewState,
                onOpenEntryContextMenu = if (canEdit) onOpenHistoryEntryContextMenu else null,
            )
        }
    }

    @Composable
    override fun UserInput(viewModel: RequestUserInputViewModel, state: RequestUserInputState.Pending) {
        if (layout.toolRows > 0) RequestUserInputPanel(viewModel, state, columns, layout.toolRows)
    }

    @Composable
    override fun PendingSteer() {
        if (layout.steerLines.isEmpty()) return
        Column(Modifier.width(columns).height(layout.steerLines.size)) {
            layout.steerLines.forEachIndexed { index, line ->
                Text(line, textStyle = if (index == 0) TextStyle.Bold else TextStyle.Dim)
            }
        }
    }

    @Composable
    override fun Composer(viewModel: ComposerViewModel, newLineKey: NewLineKey, autoFocus: Boolean) {
        ComposerView(
            viewModel, columns, layout.composerRows, newLineKey,
            autoFocus = autoFocus, focusRequester = composerFocusRequester,
        )
    }
}

private data class AgentPageLayout(
    val historyRows: Int, val composerRows: Int, val toolRows: Int, val steerLines: List<String>,
)

private fun measureAgentPage(inputs: AgentPageMeasureInputs, columns: Int, rows: Int): AgentPageLayout {
    val composerText = inputs.composer.text
    val fullComposerLayout = TextInputLayout.create(
        value = TextInputValue(text = composerText, cursorOffset = composerText.length),
        width = columns, firstLinePrefix = "> ", continuationLinePrefix = "  ", softWrap = true,
    )
    val composerStatusRows = if (composerStatusLine(inputs.composer) == null) 0 else 1
    val statusBarRows = agentRuntimeStatusBarRows(columns, inputs.state, inputs.running, inputs.settings, inputs.tokenCount)
    val flexibleRows =
        (rows - HistoryComposerSeparatorRows - statusBarRows - composerStatusRows).coerceAtLeast(0)
    val minimumComposerRows = minOf(1, flexibleRows)
    val toolRows = agentPendingToolRows(flexibleRows - minimumComposerRows, inputs.pendingToolPanelCount)
    val allToolRows = toolRows * inputs.pendingToolPanelCount
    val steerLines = pendingSteerPreviewLines(
        pending = inputs.pendingSteer, columns = columns,
        maximumRows = minOf((flexibleRows - minimumComposerRows - allToolRows).coerceAtLeast(0), PendingSteerMaximumRows),
    )
    val composerAndHistoryRows = (flexibleRows - allToolRows - steerLines.size).coerceAtLeast(0)
    val composerRows = boundedComposerRows(availableRows = composerAndHistoryRows, desiredRows = fullComposerLayout.rowCount)
    return AgentPageLayout(
        historyRows = (composerAndHistoryRows - composerRows).coerceAtLeast(0),
        composerRows = composerRows + composerStatusRows, toolRows = toolRows, steerLines = steerLines,
    )
}

internal fun agentPendingToolRows(availableRows: Int, panelCount: Int): Int {
    require(panelCount >= 0)
    return if (panelCount == 0) 0 else minOf(availableRows.coerceAtLeast(0) / panelCount, RequestUserInputMaximumRows)
}

internal fun submitToSteerHint(running: Boolean, draft: String): String? =
    SubmitToSteerHint.takeIf { running && draft.isNotBlank() }

private const val RequestUserInputMaximumRows: Int = 12
private const val PendingSteerMaximumRows: Int = 6
private const val SubmitToSteerHint: String = "Submit to steer"
