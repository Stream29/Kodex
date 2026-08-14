package io.github.stream29.kodex.desktop.session

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.stream29.kodex.app.agent.contract.AgentAddress
import io.github.stream29.kodex.app.agent.contract.AgentExecutionPhase
import io.github.stream29.kodex.app.session.contract.PersistedAgentMaterializationState
import io.github.stream29.kodex.app.session.contract.PersistedSessionTopologyNode
import io.github.stream29.kodex.app.session.contract.PersistedSessionTopologyState

/** Lightweight persisted Agent tree matching the TUI sidebar hierarchy. */
@Composable
public fun SessionTopologyDesktopView(
    topology: PersistedSessionTopologyState,
    selectedAddress: AgentAddress,
    onSelect: (AgentAddress) -> Unit,
    onMaterializeChildren: (AgentAddress) -> Unit,
    modifier: Modifier = Modifier,
): Unit {
    var expandedAddresses by remember(topology.rootAddress) {
        mutableStateOf(setOf(topology.rootAddress))
    }
    val visibleNodes = remember(topology, expandedAddresses) {
        topology.visibleNodes(expandedAddresses)
    }

    LazyColumn(modifier = modifier) {
        items(
            items = visibleNodes,
            key = PersistedSessionTopologyNode::address,
        ) { node ->
            TopologyRow(
                topology = topology,
                node = node,
                selected = node.address == selectedAddress,
                expanded = node.address in expandedAddresses,
                onToggleExpanded = {
                    if (node.address in expandedAddresses) {
                        expandedAddresses -= node.address
                    } else {
                        expandedAddresses += node.address
                        onMaterializeChildren(node.address)
                    }
                },
                onSelect = { onSelect(node.address) },
            )
        }
    }
}

@Composable
private fun TopologyRow(
    topology: PersistedSessionTopologyState,
    node: PersistedSessionTopologyNode,
    selected: Boolean,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
    onSelect: () -> Unit,
): Unit {
    val containerColor = if (selected) {
        MaterialTheme.colorScheme.secondaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceContainerLow
    }
    val contentColor = if (selected) {
        MaterialTheme.colorScheme.onSecondaryContainer
    } else {
        MaterialTheme.colorScheme.onSurface
    }
    Surface(
        color = containerColor,
        contentColor = contentColor,
        shape = RectangleShape,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onSelect)
                .padding(vertical = 3.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Spacer(Modifier.width((node.depth * 14).dp))
                Box(
                    modifier = Modifier
                        .size(24.dp)
                        .then(
                            if (node.hasChildren) {
                                Modifier.clickable(onClick = onToggleExpanded)
                            } else {
                                Modifier
                            },
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    if (node.hasChildren) {
                        Text(
                            if (expanded) "▼" else "▶",
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }
                Text(
                    text = topology.nodeLabel(node),
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.labelLarge,
                )
                when (node.materialization) {
                    PersistedAgentMaterializationState.Loading ->
                        CircularProgressIndicator(
                            modifier = Modifier.size(14.dp),
                            strokeWidth = 2.dp,
                        )

                    else -> Unit
                }
                if (node.running) {
                    CircularProgressIndicator(
                        modifier = Modifier.padding(horizontal = 6.dp).size(12.dp),
                        strokeWidth = 2.dp,
                    )
                } else {
                    Spacer(Modifier.width(6.dp))
                }
            }
            Text(
                text = when (node.materialization) {
                    is PersistedAgentMaterializationState.Failed -> "failed"
                    else -> node.phase.desktopLabel()
                },
                modifier = Modifier.padding(
                    start = (node.depth * 14 + 24).dp,
                    end = 6.dp,
                ),
                color = when (node.materialization) {
                    is PersistedAgentMaterializationState.Failed ->
                        MaterialTheme.colorScheme.error

                    else -> contentColor.copy(alpha = 0.68f)
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}

private fun PersistedSessionTopologyState.visibleNodes(
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

private fun PersistedSessionTopologyState.nodeLabel(
    node: PersistedSessionTopologyNode,
): String {
    val label = node.threadName ?: node.address.agentId
    return if (node.address == rootAddress) {
        label
    } else {
        label.substringAfterLast('/').ifBlank { label }
    }
}

private fun AgentExecutionPhase.desktopLabel(): String = when (this) {
    AgentExecutionPhase.Empty -> "empty"
    AgentExecutionPhase.UserMessage -> "user message"
    AgentExecutionPhase.Responding -> "responding"
    AgentExecutionPhase.AssistantMessage -> "assistant message"
    AgentExecutionPhase.ToolPending -> "tool pending"
    AgentExecutionPhase.ToolCompleted -> "tool completed"
    AgentExecutionPhase.ExternalWrite -> "external write"
    AgentExecutionPhase.Compacting -> "compacting"
}
