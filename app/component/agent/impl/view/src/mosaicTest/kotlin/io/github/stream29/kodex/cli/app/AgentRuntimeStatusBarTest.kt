package io.github.stream29.kodex.cli.app

import de.infix.testBalloon.framework.core.testSuite
import com.jakewharton.mosaic.terminal.MouseEvent
import com.jakewharton.mosaic.testing.runMosaicTest
import io.github.stream29.kodex.app.agent.contract.AgentViewModel
import io.github.stream29.kodex.app.test.startRpcFrontendFixture
import io.github.stream29.kodex.agentstorage.cleanmodels.unstable.PendingRequestUserInputToolEvent
import io.github.stream29.kodex.cli.runtimeconfiguration.RuntimeConfigurationDropdowns
import io.github.stream29.kodex.rpc.models.AgentStateValue
import io.github.stream29.kodex.tool.requestuserinput.RequestUserInputArgs
import io.github.stream29.kodex.tool.requestuserinput.RequestUserInputQuestion
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

val agentRuntimeStatusBarTest by testSuite {
    test("runningAgentHidesOnlyCompact") {
        assertTrue(compactVisible(false))
        assertFalse(compactVisible(true))
    }

    test("Agent status controls preserve exact callbacks counters and wrapped row measurements") {
        val fixture = startRpcFrontendFixture(this)
        try {
            val real = requireNotNull(fixture.create().rootAgent.value)
            val commands = mutableListOf<String>()
            // Only commands are recorded: configuration and all child observations remain real.
            val agent = object : AgentViewModel by real {
                override fun resume() { commands += "resume" }
                override fun cancel() { commands += "stop" }
                override fun clearPending() { commands += "clear" }
                override fun forceCompact() { commands += "compact" }
            }
            val settings = agent.settings.value
            val pending = AgentStateValue.ToolPending(listOf(PendingRequestUserInputToolEvent(
                "question", arguments = RequestUserInputArgs(listOf(RequestUserInputQuestion("id", "header", "question"))),
            )))
            listOf(
                Triple(AgentStateValue.UserMessage, false, listOf("Resume" to "resume", "Compact" to "compact")),
                Triple(AgentStateValue.Compacting, true, listOf("Stop" to "stop")),
                Triple(pending, false, listOf("Clear pending" to "clear")),
            ).forEach { (state, running, actions) ->
                listOf(40, 60, 80, 120).forEach { columns ->
                    runMosaicTest {
                        val snapshot = setContentAndSnapshot {
                            AgentRuntimeStatusBar(
                                columns, agent, state, running, settings, 123L,
                                RuntimeConfigurationDropdowns.remember(agent), {}, {},
                            )
                        }
                        assertTrue("123t" in snapshot, snapshot)
                        assertEquals(
                            agentRuntimeStatusBarRows(columns, state, running, settings, 123L),
                            snapshot.lines().size,
                            snapshot,
                        )
                        assertTrue(snapshot.lines().all { it.length <= columns - 1 }, snapshot)
                        assertTrue(snapshot.lines().first().endsWith("[Settings]"), snapshot)
                        if (running) assertFalse("[Compact]" in snapshot, snapshot)
                        actions.forEach { (label, command) ->
                            val row = snapshot.lines().indexOfFirst { "[$label]" in it }
                            assertTrue(row >= 0, snapshot)
                            val column = snapshot.lines()[row].indexOf("[$label]") + 1
                            val before = commands.size
                            sendMouseEvent(MouseEvent(column, row, MouseEvent.Type.Press, MouseEvent.Button.Left))
                            awaitSnapshot()
                            sendMouseEvent(MouseEvent(column, row, MouseEvent.Type.Release))
                            awaitSnapshot()
                            assertEquals(before + 1, commands.size)
                            assertEquals(command, commands.last())
                        }
                    }
                }
            }
        } finally { fixture.closeAndJoin() }
    }
}
