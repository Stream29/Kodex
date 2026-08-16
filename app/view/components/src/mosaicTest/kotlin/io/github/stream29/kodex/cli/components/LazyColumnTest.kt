package io.github.stream29.kodex.cli.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.jakewharton.mosaic.focus.FocusState
import com.jakewharton.mosaic.focus.focusable
import com.jakewharton.mosaic.focus.onFocusChanged
import com.jakewharton.mosaic.layout.MeasurePolicy
import com.jakewharton.mosaic.layout.height
import com.jakewharton.mosaic.layout.width
import com.jakewharton.mosaic.modifier.Modifier
import com.jakewharton.mosaic.terminal.MouseEvent
import com.jakewharton.mosaic.terminal.KeyboardEvent
import com.jakewharton.mosaic.terminal.KeyboardEvent.Companion.ModifierShift
import com.jakewharton.mosaic.testing.runMosaicTest
import com.jakewharton.mosaic.ui.Column
import com.jakewharton.mosaic.ui.Layout
import com.jakewharton.mosaic.ui.Text
import com.jakewharton.mosaic.ui.unit.Constraints
import com.jakewharton.mosaic.ui.unit.constrainWidth
import de.infix.testBalloon.framework.core.testSuite
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

val lazyColumnTest by testSuite {
    test("variable-height items may be partially visible") {
        val state = LazyListState(initialFirstVisibleItemScrollOffset = 1)
        val items = listOf(
            TestItem("A", 2),
            TestItem("B", 1),
            TestItem("C", 2),
            TestItem("D", 1),
        )

        runMosaicTest {
            assertEquals(
                "A1\nB0\nC0",
                setContentAndSnapshot {
                    TestLazyColumn(items, state, viewportHeight = 3)
                },
            )
        }

        assertEquals(0, state.firstVisibleItemIndex)
        assertEquals(1, state.firstVisibleItemScrollOffset)
        assertEquals(
            listOf(
                LazyListItemInfo(index = 0, key = "A", offset = -1, size = 2),
                LazyListItemInfo(index = 1, key = "B", offset = 1, size = 1),
                LazyListItemInfo(index = 2, key = "C", offset = 2, size = 2),
            ),
            state.layoutInfo.visibleItemsInfo,
        )
    }

    test("row scrolling crosses item boundaries") {
        val state = LazyListState(initialFirstVisibleItemScrollOffset = 1)
        val items = listOf(
            TestItem("A", 2),
            TestItem("B", 1),
            TestItem("C", 2),
            TestItem("D", 2),
        )

        runMosaicTest {
            setContentAndSnapshot {
                TestLazyColumn(items, state, viewportHeight = 3)
            }

            assertEquals(2, state.scrollBy(2))
            assertEquals("C0\nC1\nD0", awaitSnapshot())
        }

        assertEquals(2, state.firstVisibleItemIndex)
        assertEquals(0, state.firstVisibleItemScrollOffset)
    }

    test("stable key preserves the viewport through prepend and reorder") {
        val state = LazyListState(initialFirstVisibleItemIndex = 1)
        var items by mutableStateOf(
            listOf(
                TestItem("A", 1),
                TestItem("B", 1),
                TestItem("C", 1),
                TestItem("D", 1),
            )
        )

        runMosaicTest {
            assertEquals(
                "B0\nC0",
                setContentAndSnapshot {
                    TestLazyColumn(items, state, viewportHeight = 2)
                },
            )
            assertEquals(1, state.firstVisibleItemIndex)
            assertEquals(listOf("B", "C"), state.layoutInfo.visibleItemsInfo.map { item -> item.key })

            items = listOf(TestItem("X", 1)) + items
            val prependedSnapshot = awaitSnapshot()
            assertEquals(listOf("B", "C"), state.layoutInfo.visibleItemsInfo.map { item -> item.key })
            assertEquals("B0\nC0", prependedSnapshot)
            assertEquals(2, state.firstVisibleItemIndex)

            items = listOf(items[0], items[1], items[3], items[2], items[4])
            assertEquals("B0\nD0", awaitSnapshot())
            assertEquals(3, state.firstVisibleItemIndex)
        }
    }

    test("removed anchor falls back to its clamped former index") {
        val state = LazyListState(initialFirstVisibleItemIndex = 2)
        var items by mutableStateOf(
            listOf(
                TestItem("A", 1),
                TestItem("B", 1),
                TestItem("C", 1),
                TestItem("D", 1),
                TestItem("E", 1),
            )
        )

        runMosaicTest {
            assertEquals(
                "C0\nD0",
                setContentAndSnapshot {
                    TestLazyColumn(items, state, viewportHeight = 2)
                },
            )

            items = items.filterNot { item -> item.key == "C" }
            assertEquals("D0\nE0", awaitSnapshot())
        }

        assertEquals(2, state.firstVisibleItemIndex)
    }

    test("reverse layout places logical item zero at the visual bottom") {
        val state = LazyListState()
        val items = listOf(
            TestItem("A", 1),
            TestItem("B", 1),
            TestItem("C", 1),
            TestItem("D", 1),
            TestItem("E", 1),
        )

        runMosaicTest {
            assertEquals(
                "C0\nB0\nA0",
                setContentAndSnapshot {
                    TestLazyColumn(
                        items = items,
                        state = state,
                        viewportHeight = 3,
                        reverseLayout = true,
                    )
                },
            )
        }

        assertEquals(2, state.firstVisibleItemIndex)
        assertEquals(
            listOf(
                LazyListItemInfo(index = 2, key = "C", offset = 0, size = 1),
                LazyListItemInfo(index = 1, key = "B", offset = 1, size = 1),
                LazyListItemInfo(index = 0, key = "A", offset = 2, size = 1),
            ),
            state.layoutInfo.visibleItemsInfo,
        )
        assertTrue(state.canScrollBackward)
        assertFalse(state.canScrollForward)
    }

    test("reverse layout honors an explicit initial logical index") {
        val state = LazyListState(initialFirstVisibleItemIndex = 3)

        runMosaicTest {
            assertEquals(
                "D0\nC0",
                setContentAndSnapshot {
                    TestLazyColumn(
                        items = listOf(
                            TestItem("A", 1),
                            TestItem("B", 1),
                            TestItem("C", 1),
                            TestItem("D", 1),
                            TestItem("E", 1),
                        ),
                        state = state,
                        viewportHeight = 2,
                        reverseLayout = true,
                    )
                },
            )
        }

        assertEquals(3, state.firstVisibleItemIndex)
    }

    test("reverse layout bottom-aligns content shorter than the viewport") {
        runMosaicTest {
            assertEquals(
                "\n\nB0\nA0",
                setContentAndSnapshot {
                    TestLazyColumn(
                        items = listOf(TestItem("A", 1), TestItem("B", 1)),
                        state = LazyListState(),
                        viewportHeight = 4,
                        reverseLayout = true,
                    )
                },
            )
        }
    }

    test("reverse layout stable key preserves the viewport when logical indexes shift") {
        val state = LazyListState()
        var revision by mutableIntStateOf(0)
        var items by mutableStateOf(
            listOf(
                TestItem("A", 1),
                TestItem("B", 1),
                TestItem("C", 1),
                TestItem("D", 1),
                TestItem("E", 1),
            )
        )

        runMosaicTest {
            assertEquals(
                "B0\nA0\n0",
                setContentAndSnapshot {
                    Column {
                        TestLazyColumn(
                            items = items,
                            state = state,
                            viewportHeight = 2,
                            reverseLayout = true,
                        )
                        Text(revision.toString())
                    }
                },
            )

            assertEquals(-2, state.scrollBy(-2))
            revision++
            assertEquals("D0\nC0\n1", awaitSnapshot())

            items = listOf(TestItem("X", 1)) + items
            revision++
            assertEquals("D0\nC0\n2", awaitSnapshot())
            assertEquals(listOf("D", "C"), state.layoutInfo.visibleItemsInfo.map { item -> item.key })
            assertEquals(4, state.firstVisibleItemIndex)
        }
    }

    test("end positioning backfills a full viewport") {
        val state = LazyListState()
        state.requestScrollToEnd()

        runMosaicTest {
            assertEquals(
                "B1\nC0\nD0",
                setContentAndSnapshot {
                    TestLazyColumn(
                        items = listOf(
                            TestItem("A", 1),
                            TestItem("B", 2),
                            TestItem("C", 1),
                            TestItem("D", 1),
                        ),
                        state = state,
                        viewportHeight = 3,
                    )
                },
            )
        }

        assertEquals(1, state.firstVisibleItemIndex)
        assertEquals(1, state.firstVisibleItemScrollOffset)
        assertFalse(state.canScrollForward)
        assertTrue(state.canScrollBackward)
    }

    test("explicit item request overrides stable-key restoration") {
        val state = LazyListState(initialFirstVisibleItemIndex = 1)
        val items = listOf(
            TestItem("A", 1),
            TestItem("B", 1),
            TestItem("C", 2),
            TestItem("D", 1),
        )

        runMosaicTest {
            setContentAndSnapshot {
                TestLazyColumn(items, state, viewportHeight = 2)
            }

            state.scrollToItem(index = 2, scrollOffset = 1)
            assertEquals("C1\nD0", awaitSnapshot())
        }

        assertEquals(2, state.firstVisibleItemIndex)
        assertEquals(1, state.firstVisibleItemScrollOffset)
    }

    test("height shrink normalizes the old row offset and tail range") {
        val state = LazyListState(initialFirstVisibleItemScrollOffset = 3)
        var firstHeight by mutableStateOf(4)

        runMosaicTest {
            assertEquals(
                "A3\nB0",
                setContentAndSnapshot {
                    TestLazyColumn(
                        items = listOf(
                            TestItem("A", firstHeight),
                            TestItem("B", 1),
                            TestItem("C", 1),
                        ),
                        state = state,
                        viewportHeight = 2,
                    )
                },
            )

            firstHeight = 1
            assertEquals("B0\nC0", awaitSnapshot())
        }

        assertEquals(1, state.firstVisibleItemIndex)
        assertEquals(0, state.firstVisibleItemScrollOffset)
    }

    test("viewport resize keeps the same stable anchor") {
        val state = LazyListState(initialFirstVisibleItemIndex = 2)
        var viewportHeight by mutableIntStateOf(2)
        val items = List(6) { index -> TestItem(('A'.code + index).toChar().toString(), 1) }

        runMosaicTest {
            assertEquals(
                "C0\nD0",
                setContentAndSnapshot {
                    TestLazyColumn(items, state, viewportHeight)
                },
            )

            viewportHeight = 4
            assertEquals("C0\nD0\nE0\nF0", awaitSnapshot())
            assertEquals(2, state.firstVisibleItemIndex)

            viewportHeight = 1
            assertEquals("C0", awaitSnapshot())
            assertEquals(2, state.firstVisibleItemIndex)
        }
    }

    test("width-dependent item height is remeasured and offset is normalized") {
        val state = LazyListState(initialFirstVisibleItemScrollOffset = 1)
        var viewportWidth by mutableIntStateOf(3)

        runMosaicTest {
            setContentAndSnapshot {
                LazyColumn(
                    modifier = Modifier.width(viewportWidth).height(2),
                    state = state,
                ) {
                    item(key = "A") { WidthSensitiveItem("A") }
                    item(key = "B") { Text("B") }
                    item(key = "C") { Text("C") }
                }
            }
            assertEquals(
                LazyListItemInfo(index = 0, key = "A", offset = -1, size = 2),
                state.layoutInfo.visibleItemsInfo.first(),
            )

            viewportWidth = 6
            awaitSnapshot()
            assertEquals(1, state.firstVisibleItemIndex)
            assertEquals(0, state.firstVisibleItemScrollOffset)
            assertEquals(listOf("B", "C"), state.layoutInfo.visibleItemsInfo.map { item -> item.key })
        }
    }

    test("empty and shrinking content publish normalized bounds") {
        val state = LazyListState()
        var items by mutableStateOf(emptyList<TestItem>())

        runMosaicTest {
            setContentAndSnapshot {
                TestLazyColumn(items, state, viewportHeight = 2)
            }
            assertEquals(0, state.layoutInfo.totalItemsCount)
            assertFalse(state.canScrollBackward)
            assertFalse(state.canScrollForward)

            items = listOf(TestItem("A", 1), TestItem("B", 1), TestItem("C", 1))
            assertEquals("A0\nB0", awaitSnapshot())
            assertTrue(state.canScrollForward)

            state.requestScrollToEnd()
            assertEquals("B0\nC0", awaitSnapshot())
            items = listOf(TestItem("Z", 1))
            assertEquals("Z0\n", awaitSnapshot())
        }

        assertEquals(0, state.firstVisibleItemIndex)
        assertEquals(0, state.firstVisibleItemScrollOffset)
        assertFalse(state.canScrollBackward)
        assertFalse(state.canScrollForward)
    }

    test("wheel input uses lazy row geometry") {
        val state = LazyListState()
        val items = List(8) { index -> TestItem(index.toString(), 1) }

        runMosaicTest {
            setContentAndSnapshot {
                TestLazyColumn(items, state, viewportHeight = 3)
            }

            sendMouseEvent(MouseEvent(0, 0, MouseEvent.Type.Press, MouseEvent.Button.WheelDown))
            assertEquals("30\n40\n50", awaitSnapshot())
        }

        assertEquals(3, state.firstVisibleItemIndex)
    }

    test("wheel input round trips from the logical end") {
        val state = LazyListState().apply { requestScrollToEnd() }
        val items = List(8) { index -> TestItem(index.toString(), 1) }

        runMosaicTest {
            assertEquals(
                "50\n60\n70",
                setContentAndSnapshot {
                    TestLazyColumn(items, state, viewportHeight = 3)
                },
            )

            sendMouseEvent(MouseEvent(0, 0, MouseEvent.Type.Press, MouseEvent.Button.WheelUp))
            assertEquals("20\n30\n40", awaitSnapshot())
            sendMouseEvent(MouseEvent(0, 0, MouseEvent.Type.Press, MouseEvent.Button.WheelDown))
            assertEquals("50\n60\n70", awaitSnapshot())
        }

        assertEquals(5, state.firstVisibleItemIndex)
    }

    test("pointer capture survives lazy item recomposition") {
        var expanded by mutableStateOf(false)

        runMosaicTest {
            setContentAndSnapshot {
                LazyColumn(Modifier.width(20).height(4)) {
                    item(key = "item") {
                        TuiPressable(onClick = { expanded = !expanded }) { _, _, isPressed ->
                            Text(
                                when {
                                    isPressed -> "* item"
                                    expanded -> "v item"
                                    else -> "> item"
                                }
                            )
                        }
                        if (expanded) Text("details")
                    }
                }
            }

            sendMouseEvent(MouseEvent(1, 0, MouseEvent.Type.Press, MouseEvent.Button.Left))
            assertEquals("* item\n\n\n", awaitSnapshot())
            sendMouseEvent(MouseEvent(1, 0, MouseEvent.Type.Release))
            assertEquals("v item\ndetails\n\n", awaitSnapshot())

            sendMouseEvent(MouseEvent(1, 0, MouseEvent.Type.Press, MouseEvent.Button.Left))
            assertEquals("* item\ndetails\n\n", awaitSnapshot())
            sendMouseEvent(MouseEvent(1, 0, MouseEvent.Type.Release))
            assertEquals("> item\n\n\n", awaitSnapshot())
        }
    }

    test("multiple top-level composables form one lazy item") {
        runMosaicTest {
            assertEquals(
                "A\nB\nC",
                setContentAndSnapshot {
                    LazyColumn(Modifier.height(3)) {
                        item(key = "first") {
                            Text("A")
                            Text("B")
                        }
                        item(key = "second") {
                            Text("C")
                        }
                    }
                },
            )
        }
    }

    test("duplicate stable key is rejected before item composition") {
        runMosaicTest {
            assertFailsWith<IllegalArgumentException> {
                setContentAndSnapshot {
                    LazyColumn(Modifier.height(2)) {
                        item(key = "same") { Text("A") }
                        item(key = "same") { Text("B") }
                    }
                }
            }
        }
    }

    test("an unbounded vertical axis is rejected") {
        runMosaicTest {
            assertFailsWith<IllegalStateException> {
                setContentAndSnapshot {
                    Column {
                        LazyColumn {
                            item { Text("content") }
                        }
                    }
                }
            }
        }
    }

    test("large data sets compose only a bounded viewport window") {
        val composedKeys = mutableSetOf<Int>()
        var calculatedKeys = 0

        runMosaicTest {
            assertEquals(
                "0\n1\n2\n3",
                setContentAndSnapshot {
                    LazyColumn(Modifier.height(4)) {
                        items(
                            count = 10_000,
                            key = { index ->
                                calculatedKeys++
                                index
                            },
                        ) { index ->
                            composedKeys += index
                            Text(index.toString())
                        }
                    }
                },
            )
        }

        assertTrue(composedKeys.size < 32, "Composed ${composedKeys.size} of 10,000 items.")
        assertTrue(calculatedKeys < 300, "Calculated $calculatedKeys of 10,000 item keys.")
    }

    test("reverse layout composes only its newest bounded viewport window") {
        val composedKeys = mutableSetOf<Int>()
        var calculatedKeys = 0

        runMosaicTest {
            assertEquals(
                "3\n2\n1\n0",
                setContentAndSnapshot {
                    LazyColumn(
                        modifier = Modifier.height(4),
                        reverseLayout = true,
                    ) {
                        items(
                            count = 10_000,
                            key = { index ->
                                calculatedKeys++
                                index
                            },
                        ) { index ->
                            composedKeys += index
                            Text(index.toString())
                        }
                    }
                },
            )
        }

        assertTrue(composedKeys.size < 32, "Composed ${composedKeys.size} of 10,000 items.")
        assertTrue(calculatedKeys < 300, "Calculated $calculatedKeys of 10,000 item keys.")
    }

    test("remembered item identity follows a stable key") {
        val state = LazyListState(initialFirstVisibleItemIndex = 1)
        var items by mutableStateOf(listOf("A", "B", "C"))
        var nextIdentity = 0

        runMosaicTest {
            assertEquals(
                "B:1\nC:2",
                setContentAndSnapshot {
                    LazyColumn(Modifier.height(2), state) {
                        items(items, key = { item -> item }) { item ->
                            Text("$item:${remember { nextIdentity++ }}")
                        }
                    }
                },
            )
            assertEquals(1, state.firstVisibleItemIndex)
            assertEquals(listOf("B", "C"), state.layoutInfo.visibleItemsInfo.map { item -> item.key })

            items = listOf("X") + items
            val prependedSnapshot = awaitSnapshot()
            assertEquals(listOf("B", "C"), state.layoutInfo.visibleItemsInfo.map { item -> item.key })
            assertEquals("B:1\nC:2", prependedSnapshot)
        }
    }

    test("forward focus search synchronously searches a bounded window and scrolls the target into view") {
        val state = LazyListState()
        var focused by mutableStateOf("")

        runMosaicTest {
            setContentAndSnapshot {
                Column {
                    LazyColumn(Modifier.height(2), state) {
                        items(count = 12, key = { index -> index }) { index ->
                            if (index == 0 || index == 7) {
                                FocusableLazyItem(index, onFocus = { focused = "item-$index" })
                            } else {
                                Text(".")
                            }
                        }
                    }
                    FocusableLazyItem(index = 99, onFocus = { focused = "outside" })
                }
            }
            assertEquals("item-0", focused)

            sendKeyEvent(KeyboardEvent(9))
            awaitSnapshot()

            assertEquals("item-7", focused)
            assertEquals(6, state.firstVisibleItemIndex)
        }
    }

    test("backward focus search synchronously searches in traversal order and scrolls the target into view") {
        val state = LazyListState(initialFirstVisibleItemIndex = 20)
        var focused by mutableStateOf("")

        runMosaicTest {
            setContentAndSnapshot {
                LazyColumn(Modifier.height(2), state) {
                    items(count = 24, key = { index -> index }) { index ->
                        if (index == 15 || index == 20) {
                            FocusableLazyItem(index, onFocus = { focused = "item-$index" })
                        } else {
                            Text(".")
                        }
                    }
                }
            }
            assertEquals("item-20", focused)

            sendKeyEvent(KeyboardEvent(9, modifiers = ModifierShift))
            awaitSnapshot()

            assertEquals("item-15", focused)
            assertEquals(15, state.firstVisibleItemIndex)
        }
    }

    test("focus relocation minimally scrolls a partially visible target into view") {
        val state = LazyListState()
        var focused by mutableStateOf("")

        runMosaicTest {
            setContentAndSnapshot {
                LazyColumn(Modifier.height(3), state) {
                    items(count = 3, key = { index -> index }) { index ->
                        FocusableLazyItem(
                            index = index,
                            height = 2,
                            onFocus = { focused = "item-$index" },
                        )
                    }
                }
            }
            assertEquals("item-0", focused)

            sendKeyEvent(KeyboardEvent(9))
            awaitSnapshot()

            assertEquals("item-1", focused)
            assertEquals(0, state.firstVisibleItemIndex)
            assertEquals(1, state.firstVisibleItemScrollOffset)
        }
    }

    test("pointer focus leaves a partially visible target at its current viewport position") {
        val state = LazyListState(initialFirstVisibleItemScrollOffset = 1)
        var focused by mutableStateOf("")

        runMosaicTest {
            setContentAndSnapshot {
                Column {
                    FocusableLazyItem(index = 99, onFocus = { focused = "outside" })
                    LazyColumn(Modifier.height(3), state) {
                        items(count = 3, key = { index -> index }) { index ->
                            FocusableLazyItem(
                                index = index,
                                height = 2,
                                onFocus = { focused = "item-$index" },
                            )
                        }
                    }
                }
            }
            assertEquals("outside", focused)
            assertEquals(1, state.firstVisibleItemScrollOffset)

            sendMouseEvent(MouseEvent(0, 1, MouseEvent.Type.Press, MouseEvent.Button.Left))
            awaitSnapshot()

            assertEquals("item-0", focused)
            assertEquals(0, state.firstVisibleItemIndex)
            assertEquals(1, state.firstVisibleItemScrollOffset)
        }
    }

    test("initial focus relocation runs after the current layout pass") {
        val state = LazyListState(initialFirstVisibleItemScrollOffset = 1)
        var focused by mutableStateOf("")

        runMosaicTest {
            setContentAndSnapshot {
                LazyColumn(Modifier.height(3), state) {
                    items(count = 3, key = { index -> index }) { index ->
                        FocusableLazyItem(
                            index = index,
                            height = 2,
                            onFocus = { focused = "item-$index" },
                        )
                    }
                }
            }
        }

        assertEquals("item-0", focused)
        assertEquals(0, state.firstVisibleItemIndex)
        assertEquals(0, state.firstVisibleItemScrollOffset)
    }

    test("disabled user scrolling does not search beyond lazy bounds") {
        val state = LazyListState()
        var focused by mutableStateOf("")

        runMosaicTest {
            setContentAndSnapshot {
                Column {
                    LazyColumn(
                        modifier = Modifier.height(2),
                        state = state,
                        userScrollEnabled = false,
                    ) {
                        items(count = 12, key = { index -> index }) { index ->
                            if (index == 0 || index == 7) {
                                FocusableLazyItem(index, onFocus = { focused = "item-$index" })
                            } else {
                                Text(".")
                            }
                        }
                    }
                    FocusableLazyItem(index = 99, onFocus = { focused = "outside" })
                }
            }
            assertEquals("item-0", focused)

            sendKeyEvent(KeyboardEvent(9))
            awaitSnapshot()

            assertEquals("outside", focused)
            assertEquals(0, state.firstVisibleItemIndex)
        }
    }

    test("focus search falls back after two viewports without scrolling") {
        val state = LazyListState()
        var focused by mutableStateOf("")

        runMosaicTest {
            setContentAndSnapshot {
                Column {
                    LazyColumn(Modifier.height(2), state) {
                        items(count = 24, key = { index -> index }) { index ->
                            if (index == 0 || index == 20) {
                                FocusableLazyItem(index, onFocus = { focused = "item-$index" })
                            } else {
                                Text(".")
                            }
                        }
                    }
                    FocusableLazyItem(index = 99, onFocus = { focused = "outside" })
                }
            }
            assertEquals("item-0", focused)

            sendKeyEvent(KeyboardEvent(9))
            awaitSnapshot()

            assertEquals("outside", focused)
            assertEquals(0, state.firstVisibleItemIndex)
        }
    }
}

