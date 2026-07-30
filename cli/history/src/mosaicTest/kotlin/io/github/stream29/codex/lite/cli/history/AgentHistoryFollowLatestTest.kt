package io.github.stream29.codex.lite.cli.history

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.jakewharton.mosaic.layout.height
import com.jakewharton.mosaic.modifier.Modifier
import com.jakewharton.mosaic.testing.runMosaicTest
import com.jakewharton.mosaic.ui.Text
import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.codex.lite.cli.components.LazyColumn
import io.github.stream29.codex.lite.cli.components.LazyListState
import io.github.stream29.codex.lite.cli.components.items
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

val agentHistoryFollowLatestTest by testSuite {
    test("content update stays at latest when viewport was already at latest") {
        val state = LazyListState()
        var entries by mutableStateOf(listOf("newest", "middle", "oldest"))

        runMosaicTest {
            assertEquals(
                "middle\nnewest",
                setContentAndSnapshot {
                    HistoryTestList(entries, state)
                },
            )
            assertFalse(state.canScrollForward)

            state.requestLatestIfAtLatest()
            entries = listOf("newest-2") + entries

            assertEquals("newest\nnewest-2", awaitSnapshot())
            assertFalse(state.canScrollForward)
        }
    }

    test("content update keeps viewport when user has left latest") {
        val state = LazyListState()
        var entries by mutableStateOf(listOf("newest", "middle", "oldest"))

        runMosaicTest {
            setContentAndSnapshot {
                HistoryTestList(entries, state)
            }
            assertEquals(-1, state.scrollBy(-1))
            assertEquals("oldest\nmiddle", awaitSnapshot())
            assertTrue(state.canScrollForward)

            state.requestLatestIfAtLatest()
            entries = listOf("newest-2") + entries

            assertEquals("oldest\nmiddle", awaitSnapshot())
            assertTrue(state.canScrollForward)
        }
    }
}

@Composable
private fun HistoryTestList(
    entries: List<String>,
    state: LazyListState,
) {
    LazyColumn(
        modifier = Modifier.height(2),
        state = state,
        reverseLayout = true,
    ) {
        items(entries, key = { entry -> entry }) { entry ->
            Text(entry)
        }
    }
}
