@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")

package io.github.stream29.kodex.cli.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import com.jakewharton.mosaic.terminal.Terminal
import com.jakewharton.mosaic.ui.Color
import com.jakewharton.mosaic.ui.TextStyle

/**
 * Semantic terminal colors supplied to Kodex TUI components.
 *
 * [background] and [surface] intentionally use [Color.Unspecified]. Mosaic then leaves large
 * background regions to the terminal instead of assuming black or any other configured color.
 * Container roles remain available for bounded controls, headers, menus, and dialogs.
 */
@Immutable
public data class TuiColorScheme(
    public val background: Color,
    public val onBackground: Color,
    public val primary: Color,
    public val onPrimary: Color,
    public val primaryContainer: Color,
    public val onPrimaryContainer: Color,
    public val secondaryContainer: Color,
    public val onSecondaryContainer: Color,
    public val tertiaryContainer: Color,
    public val onTertiaryContainer: Color,
    public val surface: Color,
    public val onSurface: Color,
    public val onSurfaceVariant: Color,
    public val surfaceContainer: Color,
    public val surfaceContainerHigh: Color,
    public val surfaceContainerHighest: Color,
    public val surfaceContainerHover: Color,
    public val surfaceContainerActive: Color,
    public val outline: Color,
    public val error: Color,
    public val onError: Color,
    public val success: Color,
    public val onSuccess: Color,
)

/** Semantic terminal text roles. Terminal font family and size remain terminal-owned. */
@Immutable
public data class TuiTypography(
    public val headline: TextStyle,
    public val title: TextStyle,
    public val body: TextStyle,
    public val label: TextStyle,
    public val supporting: TextStyle,
)

/**
 * Generated light scheme from the Kodex teal source color `#1C444A`.
 *
 * Palette generation used `@material/material-color-utilities` npm package `0.3.0`.
 */
public expect val LightTuiColorScheme: TuiColorScheme

/**
 * Generated dark scheme from the Kodex teal source color `#1C444A`.
 *
 * Palette generation used `@material/material-color-utilities` npm package `0.3.0`.
 */
public expect val DarkTuiColorScheme: TuiColorScheme

/**
 * Compatibility fallback used when no terminal-specific scheme is provided.
 *
 * The application normally supplies [LightTuiColorScheme] or [DarkTuiColorScheme] from the
 * terminal theme. Keeping this fallback stable avoids breaking components rendered outside that
 * application root.
 */
public expect val DefaultTuiColorScheme: TuiColorScheme

/** Selects a static scheme from the terminal-reported appearance. */
public expect fun tuiColorSchemeFor(theme: Terminal.Theme): TuiColorScheme

/** Default terminal typography using only portable ANSI text styles. */
public expect val DefaultTuiTypography: TuiTypography

/**
 * Supplies semantic TUI theme values to [content].
 *
 * Defaults inherit the current theme, matching Compose theme nesting semantics.
 */
@Composable
public expect fun TuiTheme(
    colorScheme: TuiColorScheme = TuiTheme.colorScheme,
    typography: TuiTypography = TuiTheme.typography,
    content: @Composable () -> Unit,
): Unit

/** Accesses semantic theme values at the current composition location. */
public expect object TuiTheme {
    @get:Composable
    @get:ReadOnlyComposable
    public val colorScheme: TuiColorScheme

    @get:Composable
    @get:ReadOnlyComposable
    public val typography: TuiTypography
}