private data class TestItem(
    val key: String,
    val height: Int,
)

@Composable
private fun TestLazyColumn(
    items: List<TestItem>,
    state: LazyListState,
    viewportHeight: Int,
    reverseLayout: Boolean = false,
) {
    LazyColumn(
        modifier = Modifier.height(viewportHeight),
        state = state,
        reverseLayout = reverseLayout,
    ) {
        items(items, key = TestItem::key) { item ->
            Column {
                repeat(item.height) { row ->
                    Text("${item.key}$row")
                }
            }
        }
    }
}

@Composable
private fun WidthSensitiveItem(label: String) {
    Layout(
        content = { Text(label) },
        measurePolicy = MeasurePolicy { measurables, constraints ->
            val placeable = measurables.single().measure(
                constraints.copy(minHeight = 0, maxHeight = Constraints.Infinity)
            )
            val height = if (constraints.maxWidth <= 3) 2 else 1
            layout(constraints.constrainWidth(placeable.width), height) {
                placeable.place(0, 0)
            }
        },
    )
}

@Composable
private fun FocusableLazyItem(
    index: Int,
    height: Int = 1,
    onFocus: () -> Unit,
) {
    Text(
        value = "item-$index",
        modifier = Modifier
            .onFocusChanged { state ->
                if (state == FocusState.Active) onFocus()
            }
            .focusable()
            .height(height),
    )
}
