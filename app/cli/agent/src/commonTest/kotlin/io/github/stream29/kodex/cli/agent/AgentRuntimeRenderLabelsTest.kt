package io.github.stream29.kodex.cli.agent

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class AgentRuntimeRenderLabelsTest {
    @Test
    fun zeroActiveShellSessionsPreserveThePrimaryStatus() {
        assertNull(activeShellSessionLabel(0))
        assertEquals("Idle", AgentRuntimeRenderState.Idle.label(0))
    }

    @Test
    fun activeShellSessionsAreRenderedAsOrthogonalActivity() {
        assertEquals("1 shell session", activeShellSessionLabel(1))
        assertEquals("2 shell sessions", activeShellSessionLabel(2))
        assertEquals(
            "Reasoning · 1 shell session",
            AgentRuntimeRenderState.Streaming(AgentStreamKind.Reasoning).label(1),
        )
        assertEquals(
            "Idle · 2 shell sessions",
            AgentRuntimeRenderState.Idle.label(2),
        )
    }

    @Test
    fun negativeActiveShellSessionCountsAreRejected() {
        assertFailsWith<IllegalArgumentException> {
            AgentRuntimeRenderState.Idle.label(-1)
        }
    }
}
