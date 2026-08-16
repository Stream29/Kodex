package io.github.stream29.kodex.cli.components

import com.jakewharton.mosaic.layout.width
import com.jakewharton.mosaic.modifier.Modifier
import com.jakewharton.mosaic.terminal.AnsiLevel
import com.jakewharton.mosaic.testing.SnapshotStrategy
import com.jakewharton.mosaic.testing.runMosaicTest
import com.jakewharton.mosaic.ui.Color
import com.jakewharton.mosaic.ui.Text
import com.jakewharton.mosaic.ui.TextStyle
import de.infix.testBalloon.framework.core.testSuite
import kotlin.test.assertEquals

val tuiThemeTest by testSuite {
    test("default background remains unspecified for transparent history rendering") {
        assertEquals(Color.Unspecified, DefaultTuiColorScheme.background)
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
}
