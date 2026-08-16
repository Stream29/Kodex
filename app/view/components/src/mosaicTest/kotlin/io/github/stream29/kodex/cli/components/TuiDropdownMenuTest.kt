package io.github.stream29.kodex.cli.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.jakewharton.mosaic.layout.height
import com.jakewharton.mosaic.layout.width
import com.jakewharton.mosaic.modifier.Modifier
import com.jakewharton.mosaic.terminal.AnsiLevel
import com.jakewharton.mosaic.terminal.KeyboardEvent
import com.jakewharton.mosaic.terminal.MouseEvent
import com.jakewharton.mosaic.testing.TestMosaic
import com.jakewharton.mosaic.testing.runMosaicTest
import com.jakewharton.mosaic.ui.Column
import com.jakewharton.mosaic.ui.Text
import de.infix.testBalloon.framework.core.testSuite
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.TimeoutCancellationException

val tuiDropdownMenuTest by testSuite {
    test("trigger opens the selected menu and applies one option") {
        var selected by mutableStateOf("low")
        val dropdownState = TuiDropdownState()

        runMosaicTest {
            setContentAndSnapshot {
                DropdownHarness(
                    dropdownState = dropdownState,
                    selected = selected,
                    onSelect = { selected = it },
                )
            }
            awaitSnapshotContaining("[current: low]")

            sendMouseEvent(MouseEvent(1, 5, MouseEvent.Type.Press, MouseEvent.Button.Left))
            sendMouseEvent(MouseEvent(1, 5, MouseEvent.Type.Release))
            awaitSnapshotContaining("[medium]")

            sendKeyEvent(KeyboardEvent(codepoint = 57353))
            sendKeyEvent(KeyboardEvent(codepoint = 13))
            awaitSnapshotContaining("[current: medium]")
        }

        assertEquals("medium", selected)
        assertFalse(dropdownState.expanded)
    }

    test("dialog trigger renders its menu through the surrounding popup host") {
        var selected by mutableStateOf("low")
        val dropdownState = TuiDropdownState()

        runMosaicTest {
            setContentAndSnapshot {
                DialogDropdownHarness(
                    dropdownState = dropdownState,
                    selected = selected,
                    onSelect = { selected = it },
                )
            }
            awaitSnapshotContaining("[current: low]")

            dropdownState.expand()
            awaitSnapshotContaining("[medium]")
            assertTrue(dropdownState.expanded)

            sendKeyEvent(KeyboardEvent(codepoint = 57353))
            sendKeyEvent(KeyboardEvent(codepoint = 13))
            awaitSnapshotContaining("[current: medium]")
        }

        assertEquals("medium", selected)
    }

    test("trigger can request initial focus") {
        var selected by mutableStateOf("low")
        val dropdownState = TuiDropdownState()

        runMosaicTest {
            setContentAndSnapshot {
                DropdownHarness(
                    dropdownState = dropdownState,
                    selected = selected,
                    autoFocus = true,
                    onSelect = { selected = it },
                )
            }

            sendKeyEvent(KeyboardEvent(codepoint = 13))
            awaitSnapshotContaining("[medium]")

            assertTrue(dropdownState.expanded)
        }
    }
}

@Composable
private fun DropdownHarness(
    dropdownState: TuiDropdownState,
    selected: String,
    autoFocus: Boolean = false,
    onSelect: (String) -> Unit,
) {
    TuiPopupHost(modifier = Modifier.width(24).height(6)) {
        Column(modifier = Modifier.matchParentSize()) {
            repeat(5) { Text("x".repeat(24)) }
            TuiDropdownTrigger(
                dropdownState = dropdownState,
                label = "current: $selected",
                autoFocus = autoFocus,
            )
        }
        TuiDropdownMenu(
            dropdownState = dropdownState,
            options = listOf("low", "medium", "high"),
            selected = selected,
            optionLabel = { value -> value },
            onSelect = onSelect,
        )
    }
}

@Composable
private fun DialogDropdownHarness(
    dropdownState: TuiDropdownState,
    selected: String,
    onSelect: (String) -> Unit,
) {
    TuiPopupHost(modifier = Modifier.width(24).height(8)) {
        TuiDialog(onDismissRequest = {}) {
            Column(modifier = Modifier.width(18)) {
                Text("Settings")
                TuiDropdownTrigger(
                    dropdownState = dropdownState,
                    label = "current: $selected",
                )
            }
        }
        TuiDropdownMenu(
            dropdownState = dropdownState,
            options = listOf("low", "medium", "high"),
            selected = selected,
            optionLabel = { value -> value },
            onSelect = onSelect,
        )
    }
}

private suspend fun TestMosaic<String>.awaitSnapshotContaining(expected: String): String {
    var latest = ""
    repeat(5) {
        latest = try {
            awaitSnapshot()
        } catch (_: TimeoutCancellationException) {
            draw().render(AnsiLevel.NONE, supportsKittyUnderlines = false)
        }
        if (expected in latest) return latest
    }
    assertTrue(expected in latest, latest)
    return latest
}
