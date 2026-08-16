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
import io.github.stream29.kodex.app.agent.contract.AgentAddress
import io.github.stream29.kodex.app.agent.contract.AgentShellSession
import io.github.stream29.kodex.app.agent.contract.AgentViewModel
import io.github.stream29.kodex.app.session.contract.PersistedSessionTopologyNode
import io.github.stream29.kodex.app.session.contract.PersistedSessionTopologyState
import io.github.stream29.kodex.cli.agent.label
import io.github.stream29.kodex.cli.components.LazyColumn
import io.github.stream29.kodex.cli.components.TuiButton
import io.github.stream29.kodex.cli.components.TuiContextMenu
import io.github.stream29.kodex.cli.components.TuiPopupAnchor
import io.github.stream29.kodex.cli.components.TuiPopupMenuItem
import io.github.stream29.kodex.cli.components.TuiPressable
import io.github.stream29.kodex.cli.components.ellipsizeToTerminalWidth
import io.github.stream29.kodex.cli.components.items
import io.github.stream29.kodex.cli.components.rememberTuiPopupAnchor
import io.github.stream29.kodex.cli.components.tuiInteractionTextStyle
import io.github.stream29.kodex.cli.components.tuiPopupAnchor
import io.github.stream29.kodex.cli.components.wrapToTerminalWidth

