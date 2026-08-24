package io.github.stream29.kodex.cli.settings

import com.jakewharton.mosaic.layout.background
import com.jakewharton.mosaic.layout.fillMaxWidth
import com.jakewharton.mosaic.layout.width
import com.jakewharton.mosaic.modifier.Modifier
import com.jakewharton.mosaic.terminal.AnsiLevel
import com.jakewharton.mosaic.testing.SnapshotStrategy
import com.jakewharton.mosaic.testing.runMosaicTest
import com.jakewharton.mosaic.ui.Color
import com.jakewharton.mosaic.ui.Column
import com.jakewharton.mosaic.ui.Row
import io.github.stream29.kodex.cli.components.DefaultTuiColorScheme
import io.github.stream29.kodex.cli.components.TuiTheme
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.math.roundToInt

class SettingsButtonStyleTest {
    @Test
    fun buttonsUsePairedSemanticColorsWithoutInverseVideo() = runTest {
        val scheme = DefaultTuiColorScheme.copy(
            primary = Color(101, 102, 103),
            onPrimary = Color(111, 112, 113),
            secondaryContainer = Color(121, 122, 123),
            onSecondaryContainer = Color(131, 132, 133),
            surface = Color(141, 142, 143),
            onSurface = Color(151, 152, 153),
            surfaceContainerHigh = Color(161, 162, 163),
            surfaceContainerHighest = Color(171, 172, 173),
            error = Color(181, 182, 183),
            onError = Color(191, 192, 193),
        )
        val ansiSnapshots = SnapshotStrategy { mosaic ->
            mosaic.draw().render(AnsiLevel.TRUECOLOR, supportsKittyUnderlines = false)
        }

        runMosaicTest(snapshotStrategy = ansiSnapshots) {
            val snapshot = setContentAndSnapshot {
                TuiTheme(colorScheme = scheme) {
                    Column(Modifier.width(32)) {
                        Row(Modifier.background(scheme.surfaceContainerHigh)) {
                            SettingsActionButton(label = "Action", onClick = {})
                        }
                        SettingsPrimaryButton(label = "Primary", onClick = {})
                        SettingsDangerButton(
                            label = "Danger",
                            prominent = true,
                            onClick = {},
                        )
                        SettingsNavigationButton(
                            label = "Selected",
                            selected = true,
                            modifier = Modifier.fillMaxWidth(),
                            onClick = {},
                        )
                        Row(Modifier.background(scheme.surface)) {
                            SettingsContentButton(label = "Content", onClick = {})
                        }
                        SettingsPrimaryButton(
                            label = "Disabled",
                            enabled = false,
                            onClick = {},
                        )
                    }
                }
            }

            snapshot.lineContaining("Action").assertColors(
                foreground = scheme.primary,
                background = scheme.surfaceContainerHigh,
            )
            snapshot.lineContaining("Primary").assertColors(
                foreground = scheme.onPrimary,
                background = scheme.primary,
            )
            snapshot.lineContaining("Danger").assertColors(
                foreground = scheme.onError,
                background = scheme.error,
            )
            snapshot.lineContaining("Selected").assertColors(
                foreground = scheme.onSecondaryContainer,
                background = scheme.secondaryContainer,
            )
            snapshot.lineContaining("Content").assertColors(
                foreground = scheme.onSurface,
                background = scheme.surface,
            )
            snapshot.lineContaining("Disabled").assertColors(
                foreground = scheme.onSurface,
                background = scheme.surfaceContainerHighest,
            )
            assertTrue("\u001B[2m" in snapshot || ";2m" in snapshot, snapshot)
            assertFalse(
                Regex("\u001B\\[(?:\\d+;)*7(?:;\\d+)*m").containsMatchIn(snapshot),
                snapshot,
            )
        }
    }
}

private fun String.lineContaining(label: String): String =
    lines().firstOrNull { line -> label in line }
        ?: error("Missing '$label' in:\n$this")

private fun String.assertColors(foreground: Color, background: Color) {
    val (foregroundRed, foregroundGreen, foregroundBlue) = foreground.rgb()
    val (backgroundRed, backgroundGreen, backgroundBlue) = background.rgb()
    assertTrue(
        "38;2;$foregroundRed;$foregroundGreen;$foregroundBlue" in this,
        this,
    )
    assertTrue(
        "48;2;$backgroundRed;$backgroundGreen;$backgroundBlue" in this,
        this,
    )
}

private fun Color.rgb(): Triple<Int, Int, Int> {
    val (red, green, blue) = this
    return Triple(
        (red * 255).roundToInt(),
        (green * 255).roundToInt(),
        (blue * 255).roundToInt(),
    )
}
