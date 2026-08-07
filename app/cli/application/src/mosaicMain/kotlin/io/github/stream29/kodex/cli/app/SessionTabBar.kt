package io.github.stream29.kodex.cli.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import com.jakewharton.mosaic.layout.background
import com.jakewharton.mosaic.layout.fillMaxWidth
import com.jakewharton.mosaic.modifier.Modifier
import com.jakewharton.mosaic.ui.Row
import com.jakewharton.mosaic.ui.Text
import com.jakewharton.mosaic.ui.TextStyle
import com.jakewharton.mosaic.ui.unit.IntOffset
import io.github.stream29.kodex.cli.components.TuiButton
import io.github.stream29.kodex.cli.components.TuiPopupAnchor
import io.github.stream29.kodex.cli.components.ellipsizeToTerminalWidth
import io.github.stream29.kodex.cli.components.rememberTuiPopupAnchor
import io.github.stream29.kodex.cli.components.tuiPopupAnchor
import io.github.stream29.kodex.cli.agent.AgentRuntimeViewState
import io.github.stream29.kodex.utils.terminaltext.terminalCellWidth
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

internal data class SessionTabRenderState(
    val target: SessionTabTarget,
    val selected: Boolean,
    val sessionName: String,
    val running: Boolean = false,
)

private data class RootSessionTabSummary(
    val sessionName: String,
    val running: Boolean,
)

@Composable
internal fun collectSessionTabRenderStates(
    tabs: List<SessionTabViewState>,
): List<SessionTabRenderState> {
    val renderStates = ArrayList<SessionTabRenderState>(tabs.size)
    tabs.forEach { entry ->
        key(entry.target) {
            renderStates += collectSessionTabRenderState(entry)
        }
    }
    return renderStates
}

@Composable
private fun collectSessionTabRenderState(entry: SessionTabViewState): SessionTabRenderState =
    when (val target = entry.target) {
        is SessionTabTarget.NewSession -> {
            val sessionName = entry.newSessionName
                ?: if (target.ordinal == 1) "New session" else "New session ${target.ordinal}"
            SessionTabRenderState(
                target = target,
                selected = entry.selected,
                sessionName = sessionName,
            )
        }

        is SessionTabTarget.OpenSession -> {
            val rootSessionViewModel = requireNotNull(requireNotNull(entry.rootSession).viewModel)
            val rootAgentViewModelFlow = remember(rootSessionViewModel) {
                rootSessionViewModel.state
                    .map { tree ->
                        tree.agents
                            .firstOrNull { agent -> agent.agentId == tree.rootAgentId }
                            ?.viewModel
                    }
                    .distinctUntilChanged()
            }
            val initialTree = rootSessionViewModel.state.value
            val initialRootAgentViewModel = initialTree.agents
                .firstOrNull { agent -> agent.agentId == initialTree.rootAgentId }
                ?.viewModel
            val rootAgentViewModel by rootAgentViewModelFlow.collectAsState(initialRootAgentViewModel)
            rootAgentViewModel?.let { viewModel ->
                val summaryFlow = remember(viewModel, target.sessionIndex) {
                    viewModel.state
                        .map { state -> state.toRootSessionTabSummary(target.sessionIndex) }
                        .distinctUntilChanged()
                }
                val summary by summaryFlow.collectAsState(
                    viewModel.state.value.toRootSessionTabSummary(target.sessionIndex),
                )
                SessionTabRenderState(
                    target = target,
                    selected = entry.selected,
                    sessionName = summary.sessionName,
                    running = summary.running,
                )
            } ?: SessionTabRenderState(
                target = target,
                selected = entry.selected,
                sessionName = "Session ${target.sessionIndex}",
            )
        }
    }

private fun AgentRuntimeViewState.toRootSessionTabSummary(sessionIndex: Int): RootSessionTabSummary =
    RootSessionTabSummary(
        sessionName = durable.settings?.threadName
            ?.takeIf(String::isNotBlank)
            ?: "Session $sessionIndex",
        running = running,
    )