@Composable
internal fun SessionAgentSidebar(
    topology: PersistedSessionTopologyState?,
    selectedAgent: AgentViewModel?,
    columns: Int,
    rows: Int,
    runningIndicatorFrame: State<String>,
    onHoverChanged: (Boolean) -> Unit,
    onToggleExpanded: () -> Unit,
    onExpandAgent: (AgentAddress) -> Unit,
    onSelectAgent: (AgentAddress) -> Unit,
    onOpenShellSessionMenu: (ShellSessionMenuRequest) -> Unit,
) {
    var expandedAddresses by remember(topology?.rootAddress) {
        mutableStateOf(topology?.rootAddress?.let(::setOf).orEmpty())
    }
    val visibleAgents = topology?.visibleNodes(expandedAddresses).orEmpty()
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
        TuiButton(
            label = "←",
            modifier = Modifier.fillMaxWidth().background(SettingsDialogHeaderBackground),
            color = SettingsDialogForeground,
            onClick = onToggleExpanded,
        )
        if (visibleAgents.isNotEmpty()) {
            Text("Agent tree", color = SettingsDialogForeground)
            Box(modifier = Modifier.width(columns).height(agentTreeRows)) {
                LazyColumn(modifier = Modifier.width(columns).height(agentTreeRows)) {
                    items(visibleAgents, key = PersistedSessionTopologyNode::address) { node ->
                        val selected = node.address == selectedAgent?.address
                        val nodeLabel = topology?.nodeLabel(node) ?: node.address.agentId
                        val label = agentTreeNodeDisplayLabel(
                            nodeLabel = nodeLabel,
                            running = node.running,
                            runningIndicatorFrame =
                                runningIndicatorFrame.value.takeIf { node.running }.orEmpty(),
                            maximumColumns = (
                                columns -
                                    node.depth * SessionTreeIndentColumns -
                                    SessionTreeDisclosureColumns -
                                    SessionTreeNodeButtonBorderColumns
                                ).coerceAtLeast(1),
                        )
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(SettingsDialogNavigationBackground),
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(SettingsDialogNavigationBackground),
                            ) {
                                Text(" ".repeat(node.depth * SessionTreeIndentColumns))
                                if (node.hasChildren) {
                                    TuiButton(
                                        label = if (node.address in expandedAddresses) "▼" else "▶",
                                        modifier = Modifier.background(
                                            SettingsDialogNavigationBackground,
                                        ),
                                        color = SettingsDialogForeground,
                                        onClick = {
                                            if (node.address in expandedAddresses) {
                                                expandedAddresses -= node.address
                                            } else {
                                                expandedAddresses += node.address
                                                onExpandAgent(node.address)
                                            }
                                        },
                                    )
                                } else {
                                    Text(" ".repeat(SessionTreeDisclosureColumns))
                                }
                                TuiButton(
                                    label = label,
                                    modifier = Modifier
                                        .weight(1f)
                                        .background(SettingsDialogNavigationBackground),
                                    color = SettingsDialogForeground,
                                    selected = selected,
                                    onClick = { onSelectAgent(node.address) },
                                )
                            }
                            Text(
                                value = (
                                    " ".repeat(
                                        node.depth * SessionTreeIndentColumns +
                                            SessionTreeDisclosureColumns,
                                    ) + node.phase.label()
                                    ).ellipsizeToTerminalWidth(columns),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(SettingsDialogNavigationBackground),
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
            LazyColumn(modifier = Modifier.width(columns).height(shellSessionListRows)) {
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
    }
}

@Composable
internal fun SessionAgentSidebarExpandButton(
    onHoverChanged: (Boolean) -> Unit,
    onExpand: () -> Unit,
) {
    TuiButton(
        label = "→",
        onClick = onExpand,
        modifier = Modifier
            .width(SessionSidebarCollapsedButtonColumns)
            .height(SessionSidebarCollapsedButtonRows)
            .background(SettingsDialogHeaderBackground)
            .onPointerHover(
                onPointerEnter = { onHoverChanged(true) },
                onPointerExit = { onHoverChanged(false) },
            ),
        color = SettingsDialogForeground,
    )
}

internal fun agentTreeNodeDisplayLabel(
    nodeLabel: String,
    running: Boolean,
    runningIndicatorFrame: String,
    maximumColumns: Int,
): String = runningIndicatorLabel(nodeLabel, running, runningIndicatorFrame)
    .ellipsizeToTerminalWidth(maximumColumns)

@Composable
internal fun ShellSessionSidebarRow(
    lines: List<String>,
    onOpenMenu: (TuiPopupAnchor, IntOffset?) -> Unit,
) {
    val anchor = rememberTuiPopupAnchor()
    TuiPressable(
        onClick = {},
        onSecondaryClick = { position -> onOpenMenu(anchor, position) },
        modifier = Modifier.fillMaxWidth().tuiPopupAnchor(anchor),
    ) { _, hovered, pressed ->
        val textStyle = tuiInteractionTextStyle(
            hovered = hovered,
            pressed = pressed,
            idleTextStyle = TextStyle.Dim,
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(SettingsDialogNavigationBackground),
        ) {
            lines.forEach { line ->
                Text(
                    value = line,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(SettingsDialogNavigationBackground),
                    color = SettingsDialogForeground,
                    textStyle = textStyle,
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
private fun collectOngoingShellSessions(selectedAgent: AgentViewModel?): List<AgentShellSession> {
    if (selectedAgent == null) return emptyList()
    return key(selectedAgent) {
        val sessions by selectedAgent.shellSessions.activeSessions.collectAsState()
        buildList {
            sessions.values.sortedBy(AgentShellSession::sessionId).forEach { session ->
                key(session.sessionId) {
                    val completed by session.completed.collectAsState()
                    if (!completed) add(session)
                }
            }
        }
    }
}

internal data class ShellSessionMenuRequest(
    val session: AgentShellSession,
    val anchor: TuiPopupAnchor,
    val clickPosition: IntOffset?,
)

private data class ShellSessionSidebarItem(
    val session: AgentShellSession,
    val lines: List<String>,
)

internal fun PersistedSessionTopologyState.visibleNodes(
    expandedAddresses: Set<AgentAddress>,
): List<PersistedSessionTopologyNode> {
    val byAddress = nodes.associateBy(PersistedSessionTopologyNode::address)
    return nodes.filter { node ->
        val visited = mutableSetOf<AgentAddress>()
        var parentAddress = node.parentAddress
        while (parentAddress != null) {
            if (!visited.add(parentAddress) || parentAddress !in expandedAddresses) {
                return@filter false
            }
            val parent = byAddress[parentAddress] ?: return@filter false
            parentAddress = parent.parentAddress
        }
        true
    }
}

internal fun PersistedSessionTopologyState.nodeLabel(
    node: PersistedSessionTopologyNode,
): String {
    val label = node.threadName ?: node.address.agentId
    return if (node.address == rootAddress) label else label.substringAfterLast('/').ifBlank { label }
}

internal fun shellSessionSidebarLines(
    sessionId: Int,
    command: String,
    columns: Int,
): List<String> = "$sessionId: $command".wrapToTerminalWidth(columns.coerceAtLeast(1))

internal const val SessionSidebarExpandedColumns: Int = 28
internal const val SessionSidebarCollapsedButtonColumns: Int = 3
internal const val SessionSidebarCollapsedButtonRows: Int = 1

private const val SessionSidebarSectionRows: Int = 3
private const val SessionTreeIndentColumns: Int = 2
private const val SessionTreeDisclosureColumns: Int = 3
private const val SessionTreeNodeButtonBorderColumns: Int = 2
