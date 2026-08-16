package io.github.stream29.kodex.cli.settings

import com.jakewharton.mosaic.layout.width
import com.jakewharton.mosaic.modifier.Modifier
import com.jakewharton.mosaic.terminal.AnsiLevel
import com.jakewharton.mosaic.testing.SnapshotStrategy
import com.jakewharton.mosaic.testing.runMosaicTest
import com.jakewharton.mosaic.ui.Color
import com.jakewharton.mosaic.ui.Column
import io.github.stream29.kodex.cli.components.DefaultTuiColorScheme
import io.github.stream29.kodex.cli.components.TuiTheme
import io.github.stream29.kodex.cli.components.rememberTuiDropdownState
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SettingsDropdownFieldTest {
    @Test
    fun titlesAndCurrentValuesShareOneRow() = runTest {
        runMosaicTest {
            val snapshot = setContentAndSnapshot {
                Column(Modifier.width(40)) {
                    SettingsDropdownField(
                        label = "Model",
                        selectedLabel = "gpt-5.6-sol",
                        dropdownState = rememberTuiDropdownState(),
                    )
                    SettingsDropdownField(
                        label = "Authentication",
                        selectedLabel = "Codex",
                        dropdownState = rememberTuiDropdownState(),
                    )
                }
            }

            assertEquals(
                listOf("Model [gpt-5.6-sol]", "Authentication [Codex]"),
                snapshot.lines().map(String::trimEnd),
            )
        }
    }

    @Test
    fun ordinaryFieldsShareTheNeutralSurfaceRole() = runTest {
        val ansiSnapshots = SnapshotStrategy { mosaic ->
            mosaic.draw().render(AnsiLevel.TRUECOLOR, supportsKittyUnderlines = false)
        }

        runMosaicTest(snapshotStrategy = ansiSnapshots) {
            val snapshot = setContentAndSnapshot {
                TuiTheme(
                    colorScheme = DefaultTuiColorScheme.copy(
                        surface = Color(12, 34, 56),
                        surfaceContainerHighest = Color(21, 22, 23),
                        surfaceContainerHigh = Color(31, 32, 33),
                        secondaryContainer = Color(41, 42, 43),
                        tertiaryContainer = Color(51, 52, 53),
                    ),
                ) {
                    Column(Modifier.width(40)) {
                        SettingsDropdownField(
                            label = "Model",
                            selectedLabel = "gpt-5.6-sol",
                            dropdownState = rememberTuiDropdownState(),
                        )
                        SettingsDropdownField(
                            label = "Questions",
                            selectedLabel = "ask user",
                            dropdownState = rememberTuiDropdownState(),
                        )
                    }
                }
            }

            assertTrue(";48;2;12;34;56m" in snapshot, snapshot)
            assertFalse(";48;2;21;22;23m" in snapshot, snapshot)
            assertFalse(";48;2;31;32;33m" in snapshot, snapshot)
            assertFalse(";48;2;41;42;43m" in snapshot, snapshot)
            assertFalse(";48;2;51;52;53m" in snapshot, snapshot)
        }
    }
}
