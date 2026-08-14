package io.github.stream29.kodex.desktop.application

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import kotlin.test.Test
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

public class KodexDesktopThemeTest {
    @OptIn(ExperimentalTestApi::class)
    @Test
    public fun lightThemeProvidesLightSemanticSurfaces(): Unit =
        runDesktopComposeUiTest {
            var background = Color.Unspecified
            var foreground = Color.Unspecified
            var primaryContainer = Color.Unspecified
            setContent {
                KodexDesktopTheme(darkTheme = false) {
                    background = MaterialTheme.colorScheme.background
                    foreground = MaterialTheme.colorScheme.onBackground
                    primaryContainer = MaterialTheme.colorScheme.primaryContainer
                }
            }

            runOnIdle {
                assertTrue(background.luminance() > 0.8f)
                assertTrue(foreground.luminance() < 0.2f)
                assertNotEquals(background, primaryContainer)
            }
        }

    @OptIn(ExperimentalTestApi::class)
    @Test
    public fun darkThemeProvidesDarkSemanticSurfaces(): Unit =
        runDesktopComposeUiTest {
            var background = Color.Unspecified
            var foreground = Color.Unspecified
            var primaryContainer = Color.Unspecified
            setContent {
                KodexDesktopTheme(darkTheme = true) {
                    background = MaterialTheme.colorScheme.background
                    foreground = MaterialTheme.colorScheme.onBackground
                    primaryContainer = MaterialTheme.colorScheme.primaryContainer
                }
            }

            runOnIdle {
                assertTrue(background.luminance() < 0.1f)
                assertTrue(foreground.luminance() > 0.7f)
                assertNotEquals(background, primaryContainer)
            }
        }
}
