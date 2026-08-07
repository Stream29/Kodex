package io.github.stream29.kodex.cli.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.jakewharton.mosaic.layout.background
import com.jakewharton.mosaic.layout.fillMaxWidth
import com.jakewharton.mosaic.layout.height
import com.jakewharton.mosaic.layout.onPointerHover
import com.jakewharton.mosaic.layout.width
import com.jakewharton.mosaic.modifier.Modifier
import com.jakewharton.mosaic.ui.Box
import com.jakewharton.mosaic.ui.BoxScope
import com.jakewharton.mosaic.ui.Column
import com.jakewharton.mosaic.ui.Row
import com.jakewharton.mosaic.ui.Text
import com.jakewharton.mosaic.ui.TextStyle
import com.jakewharton.mosaic.ui.unit.IntOffset
import io.github.stream29.kodex.cli.agent.label
import io.github.stream29.kodex.cli.agent.toRenderState
import io.github.stream29.kodex.cli.components.LazyColumn
import io.github.stream29.kodex.cli.components.TuiButton
import io.github.stream29.kodex.cli.components.TuiContextMenu
import io.github.stream29.kodex.cli.components.TuiPressable
import io.github.stream29.kodex.cli.components.TuiPopupAnchor
import io.github.stream29.kodex.cli.components.TuiPopupMenuItem
import io.github.stream29.kodex.cli.components.ellipsizeToTerminalWidth
import io.github.stream29.kodex.cli.components.items
import io.github.stream29.kodex.cli.components.rememberTuiPopupAnchor
import io.github.stream29.kodex.cli.components.tuiPopupAnchor
import io.github.stream29.kodex.cli.components.wrapToTerminalWidth
import io.github.stream29.kodex.cli.session.AgentRuntimeTreeEntry
import io.github.stream29.kodex.cli.session.RootSessionViewState
import io.github.stream29.kodex.tool.unifiedexec.UnifiedExecProcessSession

