package io.github.stream29.kodex.cli.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.jakewharton.mosaic.testing.runMosaicTest
import com.jakewharton.mosaic.ui.Text
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class TerminalTitleTest {
    @Test
    fun summaryCountsOnlyOpenedPersistedSessionTabs() = runTest {
        val fixture = SessionViewModelTestFixture.create(this)
        try {
            val first = fixture.persistedSession("First")
            val draft = fixture.newSession("Draft")
            val second = fixture.persistedSession("Overflow")
            val summary = summarizeOpenSessions(
                listOf(
                    SessionTabRenderState(
                        target = first,
                        selected = false,
                        sessionName = "First",
                        running = true,
                    ),
                    SessionTabRenderState(
                        target = draft,
                        selected = true,
                        sessionName = "Draft",
                        running = true,
                    ),
                    SessionTabRenderState(
                        target = second,
                        selected = false,
                        sessionName = "Overflow",
                    ),
                ),
            )

            assertEquals(
                OpenSessionSummary(sessionCount = 2, runningSessionCount = 1),
                summary,
            )
        } finally {
            fixture.close()
        }
    }

    @Test
    fun titleUsesTheFixedCountFormatAndOscZero() {
        assertEquals("1 sessions (1 running)", terminalTitleText(sessionCount = 1, runningCount = 1))
        assertEquals(
            "\u001B]0;2 sessions (1 running)\u0007",
            terminalTitleControlSequence("2 sessions (1 running)"),
        )
        assertEquals("\u001B]0;\u0007", terminalTitleControlSequence(""))
    }

    @Test
    fun effectWritesOnlyChangedCountsAndExplicitCleanupClears() = runTest {
        val writes = mutableListOf<String>()
        var sessionCount by mutableStateOf(2)
        var runningCount by mutableStateOf(1)
        var revision by mutableStateOf(0)

        runMosaicTest {
            setContentAndSnapshot {
                TerminalTitleEffect(
                    sessionCount = sessionCount,
                    runningCount = runningCount,
                    write = { sequence -> writes += sequence },
                )
                Text(revision.toString())
            }
            assertEquals(
                listOf("\u001B]0;2 sessions (1 running)\u0007"),
                writes,
            )

            revision += 1
            awaitSnapshot()
            assertEquals(1, writes.size)

            runningCount = 2
            awaitSnapshot()
            assertEquals(
                listOf(
                    "\u001B]0;2 sessions (1 running)\u0007",
                    "\u001B]0;2 sessions (2 running)\u0007",
                ),
                writes,
            )
        }

        writeTerminalTitle("", writes::add)
        assertEquals("\u001B]0;\u0007", writes.last())
    }
}
