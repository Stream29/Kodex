package io.github.stream29.kodex.cli.components

import com.jakewharton.mosaic.layout.width
import com.jakewharton.mosaic.modifier.Modifier
import com.jakewharton.mosaic.terminal.AnsiLevel
import com.jakewharton.mosaic.terminal.Terminal
import com.jakewharton.mosaic.testing.SnapshotStrategy
import com.jakewharton.mosaic.testing.runMosaicTest
import com.jakewharton.mosaic.ui.Color
import com.jakewharton.mosaic.ui.Text
import com.jakewharton.mosaic.ui.TextStyle
import de.infix.testBalloon.framework.core.testSuite
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.math.pow

val tuiThemeTest by testSuite {
    test("default background remains unspecified for transparent history rendering") {
        assertEquals(Color.Unspecified, DefaultTuiColorScheme.background)
        assertEquals(Color.Unspecified, LightTuiColorScheme.background)
        assertEquals(Color.Unspecified, DarkTuiColorScheme.background)
    }

    test("terminal theme chooses the generated color scheme") {
        assertEquals(LightTuiColorScheme, tuiColorSchemeFor(Terminal.Theme.Light))
        assertEquals(DarkTuiColorScheme, tuiColorSchemeFor(Terminal.Theme.Dark))
        assertEquals(DarkTuiColorScheme, tuiColorSchemeFor(Terminal.Theme.Unknown))
    }

    test("generated semantic role pairs meet contrast targets") {
        listOf(LightTuiColorScheme, DarkTuiColorScheme).forEach { scheme ->
            assertContrast(scheme.onPrimary, scheme.primary, 4.5)
            assertContrast(scheme.onPrimaryContainer, scheme.primaryContainer, 4.5)
            assertContrast(scheme.onSecondaryContainer, scheme.secondaryContainer, 4.5)
            assertContrast(scheme.onTertiaryContainer, scheme.tertiaryContainer, 4.5)
            assertContrast(scheme.onSurface, scheme.surface, 4.5)
            assertContrast(scheme.onSurfaceVariant, scheme.surface, 4.5)
            assertContrast(scheme.primary, scheme.surface, 4.5)
            assertContrast(scheme.error, scheme.surface, 4.5)
            assertContrast(scheme.success, scheme.surface, 4.5)
            assertContrast(scheme.outline, scheme.surface, 3.0)
        }
    }

    test("nested theme defaults inherit the surrounding semantic systems") {
        val outerScheme = DefaultTuiColorScheme.copy(primary = Color.Blue)
        var outerObserved: TuiColorScheme? = null
        var inheritedObserved: TuiColorScheme? = null
        var overriddenObserved: TuiColorScheme? = null

        runMosaicTest {
            setContentAndSnapshot {
                TuiTheme(colorScheme = outerScheme) {
                    outerObserved = TuiTheme.colorScheme
                    TuiTheme {
                        inheritedObserved = TuiTheme.colorScheme
                        TuiTheme(colorScheme = TuiTheme.colorScheme.copy(primary = Color.Red)) {
                            overriddenObserved = TuiTheme.colorScheme
                            Text("theme", modifier = Modifier.width(5))
                        }
                    }
                }
            }
        }

        assertEquals(outerScheme, outerObserved)
        assertEquals(outerScheme, inheritedObserved)
        assertEquals(Color.Red, overriddenObserved?.primary)
        assertEquals(Color.Unspecified, overriddenObserved?.background)
    }

    test("buttons consume the semantic label typography") {
        val ansiSnapshots = SnapshotStrategy { mosaic ->
            mosaic.draw().render(AnsiLevel.TRUECOLOR, supportsKittyUnderlines = false)
        }

        runMosaicTest(snapshotStrategy = ansiSnapshots) {
            assertEquals(
                "\u001B[3m[theme]\u001B[0m",
                setContentAndSnapshot {
                    TuiTheme(
                        typography = DefaultTuiTypography.copy(label = TextStyle.Italic),
                    ) {
                        TuiButton(label = "theme", onClick = {})
                    }
                },
            )
        }
    }

    test("ANSI16 keeps checkbox markers and text-style affordances") {
        val ansiSnapshots = SnapshotStrategy { mosaic ->
            mosaic.draw().render(AnsiLevel.ANSI16, supportsKittyUnderlines = false)
        }

        runMosaicTest(snapshotStrategy = ansiSnapshots) {
            val rendered = setContentAndSnapshot {
                Text(
                    value = "[x] enabled  [ ] disabled",
                    textStyle = TextStyle.Bold,
                )
            }

            assertTrue("[x] enabled  [ ] disabled" in rendered)
            assertTrue("\u001B[1m" in rendered)
        }
    }
}

private fun assertContrast(foreground: Color, background: Color, minimum: Double) {
    val ratio = contrastRatio(foreground, background)
    assertTrue(
        ratio >= minimum,
        "Expected contrast >= $minimum but got $ratio for $foreground on $background",
    )
}

private fun contrastRatio(first: Color, second: Color): Double {
    val firstLuminance = relativeLuminance(first)
    val secondLuminance = relativeLuminance(second)
    val lighter = maxOf(firstLuminance, secondLuminance)
    val darker = minOf(firstLuminance, secondLuminance)
    return (lighter + 0.05) / (darker + 0.05)
}

private fun relativeLuminance(color: Color): Double {
    val (red, green, blue) = color
    fun linear(component: Float): Double {
        val value = component.toDouble()
        return if (value <= 0.04045) {
            value / 12.92
        } else {
            ((value + 0.055) / 1.055).pow(2.4)
        }
    }
    return 0.2126 * linear(red) +
        0.7152 * linear(green) +
        0.0722 * linear(blue)
}
