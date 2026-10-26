package io.github.stream29.kodex.cli.components

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.jakewharton.mosaic.layout.height
import com.jakewharton.mosaic.layout.width
import com.jakewharton.mosaic.modifier.Modifier
import com.jakewharton.mosaic.terminal.MouseEvent
import com.jakewharton.mosaic.testing.runMosaicTest
import com.jakewharton.mosaic.ui.Column
import com.jakewharton.mosaic.ui.Text
import de.infix.testBalloon.framework.core.testSuite
import kotlin.test.assertEquals
import kotlin.test.assertTrue

val lazyColumnRemountTest by testSuite {
    test("standalone focusable rows can relocate focus on remount independently of History") {
        val state = LazyListState()
        val interactions = mutableListOf<ScrollInteraction>()
        val input = MutableScrollInteractionSource { interactions += it }
        var mounted by mutableStateOf(true)
        runMosaicTest {
            setContentAndSnapshot {
                Column(Modifier.width(40).height(8)) {
                    if (mounted) {
                        LazyColumn(
                            Modifier.width(40).height(8), state,
                            reverseLayout = true, interactionSource = input,
                        ) {
                            items(30, key = { it }) { row ->
                                TuiPressable(onClick = {}) { _, _, _ ->
                                    Column {
                                        Text("header-$row")
                                        Text("body-$row")
                                    }
                                }
                            }
                        }
                    } else {
                        Text("other tab")
                    }
                }
            }
            sendMouseEvent(MouseEvent(1, 1, MouseEvent.Type.Press, MouseEvent.Button.WheelUp))
            awaitSnapshot()
            mounted = false
            awaitSnapshot()
            interactions.clear()
            mounted = true
            awaitSnapshot()
            assertTrue(
                interactions.any { it.source == ScrollInputSource.FocusRelocation },
                "A remounted standalone focus scope reveals its selected row; no VM is involved.",
            )
        }
    }

    test("retained reverse list restores a partial item without History or a ViewModel") {
        val state = LazyListState()
        var mounted by mutableStateOf(true)
        runMosaicTest {
            setContentAndSnapshot {
                Column(Modifier.width(40).height(8)) {
                    if (mounted) {
                        LazyColumn(Modifier.width(40).height(8), state, reverseLayout = true) {
                            items(30, key = { it }) { row ->
                                Column {
                                    Text("header-$row")
                                    Text("body-$row")
                                }
                            }
                        }
                    } else {
                        Text("other tab")
                    }
                }
            }
            sendMouseEvent(MouseEvent(1, 1, MouseEvent.Type.Press, MouseEvent.Button.WheelUp))
            val before = awaitSnapshot()
            val index = state.firstVisibleItemIndex
            val offset = state.firstVisibleItemScrollOffset
            mounted = false
            awaitSnapshot()
            mounted = true
            val after = awaitSnapshot()
            assertEquals(before, after, "index=$index offset=$offset restored=${state.firstVisibleItemIndex}/${state.firstVisibleItemScrollOffset}")
            assertEquals(index, state.firstVisibleItemIndex)
            assertEquals(offset, state.firstVisibleItemScrollOffset)
        }
    }
}
