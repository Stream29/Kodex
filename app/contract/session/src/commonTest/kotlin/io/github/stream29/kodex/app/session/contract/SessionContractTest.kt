package io.github.stream29.kodex.app.session.contract

import io.github.stream29.kodex.app.agent.contract.AgentAddress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

public class SessionContractTest {
    @Test
    public fun persistedStateRequiresAnAvailableRootAndConsistentTopology() {
        val rootAddress = AgentAddress(sessionIndex = 3, agentId = "root")
        val childAddress = AgentAddress(sessionIndex = 3, agentId = "child")
        val topology = PersistedSessionTopologyState(
            rootAddress = rootAddress,
            nodes = listOf(
                PersistedSessionTopologyNode(
                    address = rootAddress,
                    parentAddress = null,
                    depth = 0,
                ),
                PersistedSessionTopologyNode(
                    address = childAddress,
                    parentAddress = rootAddress,
                    depth = 1,
                ),
            ),
            revision = 2,
        )

        assertEquals(rootAddress, topology.rootAddress)
        assertEquals(2, topology.nodes.size)
        assertFailsWith<IllegalArgumentException> {
            PersistedSessionSummaryState(agentCount = 0)
        }
        assertFailsWith<IllegalArgumentException> {
            topology.copy(nodes = topology.nodes.drop(1))
        }
        assertFailsWith<IllegalArgumentException> {
            topology.copy(
                nodes = topology.nodes.map { node ->
                    if (node.address == childAddress) {
                        node.copy(depth = 2)
                    } else {
                        node
                    }
                },
            )
        }
    }

    @Test
    public fun persistedTopologyAcceptsCanonicalOrderedPartialTrees() {
        val root = AgentAddress(sessionIndex = 7, agentId = "root")
        val first = AgentAddress(sessionIndex = 7, agentId = "first")
        val deep = AgentAddress(sessionIndex = 7, agentId = "deep")
        val second = AgentAddress(sessionIndex = 7, agentId = "second")

        val topology = PersistedSessionTopologyState(
            rootAddress = root,
            nodes = listOf(
                topologyNode(root, parent = null, depth = 0, hasChildren = true),
                topologyNode(first, parent = root, depth = 1, hasChildren = true),
                topologyNode(deep, parent = first, depth = 2),
                topologyNode(second, parent = root, depth = 1),
            ),
        )
        val undiscoveredPartialTree = PersistedSessionTopologyState(
            rootAddress = root,
            nodes = listOf(
                topologyNode(root, parent = null, depth = 0, hasChildren = true),
            ),
        )

        assertEquals(listOf(root, first, deep, second), topology.nodes.map { node -> node.address })
        assertEquals(true, undiscoveredPartialTree.nodes.single().hasChildren)
    }

    @Test
    public fun persistedTopologyRejectsAStateThatDoesNotStartAtItsRoot() {
        val root = AgentAddress(sessionIndex = 7, agentId = "root")
        val child = AgentAddress(sessionIndex = 7, agentId = "child")

        assertFailsWith<IllegalArgumentException> {
            PersistedSessionTopologyState(
                rootAddress = root,
                nodes = listOf(
                    topologyNode(child, parent = root, depth = 1),
                    topologyNode(root, parent = null, depth = 0),
                ),
            )
        }
    }

    @Test
    public fun persistedTopologyRejectsForestsAndSkippedDepths() {
        val root = AgentAddress(sessionIndex = 7, agentId = "root")
        val orphan = AgentAddress(sessionIndex = 7, agentId = "orphan")
        val deep = AgentAddress(sessionIndex = 7, agentId = "deep")

        assertFailsWith<IllegalArgumentException> {
            PersistedSessionTopologyState(
                rootAddress = root,
                nodes = listOf(
                    topologyNode(root, parent = null, depth = 0),
                    topologyNode(orphan, parent = null, depth = 1),
                ),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            PersistedSessionTopologyState(
                rootAddress = root,
                nodes = listOf(
                    topologyNode(root, parent = null, depth = 0),
                    topologyNode(deep, parent = root, depth = 2),
                ),
            )
        }
    }

    @Test
    public fun persistedTopologyRejectsAReenteredSubtree() {
        val root = AgentAddress(sessionIndex = 7, agentId = "root")
        val first = AgentAddress(sessionIndex = 7, agentId = "first")
        val firstChild = AgentAddress(sessionIndex = 7, agentId = "first-child")
        val second = AgentAddress(sessionIndex = 7, agentId = "second")

        assertFailsWith<IllegalArgumentException> {
            PersistedSessionTopologyState(
                rootAddress = root,
                nodes = listOf(
                    topologyNode(root, parent = null, depth = 0),
                    topologyNode(first, parent = root, depth = 1),
                    topologyNode(second, parent = root, depth = 1),
                    topologyNode(firstChild, parent = first, depth = 2),
                ),
            )
        }
    }
}

private fun topologyNode(
    address: AgentAddress,
    parent: AgentAddress?,
    depth: Int,
    hasChildren: Boolean = false,
): PersistedSessionTopologyNode = PersistedSessionTopologyNode(
    address = address,
    parentAddress = parent,
    depth = depth,
    hasChildren = hasChildren,
)