@Composable
internal fun SessionAgentSidebar(
    tree: RootSessionViewState?,
    expanded: Boolean,
    columns: Int,
    rows: Int,
    runningIndicatorFrame: State<String>,
    onHoverChanged: (Boolean) -> Unit,
    onToggleExpanded: () -> Unit,
    onSelectAgent: (String) -> Unit,
    onOpenShellSessionMenu: (ShellSessionMenuRequest) -> Unit,
) {
    var expandedAgentIds by remember(tree?.rootAgentId) {
        mutableStateOf(tree?.rootAgentId?.let(::setOf).orEmpty())
    }
    val visibleAgents = tree?.visibleAgentTreeEntries(expandedAgentIds).orEmpty()
    val hasAgentTree = visibleAgents.isNotEmpty()
    val agentsWithChildren = remember(tree?.agents) {
        tree?.agents?.mapNotNull(AgentRuntimeTreeEntry::parentAgentId)?.toSet().orEmpty()
    }
    val selectedAgent = tree?.agents?.firstOrNull { agent -> agent.selected }
    val ongoingShellSessions = collectOngoingShellSessions(selectedAgent)
    val shellSessionItems = ongoingShellSessions.map { session ->
        ShellSessionSidebarItem(
            session = session,
            lines = shellSessionSidebarLines(
                sessionId = session.sessionId,
                command = session.arguments.command,
                columns = columns,
            ),
        )
    }
    val availableSplitListRows = (rows - SessionSidebarSectionRows).coerceAtLeast(0)
    val shellSessionListRows = minOf(
        shellSessionItems.sumOf { item -> item.lines.size },
        (availableSplitListRows + 1) / 2,
    )
    val shellSessionSectionRows = if (shellSessionListRows == 0) 0 else shellSessionListRows + 1
    val agentTreeRows = (rows - 2 - shellSessionSectionRows).coerceAtLeast(0)
    Column(
        modifier = Modifier
            .width(columns)
            .height(rows)
            .background(SettingsDialogNavigationBackground)
            .onPointerHover(
                onPointerEnter = { onHoverChanged(true) },
                onPointerExit = { onHoverChanged(false) },
            ),
    ) {
        if (expanded) {
            TuiButton(
                label = "←",
                modifier = Modifier.fillMaxWidth().background(SettingsDialogHeaderBackground),
                color = SettingsDialogForeground,
                onClick = onToggleExpanded,
            )
            if (hasAgentTree) {
                Text("Agent tree", color = SettingsDialogForeground)
                Box(modifier = Modifier.width(columns).height(agentTreeRows)) {
                    LazyColumn(modifier = Modifier.width(columns).height(agentTreeRows)) {
                        items(visibleAgents, key = { it.agentId }) { agent ->
                            val agentState by agent.viewModel.state.collectAsState()
                            val background = if (agent.selected) {
                                SettingsDialogSelectionBackground
                            } else {
                                SettingsDialogNavigationBackground
                            }
                            val hasChildren = agent.agentId in agentsWithChildren
                            val nodeLabel = tree?.agentTreeNodeLabel(
                                agent = agent,
                                threadName = agentState.durable.settings?.threadName,
                            ) ?: agent.agentId
                            val label = agentTreeNodeDisplayLabel(
                                nodeLabel = nodeLabel,
                                running = agentState.running,
                                runningIndicatorFrame = if (agentState.running) {
                                    runningIndicatorFrame.value
                                } else {
                                    ""
                                },
                                maximumColumns = (
                                    columns -
                                        agent.depth * SessionTreeIndentColumns -
                                        SessionTreeDisclosureColumns -
                                        SessionTreeNodeButtonBorderColumns
                                    ).coerceAtLeast(1),
                            )
                            Column(modifier = Modifier.fillMaxWidth().background(background)) {
                                Row(modifier = Modifier.fillMaxWidth().background(background)) {
                                    Text(" ".repeat(agent.depth * SessionTreeIndentColumns))
                                    if (hasChildren) {
                                        TuiButton(
                                            label = if (agent.agentId in expandedAgentIds) "▼" else "▶",
                                            modifier = Modifier.background(background),
                                            color = SettingsDialogForeground,
                                            onClick = {
                                                expandedAgentIds = if (agent.agentId in expandedAgentIds) {
                                                    expandedAgentIds - agent.agentId
                                                } else {
                                                    expandedAgentIds + agent.agentId
                                                }
                                            },
                                        )
                                    } else {
                                        Text(" ".repeat(SessionTreeDisclosureColumns))
                                    }
                                    TuiButton(
                                        label = label,
                                        modifier = Modifier.weight(1f).background(background),
                                        color = SettingsDialogForeground,
                                        onClick = { onSelectAgent(agent.agentId) },
                                    )
                                }
                                Text(
                                    value = (
                                        " ".repeat(
                                            agent.depth * SessionTreeIndentColumns +
                                                SessionTreeDisclosureColumns,
                                        ) + agentState.toRenderState().label()
                                        ).ellipsizeToTerminalWidth(columns),
                                    modifier = Modifier.fillMaxWidth().background(background),
                                    color = SettingsDialogForeground,
                                    textStyle = TextStyle.Dim,
                                )
                            }
                        }
                    }
                }
            }
            if (shellSessionListRows > 0) {
                Text("Shell sessions", color = SettingsDialogForeground)
                LazyColumn(
                    modifier = Modifier.width(columns).height(shellSessionListRows),
                ) {
                    items(shellSessionItems, key = { item -> item.session.sessionId }) { item ->
                        ShellSessionSidebarRow(
                            lines = item.lines,
                            onOpenMenu = { anchor, clickPosition ->
                                onOpenShellSessionMenu(
                                    ShellSessionMenuRequest(
                                        session = item.session,
                                        anchor = anchor,
                                        clickPosition = clickPosition,
                                    ),
                                )
                            },
                        )
                    }
                }
            }
        } else {
            TuiPressable(
                onClick = onToggleExpanded,
                modifier = Modifier.fillMaxWidth().background(SettingsDialogHeaderBackground),
            ) { _, isHovered, _ ->
                Text(
                    value = "→",
                    color = SettingsDialogForeground,
                    textStyle = if (isHovered) TextStyle.Bold else TextStyle.Unspecified,
                )
            }
        }
    }
}

internal fun agentTreeNodeDisplayLabel(
    nodeLabel: String,
    running: Boolean,
    runningIndicatorFrame: String,
    maximumColumns: Int,
): String = runningIndicatorLabel(
    name = nodeLabel,
    running = running,
    frame = runningIndicatorFrame,
).ellipsizeToTerminalWidth(maximumColumns)

