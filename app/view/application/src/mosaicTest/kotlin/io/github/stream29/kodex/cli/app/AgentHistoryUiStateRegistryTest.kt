package io.github.stream29.kodex.cli.app

import de.infix.testBalloon.framework.core.testSuite
import kotlin.test.assertNotSame
import kotlin.test.assertSame

val agentHistoryUiStateRegistryTest by testSuite {
    test("states are isolated by Session and Agent identity") {
        val registry = AgentHistoryUiStateRegistry()

        val first = registry.stateFor(sessionIndex = 1, agentId = "shared")

        assertSame(first, registry.stateFor(sessionIndex = 1, agentId = "shared"))
        assertNotSame(first, registry.stateFor(sessionIndex = 1, agentId = "other"))
        assertNotSame(first, registry.stateFor(sessionIndex = 2, agentId = "shared"))
    }

    test("retaining topology removes closed Sessions and missing Agents") {
        val registry = AgentHistoryUiStateRegistry()
        val retained = registry.stateFor(sessionIndex = 1, agentId = "retained")
        val removedAgent = registry.stateFor(sessionIndex = 1, agentId = "removed")
        val removedSession = registry.stateFor(sessionIndex = 2, agentId = "retained")

        registry.retain(
            mapOf(
                1 to setOf("retained"),
                3 to setOf("new"),
            ),
        )

        assertSame(retained, registry.stateFor(sessionIndex = 1, agentId = "retained"))
        assertNotSame(removedAgent, registry.stateFor(sessionIndex = 1, agentId = "removed"))
        assertNotSame(removedSession, registry.stateFor(sessionIndex = 2, agentId = "retained"))
    }
}
