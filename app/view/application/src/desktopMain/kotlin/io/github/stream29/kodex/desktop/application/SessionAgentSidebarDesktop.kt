package io.github.stream29.kodex.desktop.application

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.stream29.kodex.app.agent.contract.AgentAddress
import io.github.stream29.kodex.app.agent.contract.AgentShellSession
import io.github.stream29.kodex.app.agent.contract.AgentViewModel
import io.github.stream29.kodex.app.session.contract.PersistedSessionTopologyState
import io.github.stream29.kodex.desktop.components.desktopSecondaryClick
import io.github.stream29.kodex.desktop.session.SessionTopologyDesktopView

/** Always-present collapsible Agent sidebar, equivalent to the TUI sidebar. */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun SessionAgentSidebarDesktop(
    topology: PersistedSessionTopologyState?,
    selectedAgent: AgentViewModel?,
    expanded: Boolean,
    onHoverChanged: (Boolean) -> Unit,
    onToggleExpanded: () -> Unit,
    onExpandAgent: (AgentAddress) -> Unit,
    onSelectAgent: (AgentAddress) -> Unit,
    onContextMenuVisibilityChanged: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
): Unit {
    val shellSessions = collectOngoingShellSessions(selectedAgent)

    Surface(
        modifier = modifier
            .fillMaxHeight()
            .onPointerEvent(PointerEventType.Enter) { onHoverChanged(true) }
            .onPointerEvent(PointerEventType.Exit) { onHoverChanged(false) },
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        contentColor = MaterialTheme.colorScheme.onSurface,
        shape = RectangleShape,
    ) {
        Column(Modifier.fillMaxSize()) {
            TextButton(
                onClick = onToggleExpanded,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.textButtonColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                ),
                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 7.dp),
                shape = RectangleShape,
            ) {
                Text(if (expanded) "←" else "→")
            }
            if (expanded) {
                BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                    val shellMaximumHeight = maxHeight / 2
                    Column(Modifier.fillMaxSize()) {
                        topology?.takeIf { it.nodes.isNotEmpty() }?.let { state ->
                            Text(
                                text = "Agent tree",
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.labelMedium,
                            )
                            SessionTopologyDesktopView(
                                topology = state,
                                selectedAddress = selectedAgent?.address ?: state.rootAddress,
                                onSelect = onSelectAgent,
                                onMaterializeChildren = onExpandAgent,
                                modifier = Modifier.weight(1f).fillMaxWidth(),
                            )
                        }
                        if (shellSessions.isNotEmpty()) {
                            Text(
                                text = "Shell sessions",
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.labelMedium,
                            )
                            LazyColumn(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(max = shellMaximumHeight),
                            ) {
                                items(
                                    items = shellSessions,
                                    key = AgentShellSession::sessionId,
                                ) { shell ->
                                    ShellSessionDesktopRow(
                                        shell = shell,
                                        onMenuVisibilityChanged =
                                            onContextMenuVisibilityChanged,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun collectOngoingShellSessions(
    selectedAgent: AgentViewModel?,
): List<AgentShellSession> {
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

@Composable
private fun ShellSessionDesktopRow(
    shell: AgentShellSession,
    onMenuVisibilityChanged: (Boolean) -> Unit,
): Unit {
    var menuOpen by remember(shell.sessionId) { mutableStateOf(false) }
    Box {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .desktopSecondaryClick {
                    menuOpen = true
                    onMenuVisibilityChanged(true)
                },
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            shape = RectangleShape,
        ) {
            Text(
                text = "${shell.sessionId}: ${shell.arguments.command}",
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        DropdownMenu(
            expanded = menuOpen,
            onDismissRequest = {
                menuOpen = false
                onMenuVisibilityChanged(false)
            },
        ) {
            DropdownMenuItem(
                text = { Text("Close session") },
                onClick = {
                    menuOpen = false
                    onMenuVisibilityChanged(false)
                    shell.close()
                },
            )
        }
    }
}

internal val SessionSidebarExpandedWidth = 280.dp
internal val SessionSidebarCollapsedWidth = 32.dp
