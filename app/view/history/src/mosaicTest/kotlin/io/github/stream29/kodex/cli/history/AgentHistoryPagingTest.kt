package io.github.stream29.kodex.cli.history

import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import com.jakewharton.mosaic.focus.FocusRequester
import com.jakewharton.mosaic.focus.focusable
import com.jakewharton.mosaic.layout.height
import com.jakewharton.mosaic.layout.width
import com.jakewharton.mosaic.modifier.Modifier
import com.jakewharton.mosaic.terminal.KeyboardEvent
import com.jakewharton.mosaic.testing.runMosaicTest
import com.jakewharton.mosaic.ui.Column
import com.jakewharton.mosaic.ui.Text
import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.cli.components.LazyColumn
import io.github.stream29.kodex.cli.components.LazyListItemInfo
import io.github.stream29.kodex.cli.components.LazyListLayoutInfo
import io.github.stream29.kodex.cli.components.LazyListState
import io.github.stream29.kodex.cli.components.MutableScrollInteractionSource
import io.github.stream29.kodex.cli.components.TuiPressable
import io.github.stream29.kodex.cli.components.items
import io.github.stream29.kodex.app.history.contract.HistoryItemViewModel
import kotlin.test.assertEquals
import kotlin.test.assertTrue

val agentHistoryPagingTest by testSuite {
    test("page focus selection skips partially visible edge entries") {
        val topPartial = storedKey(0)
        val firstComplete = storedKey(1)
        val lastComplete = storedKey(2)
        val bottomPartial = storedKey(3)
        val layoutInfo = LazyListLayoutInfo(
            visibleItemsInfo = listOf(
                LazyListItemInfo(index = 0, key = topPartial, offset = -1, size = 2),
                LazyListItemInfo(index = 1, key = firstComplete, offset = 1, size = 2),
                LazyListItemInfo(index = 2, key = lastComplete, offset = 3, size = 2),
                LazyListItemInfo(index = 3, key = bottomPartial, offset = 5, size = 2),
            ),
            viewportStartOffset = 0,
            viewportEndOffset = 6,
            totalItemsCount = 4,
        )

        assertEquals(firstComplete, layoutInfo.historyPageFocusItem(towardTop = true))
        assertEquals(lastComplete, layoutInfo.historyPageFocusItem(towardTop = false))
    }

    test("paging moves half a viewport and refocuses its visual edge") {
        val listState = LazyListState()
        val interactionSource = MutableScrollInteractionSource()
        val historyItems = List(12) { index -> storedKey(index) }
        val focusRequesters = mutableMapOf<HistoryItemViewModel, FocusRequester>()

        runMosaicTest {
            setContentAndSnapshot {
                Column {
                    HistoryPagingFocusEffect(
                        listState = listState,
                        interactionSource = interactionSource,
                        entryFocusRequesters = focusRequesters,
                    )
                    LazyColumn(
                        modifier = Modifier.width(8).height(6),
                        state = listState,
                        reverseLayout = true,
                        interactionSource = interactionSource,
                        keyboardPageSize = { viewportSize ->
                            (viewportSize / 2).coerceAtLeast(1)
                        },
                    ) {
                        items(
                            items = historyItems,
                            key = { item -> item },
                        ) { key ->
                            val index = (key as HistoryItemViewModel.Message).index
                            val focusRequester = remember(key) { FocusRequester() }
                            DisposableEffect(key, focusRequester) {
                                focusRequesters[key] = focusRequester
                                onDispose {
                                    if (focusRequesters[key] === focusRequester) {
                                        focusRequesters.remove(key)
                                    }
                                }
                            }
                            TuiPressable(
                                onClick = {},
                                focusRequester = focusRequester,
                                autoFocus = index == 0,
                            ) { isFocused, _, _ ->
                                Text(if (isFocused) "[$index]" else " $index ")
                            }
                        }
                    }
                    Text(
                        value = "composer",
                        modifier = Modifier.focusable(),
                    )
                }
            }

            val initiallyFocused = awaitSnapshot()
            assertTrue(
                actual = "[0]" in initiallyFocused,
                message = "Expected newest entry focus before paging:\n$initiallyFocused",
            )

            sendKeyEvent(KeyboardEvent(KeyboardEvent.PageUp))
            awaitSnapshot()
            val olderPage = awaitSnapshot()
            assertEquals(8, listState.firstVisibleItemIndex)
            assertTrue(
                actual = "[8]" in olderPage,
                message = "Expected top complete entry focus after PageUp:\n$olderPage",
            )

            sendKeyEvent(KeyboardEvent(KeyboardEvent.PageDown))
            awaitSnapshot()
            val latestPage = awaitSnapshot()
            assertEquals(5, listState.firstVisibleItemIndex)
            assertTrue(
                actual = "[0]" in latestPage,
                message = "Expected bottom complete entry focus after PageDown:\n$latestPage",
            )
        }
    }
}

private fun storedKey(index: Int): HistoryItemViewModel =
    HistoryItemViewModel.Message(index)
