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
}