@Composable
internal fun ShellSessionSidebarRow(
    lines: List<String>,
    onOpenMenu: (TuiPopupAnchor, IntOffset?) -> Unit,
) {
    val menuAnchor = rememberTuiPopupAnchor()
    TuiPressable(
        onClick = {},
        onSecondaryClick = { clickPosition -> onOpenMenu(menuAnchor, clickPosition) },
        modifier = Modifier
            .fillMaxWidth()
            .tuiPopupAnchor(menuAnchor),
    ) { isFocused, isHovered, _ ->
        val background = if (isFocused || isHovered) {
            SettingsDialogSelectionBackground
        } else {
            SettingsDialogNavigationBackground
        }
        Column(modifier = Modifier.fillMaxWidth().background(background)) {
            lines.forEach { line ->
                Text(
                    value = line,
                    modifier = Modifier.fillMaxWidth().background(background),
                    color = SettingsDialogForeground,
                    textStyle = TextStyle.Dim,
                )
            }
        }
    }
}

@Composable
internal fun BoxScope.ShellSessionContextMenu(
    request: ShellSessionMenuRequest?,
    onDismissRequest: () -> Unit,
) {
    val openRequest = request ?: return
    val completed by openRequest.session.completed.collectAsState()
    LaunchedEffect(openRequest, completed) {
        if (completed) onDismissRequest()
    }
    if (completed) return
    TuiContextMenu(
        expanded = true,
        anchor = openRequest.anchor,
        clickPosition = openRequest.clickPosition,
        onDismissRequest = onDismissRequest,
        backgroundColor = SettingsDialogHomeBackground,
    ) {
        TuiPopupMenuItem(
            key = "close-shell-session",
            onClick = openRequest.session::close,
        ) {
            Text("Close session")
        }
    }
}

@Composable
private fun collectOngoingShellSessions(
    selectedAgent: AgentRuntimeTreeEntry?,
): List<UnifiedExecProcessSession> {
    if (selectedAgent == null) return emptyList()
    return key(selectedAgent.viewModel) {
        val sessions by
        selectedAgent.viewModel.session.runtime.unifiedExecToolClient.activeSessions.collectAsState()
        buildList {
            sessions.values
                .sortedBy { session -> session.sessionId }
                .forEach { session ->
                    key(session.sessionId) {
                        val completed by session.completed.collectAsState()
                        if (!completed) add(session)
                    }
                }
        }
    }
}

internal data class ShellSessionMenuRequest(
    val session: UnifiedExecProcessSession,
    val anchor: TuiPopupAnchor,
    val clickPosition: IntOffset?,
)

private data class ShellSessionSidebarItem(
    val session: UnifiedExecProcessSession,
    val lines: List<String>,
)

internal fun RootSessionViewState.visibleAgentTreeEntries(
    expandedAgentIds: Set<String>,
): List<AgentRuntimeTreeEntry> {
    val agentsById = agents.associateBy(AgentRuntimeTreeEntry::agentId)
    return agents.filter { agent ->
        val visited = mutableSetOf<String>()
        var parentAgentId = agent.parentAgentId
        while (parentAgentId != null) {
            if (!visited.add(parentAgentId) || parentAgentId !in expandedAgentIds) return@filter false
            val parent = agentsById[parentAgentId] ?: return@filter false
            parentAgentId = parent.parentAgentId
        }
        true
    }
}

internal fun RootSessionViewState.agentTreeNodeLabel(
    agent: AgentRuntimeTreeEntry,
    threadName: String?,
): String {
    val label = threadName?.takeIf(String::isNotBlank) ?: agent.agentId
    return if (agent.agentId == rootAgentId) {
        label
    } else {
        label.substringAfterLast('/').ifBlank { label }
    }
}

internal fun shellSessionSidebarLines(
    sessionId: Int,
    command: String,
    columns: Int,
): List<String> = "$sessionId: $command".wrapToTerminalWidth(columns.coerceAtLeast(1))

internal const val SessionSidebarExpandedColumns: Int = 28
internal const val SessionSidebarCollapsedColumns: Int = 1

private const val SessionSidebarSectionRows: Int = 3
private const val SessionTreeIndentColumns: Int = 2
private const val SessionTreeDisclosureColumns: Int = 3
private const val SessionTreeNodeButtonBorderColumns: Int = 2
