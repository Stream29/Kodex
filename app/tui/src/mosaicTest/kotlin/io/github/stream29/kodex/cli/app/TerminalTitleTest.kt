package io.github.stream29.kodex.cli.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.jakewharton.mosaic.testing.runMosaicTest
import com.jakewharton.mosaic.ui.Text
import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.app.session.contract.AgentTabViewModel
import kotlin.test.assertEquals

val terminalTitleTest by testSuite {
    test("summary counts open Agent tabs and their running states") {
        val fixture = SessionViewModelTestFixture.create(this)
        try {
            val first = fixture.persistedSession("First")
            val second = fixture.persistedSession("Overflow")
            val standalone = object : AgentTabViewModel by second {}
            val summary = summarizeOpenAgents(
                listOf(
                    SessionTabRenderState(
                        target = first,
                        selected = false,
                        sessionName = "First",
                        running = true,
                    ),
                    SessionTabRenderState(
                        target = standalone,
                        selected = false,
                        sessionName = "Overflow",
                    ),
                ),
            )

            assertEquals(
                OpenAgentSummary(agentCount = 2, runningAgentCount = 1),
                summary,
            )
            assertEquals(OpenAgentSummary(0, 0), summarizeOpenAgents(emptyList()))
        } finally {
            fixture.close()
        }
    }

    test("titleUsesTheFixedCountFormatAndOscZero") {
        assertEquals("1 agent (1 running)", terminalTitleText(agentCount = 1, runningCount = 1))
        assertEquals(
            "\u001B]0;2 agents (1 running)\u0007",
            terminalTitleControlSequence("2 agents (1 running)"),
        )
        assertEquals("\u001B]0;\u0007", terminalTitleControlSequence(""))
    }

    test("effectWritesOnlyChangedCountsAndExplicitCleanupClears") {
        val writes = mutableListOf<String>()
        var agentCount by mutableStateOf(2)
        var runningCount by mutableStateOf(1)
        var revision by mutableStateOf(0)

        runMosaicTest {
            setContentAndSnapshot {
                io.github.stream29.kodex.app.ui.WithMosaicComponentRenderers {
                TerminalTitleEffect(
                    agentCount = agentCount,
                    runningCount = runningCount,
                    write = { sequence -> writes += sequence },
                )
                Text(revision.toString())
            }
            }
            assertEquals(
                listOf("\u001B]0;2 agents (1 running)\u0007"),
                writes,
            )

            revision += 1
            awaitSnapshot()
            assertEquals(1, writes.size)

            runningCount = 2
            awaitSnapshot()
            assertEquals(
                listOf(
                    "\u001B]0;2 agents (1 running)\u0007",
                    "\u001B]0;2 agents (2 running)\u0007",
                ),
                writes,
            )
        }

        writeTerminalTitle("", writes::add)
        assertEquals("\u001B]0;\u0007", writes.last())
    }
}
