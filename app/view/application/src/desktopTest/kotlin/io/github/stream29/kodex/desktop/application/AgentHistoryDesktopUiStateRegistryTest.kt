package io.github.stream29.kodex.desktop.application

import kotlin.test.Test
import kotlin.test.assertNotSame
import kotlin.test.assertSame

public class AgentHistoryDesktopUiStateRegistryTest {
    @Test
    public fun stateIsScopedBySessionAndAgent(): Unit {
        val registry = AgentHistoryDesktopUiStateRegistry()
        val first = registry.stateFor(sessionIndex = 1, agentId = "shared")

        assertSame(first, registry.stateFor(sessionIndex = 1, agentId = "shared"))
        assertNotSame(first, registry.stateFor(sessionIndex = 1, agentId = "other"))
        assertNotSame(first, registry.stateFor(sessionIndex = 2, agentId = "shared"))
    }

    @Test
    public fun retainDropsRemovedSessionsAndAgents(): Unit {
        val registry = AgentHistoryDesktopUiStateRegistry()
        val retained = registry.stateFor(sessionIndex = 1, agentId = "retained")
        val removedAgent = registry.stateFor(sessionIndex = 1, agentId = "removed")
        val removedSession = registry.stateFor(sessionIndex = 2, agentId = "retained")

        registry.retain(
            mapOf(
                1 to setOf("retained"),
            ),
        )

        assertSame(retained, registry.stateFor(sessionIndex = 1, agentId = "retained"))
        assertNotSame(
            removedAgent,
            registry.stateFor(sessionIndex = 1, agentId = "removed"),
        )
        assertNotSame(
            removedSession,
            registry.stateFor(sessionIndex = 2, agentId = "retained"),
        )
    }
}
