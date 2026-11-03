package io.github.stream29.kodex.cli.components

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import com.jakewharton.mosaic.focus.FocusRequester
import com.jakewharton.mosaic.focus.focusRequester
import com.jakewharton.mosaic.focus.focusable
import com.jakewharton.mosaic.layout.MeasurePolicy
import com.jakewharton.mosaic.layout.height
import com.jakewharton.mosaic.modifier.Modifier
import com.jakewharton.mosaic.testing.runMosaicTest
import com.jakewharton.mosaic.ui.Column
import com.jakewharton.mosaic.ui.Layout
import com.jakewharton.mosaic.ui.Text
import de.infix.testBalloon.framework.core.testSuite
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

val lazyColumnPrefetchFocusTest by testSuite {
    test("premeasured predictive item cannot acquire focus until adopted") {
        val state = LazyListState()
        val requester = FocusRequester()
        val measured = mutableSetOf<Int>()
        var frameTrigger by mutableIntStateOf(0)

        runMosaicTest {
            setContentAndSnapshot {
                Column {
                    Text(frameTrigger.toString())
                    LazyColumn(Modifier.height(2), state) {
                        items(count = 20, key = { it }) { index ->
                            Layout(
                                content = {
                                    val modifier = if (index == 6) {
                                        Modifier.focusRequester(requester).focusable()
                                    } else {
                                        Modifier
                                    }
                                    Text(index.toString(), modifier)
                                },
                                measurePolicy = MeasurePolicy { measurables, constraints ->
                                    measured += index
                                    val child = measurables.single().measure(constraints)
                                    layout(child.width, child.height) { child.place(0, 0) }
                                },
                            )
                        }
                    }
                }
            }
            assertEquals(1, state.scrollBy(1))
            awaitSnapshot()
            repeat(12) {
                if (6 !in measured) {
                    frameTrigger++
                    awaitSnapshot()
                }
            }
            assertTrue(6 in measured)
            assertFalse(state.layoutInfo.visibleItemsInfo.any { it.index == 6 })
            frameTrigger++
            awaitSnapshot()
            assertFalse(requester.requestFocus())
            assertEquals(4, state.scrollBy(4))
            awaitSnapshot()
            assertTrue(state.layoutInfo.visibleItemsInfo.any { it.index == 6 })
            assertTrue(requester.requestFocus())
        }
    }
}