/** Application session navigation: catalog access, visible tabs, and a New-session creation action. */
@Composable
internal fun SessionTabBar(
    tabs: List<SessionTabRenderState>,
    runningIndicatorFrame: State<String>,
    columns: Int,
    onSelectTab: (SessionTabTarget) -> Unit,
    onOpenTabMenu: (SessionTabTarget, String, TuiPopupAnchor, IntOffset?) -> Unit,
    onCreateNewSession: () -> Unit,
    onOpenSessions: () -> Unit,
) {
    val tabColumns = (columns - TabControlsColumns).coerceAtLeast(0)
    val visible = visibleTabs(tabs, tabColumns)

    Row(modifier = Modifier.fillMaxWidth().background(SessionTopBarBackground)) {
        TuiButton(
            label = "Sessions",
            modifier = Modifier.background(SessionButtonBackground),
            color = SessionForeground,
            onClick = onOpenSessions,
        )
        Text(" ")
        visible.forEachIndexed { index, entry ->
            if (index != 0) Text(" ")
            key(entry.target) {
                SessionTab(
                    entry = entry,
                    runningIndicatorFrame = runningIndicatorFrame,
                    maximumLabelColumns = tabLabelColumns(entry, tabColumns, visible.size),
                    onClick = { onSelectTab(entry.target) },
                    onOpenMenu = onOpenTabMenu,
                )
            }
        }
        if (visible.isNotEmpty()) Text(" ")
        TuiButton(
            label = "+",
            modifier = Modifier.background(SessionButtonBackground),
            color = SessionForeground,
            onClick = onCreateNewSession,
        )
    }
}

@Composable
private fun SessionTab(
    entry: SessionTabRenderState,
    runningIndicatorFrame: State<String>,
    maximumLabelColumns: Int,
    onClick: () -> Unit,
    onOpenMenu: (SessionTabTarget, String, TuiPopupAnchor, IntOffset?) -> Unit,
) {
    val label = if (entry.running) {
        runningIndicatorLabel(
            name = entry.sessionName,
            running = true,
            frame = runningIndicatorFrame.value,
        )
    } else {
        entry.sessionName
    }
    SessionTabButton(
        entry = entry,
        sessionName = entry.sessionName,
        label = label,
        maximumLabelColumns = maximumLabelColumns,
        onClick = onClick,
        onOpenMenu = onOpenMenu,
    )
}

@Composable
private fun SessionTabButton(
    entry: SessionTabRenderState,
    sessionName: String,
    label: String,
    maximumLabelColumns: Int,
    onClick: () -> Unit,
    onOpenMenu: (SessionTabTarget, String, TuiPopupAnchor, IntOffset?) -> Unit,
) {
    val tabMenuAnchor = rememberTuiPopupAnchor()
    TuiButton(
        label = label.ellipsizeToTerminalWidth(maximumLabelColumns),
        modifier = Modifier
            .background(if (entry.selected) SessionButtonBackground else SessionTopBarBackground)
            .tuiPopupAnchor(tabMenuAnchor),
        color = SessionForeground,
        idleTextStyle = if (entry.selected) TextStyle.Bold else TextStyle.Unspecified,
        onSecondaryClick = { clickPosition ->
            onOpenMenu(entry.target, sessionName, tabMenuAnchor, clickPosition)
        },
        onClick = onClick,
    )
}

private fun visibleTabs(
    tabs: List<SessionTabRenderState>,
    availableColumns: Int,
): List<SessionTabRenderState> {
    val active = tabs.firstOrNull { entry -> entry.selected }
    val ordered = listOfNotNull(active) + tabs.filter { entry -> entry != active }
    var remaining = availableColumns
    return buildList {
        ordered.forEach { entry ->
            val required = minimumTabColumns(entry) + if (isEmpty()) 0 else TabSpacingColumns
            if (required <= remaining) {
                add(entry)
                remaining -= required
            }
        }
    }.sortedBy { entry -> tabs.indexOf(entry) }
}

private fun tabLabelColumns(entry: SessionTabRenderState, availableColumns: Int, tabCount: Int): Int {
    val spacing = (tabCount - 1).coerceAtLeast(0) * TabSpacingColumns
    val availableForLabels = (availableColumns - spacing - tabCount * TabBracketsColumns).coerceAtLeast(tabCount)
    val equalShare = (availableForLabels / tabCount.coerceAtLeast(1)).coerceAtLeast(1)
    return minOf(equalShare, MaximumTabLabelColumns).coerceAtLeast(minimumLabelColumns(entry))
}

private fun minimumTabColumns(entry: SessionTabRenderState): Int =
    minimumLabelColumns(entry) + TabBracketsColumns

private fun minimumLabelColumns(entry: SessionTabRenderState): Int = when (val target = entry.target) {
    is SessionTabTarget.NewSession -> "new".terminalCellWidth()
    is SessionTabTarget.OpenSession -> "s${target.sessionIndex}".terminalCellWidth()
}

private const val MaximumTabLabelColumns: Int = 20
private const val TabBracketsColumns: Int = 2
private const val TabSpacingColumns: Int = 1
private const val TabControlsColumns: Int = 15
