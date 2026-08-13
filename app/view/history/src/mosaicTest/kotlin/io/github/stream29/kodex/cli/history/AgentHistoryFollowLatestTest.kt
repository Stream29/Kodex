package io.github.stream29.kodex.cli.history

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.jakewharton.mosaic.layout.height
import com.jakewharton.mosaic.layout.width
import com.jakewharton.mosaic.modifier.Modifier
import com.jakewharton.mosaic.terminal.MouseEvent
import com.jakewharton.mosaic.testing.runMosaicTest
import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.cli.components.LazyColumn
import io.github.stream29.kodex.cli.components.ScrollInputSource
import io.github.stream29.kodex.cli.components.ScrollInteraction
import io.github.stream29.kodex.cli.components.ScrollOrientation
import io.github.stream29.kodex.cli.components.items
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

val agentHistoryFollowLatestTest by testSuite {
    test("viewport height changes keep an enabled state at latest") {
        val state = AgentHistoryUiState()
        var height by mutableIntStateOf(3)
        val entries = historyEntries("newest", "middle", "oldest")

        runMosaicTest {
            assertEquals(
                "oldest\nmiddle\nnewest",
                setContentAndSnapshot {
                    HistoryTestList(entries, state, width = 12, height = height)
                },
            )
            assertFalse(state.listState.canScrollForward)

            height = 2

            awaitSnapshot()
            assertEquals("middle\nnewest", awaitSnapshot())
            assertTrue(state.followsLatest)
            assertFalse(state.listState.canScrollForward)
        }
    }

    test("item reflow keeps an enabled state at latest") {
        val state = AgentHistoryUiState()
        var width by mutableIntStateOf(10)
        val entries = historyEntries("abcdefghij", "older")

        runMosaicTest {
            assertEquals(
                "older\nabcdefghij",
                setContentAndSnapshot {
                    HistoryTestList(entries, state, width = width, height = 2)
                },
            )

            width = 4

            awaitSnapshot()
            assertEquals("efgh\nij", awaitSnapshot())
            assertTrue(state.followsLatest)
            assertFalse(state.listState.canScrollForward)
        }
    }

    test("content updates follow latest only while the intent is enabled") {
        val state = AgentHistoryUiState()
        var entries by mutableStateOf(historyEntries("newest", "middle", "oldest"))

        runMosaicTest {
            assertEquals(
                "middle\nnewest",
                setContentAndSnapshot {
                    HistoryTestList(entries, state, width = 12, height = 2)
                },
            )

            state.requestLatestForContentChange()
            entries = listOf(HistoryTestEntry("newest-2", "newest-2")) + entries

            assertEquals("newest\nnewest-2", awaitSnapshot())
            assertTrue(state.followsLatest)

            sendMouseEvent(
                MouseEvent(0, 0, MouseEvent.Type.Press, MouseEvent.Button.WheelUp),
            )
            assertEquals("oldest\nmiddle", awaitSnapshot())
            assertFalse(state.followsLatest)

            state.requestLatestForContentChange()
            entries = listOf(HistoryTestEntry("newest-3", "newest-3")) + entries

            assertEquals("oldest\nmiddle", awaitSnapshot())
            assertFalse(state.followsLatest)

            sendMouseEvent(
                MouseEvent(0, 0, MouseEvent.Type.Press, MouseEvent.Button.WheelDown),
            )
            awaitSnapshot()
            sendMouseEvent(
                MouseEvent(0, 0, MouseEvent.Type.Press, MouseEvent.Button.WheelDown),
            )
            assertEquals("newest-2\nnewest-3", awaitSnapshot())
            assertTrue(state.followsLatest)
            assertFalse(state.listState.canScrollForward)
        }
    }

    test("streaming item growth follows latest and preserves a paused anchor") {
        val state = AgentHistoryUiState()
        var entries by mutableStateOf(
            listOf(
                HistoryTestEntry(id = "stream", text = "abcde"),
                HistoryTestEntry(id = "older", text = "older"),
            ),
        )

        runMosaicTest {
            assertEquals(
                "older\nabcde",
                setContentAndSnapshot {
                    HistoryTestList(entries, state, width = 5, height = 2)
                },
            )

            state.requestLatestForContentChange()
            entries = entries.map { entry ->
                if (entry.id == "stream") entry.copy(text = "aaaaabbbbbccccc") else entry
            }

            assertEquals("bbbbb\nccccc", awaitSnapshot())
            commitScroll(state, ScrollInputSource.Pointer, delta = -1)
            assertEquals("aaaaa\nbbbbb", awaitSnapshot())
            assertFalse(state.followsLatest)

            state.requestLatestForContentChange()
            entries = entries.map { entry ->
                if (entry.id == "stream") entry.copy(text = "aaaaabbbbbcccccddddd") else entry
            }

            assertEquals("aaaaa\nbbbbb", awaitSnapshot())
            assertFalse(state.followsLatest)
        }
    }

    test("only committed pointer and keyboard movement changes follow intent") {
        val state = AgentHistoryUiState()
        val entries = historyEntries("newest", "middle", "oldest")

        runMosaicTest {
            setContentAndSnapshot {
                HistoryTestList(entries, state, width = 12, height = 2)
            }

            state.interactionSource.tryEmit(
                ScrollInteraction(
                    source = ScrollInputSource.Pointer,
                    orientation = ScrollOrientation.Vertical,
                    requestedDelta = 1,
                    consumedDelta = 0,
                ),
            )
            assertTrue(state.followsLatest)

            commitScroll(state, ScrollInputSource.FocusRelocation, delta = -1)
            assertTrue(state.followsLatest)
            awaitSnapshot()
            assertEquals("middle\nnewest", awaitSnapshot())

            commitScroll(state, ScrollInputSource.Programmatic, delta = -1)
            assertTrue(state.followsLatest)
            awaitSnapshot()
            assertEquals("middle\nnewest", awaitSnapshot())

            commitScroll(state, ScrollInputSource.Pointer, delta = -1)
            assertFalse(state.followsLatest)
            assertEquals("oldest\nmiddle", awaitSnapshot())

            commitScroll(state, ScrollInputSource.Keyboard, delta = 1)
            assertTrue(state.followsLatest)
            assertEquals("middle\nnewest", awaitSnapshot())
        }
    }

    test("a paused state resumes when layout passively reaches latest") {
        val state = AgentHistoryUiState()
        var height by mutableIntStateOf(2)
        val entries = historyEntries("newest", "middle", "oldest")

        runMosaicTest {
            setContentAndSnapshot {
                HistoryTestList(entries, state, width = 12, height = height)
            }
            commitScroll(state, ScrollInputSource.Pointer, delta = -1)
            assertEquals("oldest\nmiddle", awaitSnapshot())
            assertFalse(state.followsLatest)

            height = 3

            assertEquals("oldest\nmiddle\nnewest", awaitSnapshot())
            height = 4
            awaitSnapshot()
            assertTrue(state.followsLatest)
            assertFalse(state.listState.canScrollForward)
        }
    }
}

private fun commitScroll(
    state: AgentHistoryUiState,
    source: ScrollInputSource,
    delta: Int,
) {
    val consumed = state.listState.scrollBy(delta)
    assertEquals(delta, consumed)
    state.interactionSource.tryEmit(
        ScrollInteraction(
            source = source,
            orientation = ScrollOrientation.Vertical,
            requestedDelta = delta,
            consumedDelta = consumed,
        ),
    )
}

@Composable
private fun HistoryTestList(
    entries: List<HistoryTestEntry>,
    state: AgentHistoryUiState,
    width: Int,
    height: Int,
) {
    HistoryFollowLatestEffect(state)
    LazyColumn(
        modifier = Modifier.width(width).height(height),
        state = state.listState,
        reverseLayout = true,
        interactionSource = state.interactionSource,
    ) {
        items(entries, key = HistoryTestEntry::id) { entry ->
            WrappedHistoryText(entry.text)
        }
    }
}

private fun historyEntries(vararg values: String): List<HistoryTestEntry> =
    values.map { value -> HistoryTestEntry(id = value, text = value) }

private data class HistoryTestEntry(
    val id: String,
    val text: String,
)
