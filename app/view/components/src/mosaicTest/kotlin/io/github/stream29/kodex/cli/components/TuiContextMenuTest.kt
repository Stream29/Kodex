package io.github.stream29.kodex.cli.components

import com.jakewharton.mosaic.layout.height
import com.jakewharton.mosaic.layout.width
import com.jakewharton.mosaic.modifier.Modifier
import com.jakewharton.mosaic.testing.runMosaicTest
import com.jakewharton.mosaic.ui.Column
import com.jakewharton.mosaic.ui.Row
import com.jakewharton.mosaic.ui.Text
import com.jakewharton.mosaic.ui.unit.IntOffset
import com.jakewharton.mosaic.ui.unit.IntSize
import de.infix.testBalloon.framework.core.testSuite
import kotlin.test.assertEquals

val tuiContextMenuTest by testSuite {
    test("context menu positions use the click cell and stay inside the host") {
        val surfaceSize = IntSize(width = 30, height = 10)
        val popupContentSize = IntSize(width = 8, height = 2)

        assertEquals(
            IntOffset(x = 9, y = 4),
            calculateContextMenuPosition(
                anchorPosition = IntOffset(x = 4, y = 3),
                clickPosition = IntOffset(x = 5, y = 1),
                surfaceSize = surfaceSize,
                popupContentSize = popupContentSize,
            ),
        )
        assertEquals(
            IntOffset(x = 22, y = 8),
            calculateContextMenuPosition(
                anchorPosition = IntOffset(x = 25, y = 9),
                clickPosition = IntOffset(x = 2, y = 1),
                surfaceSize = surfaceSize,
                popupContentSize = popupContentSize,
            ),
        )
        assertEquals(
            IntOffset(x = 4, y = 3),
            calculateContextMenuPosition(
                anchorPosition = IntOffset(x = 4, y = 3),
                clickPosition = null,
                surfaceSize = surfaceSize,
                popupContentSize = popupContentSize,
            ),
        )
    }

    test("context menu renders from the supplied click cell by default") {
        runMosaicTest {
            setContentAndSnapshot {
                val anchor = rememberTuiPopupAnchor()
                TuiPopupHost(modifier = Modifier.width(20).height(4)) {
                    Column(modifier = Modifier.matchParentSize()) {
                        Text("....................")
                        Row {
                            Text("..")
                            Text("trigger", modifier = Modifier.tuiPopupAnchor(anchor))
                            Text("...........")
                        }
                        Text("....................")
                        Text("....................")
                    }
                    TuiContextMenu(
                        expanded = true,
                        onDismissRequest = {},
                        anchor = anchor,
                        clickPosition = IntOffset(x = 3, y = 0),
                    ) {
                        TuiPopupMenuItem(key = "menu", onClick = {}) {
                            Text("menu")
                        }
                    }
                }
            }
            val snapshot = awaitSnapshot()

            assertEquals(5, snapshot.lines()[1].indexOf("[menu]"), snapshot)
        }
    }
}
