package io.github.stream29.kodex.cli.components

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.jakewharton.mosaic.terminal.AnsiLevel
import com.jakewharton.mosaic.terminal.KeyboardEvent
import com.jakewharton.mosaic.testing.SnapshotStrategy
import com.jakewharton.mosaic.testing.runMosaicTest
import com.jakewharton.mosaic.ui.Box
import de.infix.testBalloon.framework.core.testSuite
import kotlin.test.assertEquals

private val checkboxAnsiSnapshots = SnapshotStrategy { mosaic ->
    mosaic.draw().render(AnsiLevel.TRUECOLOR, supportsKittyUnderlines = false)
}

val tuiCheckboxTest by testSuite {
    test("keyboardActivationTogglesTheCheckboxAndKeepsLabelTogether") {
        var checked by mutableStateOf(false)

        runMosaicTest(snapshotStrategy = checkboxAnsiSnapshots) {
            assertEquals(
                "[ ] Automatic session title",
                setContentAndSnapshot {
                    Box {
                        TuiCheckbox(
                            label = "Automatic session title",
                            checked = checked,
                            onCheckedChange = { checked = it },
                        )
                    }
                },
            )

            sendKeyEvent(KeyboardEvent(codepoint = 13))
            assertEquals("[x] Automatic session title", awaitSnapshot())
            assertEquals(true, checked)

            sendKeyEvent(KeyboardEvent(codepoint = 32))
            assertEquals("[ ] Automatic session title", awaitSnapshot())
            assertEquals(false, checked)
        }
    }

    test("disabledCheckboxIsDimAndDoesNotToggle") {
        var checked by mutableStateOf(false)

        runMosaicTest(snapshotStrategy = checkboxAnsiSnapshots) {
            assertEquals(
                "\u001B[2m[ ] OAuth\u001B[0m",
                setContentAndSnapshot {
                    Box {
                        TuiCheckbox(
                            label = "OAuth",
                            checked = checked,
                            onCheckedChange = { checked = it },
                            enabled = false,
                        )
                    }
                },
            )

            sendKeyEvent(KeyboardEvent(codepoint = 13))
            assertEquals(false, checked)
        }
    }
}
