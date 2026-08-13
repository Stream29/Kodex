package io.github.stream29.kodex.cli.app

import androidx.compose.runtime.mutableStateOf
import com.jakewharton.mosaic.testing.runMosaicTest
import io.github.stream29.kodex.app.agent.contract.AgentAddress
import io.github.stream29.kodex.app.session.contract.PersistedAgentMaterializationState
import io.github.stream29.kodex.app.session.contract.PersistedSessionTopologyNode
import io.github.stream29.kodex.app.session.contract.PersistedSessionTopologyState
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private val fixedSidebarRunningIndicatorFrame = mutableStateOf("⠋")

class SessionAgentSidebarTest {
    @Test
    fun emptySidebarOmitsTheAgentTreeSection() = runTest {
        runMosaicTest {
            val snapshot = setContentAndSnapshot {
                SessionAgentSidebar(
                    topology = null,
                    selectedAgent = null,
                    expanded = true,
                    columns = 28,
                    rows = 8,
                    runningIndicatorFrame = fixedSidebarRunningIndicatorFrame,
                    onHoverChanged = {},
                    onToggleExpanded = {},
                    onExpandAgent = {},
                    onSelectAgent = {},
                    onOpenShellSessionMenu = {},
                )
            }

            assertTrue("←" in snapshot)
            assertFalse("Agent tree" in snapshot)
            assertFalse("No agents" in snapshot)
        }
    }

    @Test
    fun runningAgentTreeLabelPrefixesTheSpinnerWithoutSpacing() {
        assertEquals(
            "⠋worker",
            agentTreeNodeDisplayLabel(
                nodeLabel = "worker",
                running = true,
                runningIndicatorFrame = "⠋",
                maximumColumns = 20,
            ),
        )
        assertEquals(
            "worker",
            agentTreeNodeDisplayLabel(
                nodeLabel = "worker",
                running = false,
                runningIndicatorFrame = "⠋",
                maximumColumns = 20,
            ),
        )
    }

    @Test
    fun topologyExpansionIsFrontendLocalAndUsesPathTailLabels() {
        val root = AgentAddress(7, "root")
        val worker = AgentAddress(7, "worker")
        val reviewer = AgentAddress(7, "reviewer")
        val topology = PersistedSessionTopologyState(
            rootAddress = root,
            nodes = listOf(
                topologyNode(root, null, 0, "Root / title"),
                topologyNode(worker, root, 1, "/root/worker"),
                topologyNode(reviewer, worker, 2, "/root/worker/reviewer"),
            ),
        )

        assertEquals(
            listOf(root, worker),
            topology.visibleNodes(setOf(root)).map { node -> node.address },
        )
        assertEquals(
            listOf(root, worker, reviewer),
            topology.visibleNodes(setOf(root, worker)).map { node -> node.address },
        )
        assertEquals("Root / title", topology.nodeLabel(topology.nodes[0]))
        assertEquals("worker", topology.nodeLabel(topology.nodes[1]))
        assertEquals("reviewer", topology.nodeLabel(topology.nodes[2]))
    }

    @Test
    fun shellSessionRowsWrapHardLines() {
        assertEquals(
            listOf("42: abcd", "efghijkl", "next"),
            shellSessionSidebarLines(
                sessionId = 42,
                command = "abcdefghijkl\nnext",
                columns = 8,
            ),
        )
    }
}

private fun topologyNode(
    address: AgentAddress,
    parent: AgentAddress?,
    depth: Int,
    threadName: String,
): PersistedSessionTopologyNode = PersistedSessionTopologyNode(
    address = address,
    parentAddress = parent,
    depth = depth,
    threadName = threadName,
    hasChildren = address.agentId != "reviewer",
    materialization = PersistedAgentMaterializationState.Unloaded,
)
