package io.github.stream29.kodex.cli.components

import com.jakewharton.mosaic.layout.width
import com.jakewharton.mosaic.modifier.Modifier
import com.jakewharton.mosaic.testing.runMosaicTest
import de.infix.testBalloon.framework.core.testSuite
import kotlin.test.assertEquals

val tuiDialogActionRowTest by testSuite {
    test("actions are trailing aligned with one cell between them") {
        runMosaicTest {
            assertEquals(
                "     [Cancel] [Save]",
                setContentAndSnapshot {
                    TuiDialogActionRow(modifier = Modifier.width(20)) {
                        TuiButton(label = "Cancel", onClick = {})
                        TuiButton(label = "Save", onClick = {})
                    }
                },
            )
        }
    }
}
