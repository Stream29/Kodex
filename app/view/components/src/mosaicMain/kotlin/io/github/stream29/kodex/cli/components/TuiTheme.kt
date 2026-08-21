package io.github.stream29.kodex.cli.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import com.jakewharton.mosaic.ui.Color
import com.jakewharton.mosaic.ui.TextStyle
import com.jakewharton.mosaic.terminal.Terminal

/**
 * Semantic terminal colors supplied to Kodex TUI components.
 *
 * [background] intentionally defaults to [Color.Unspecified]. The conversation history uses that
 * role so Mosaic does not fill otherwise empty cells and terminal-native text copying remains
 * intact. Surface roles are opaque and intended for bounded controls, sidebars, menus, and dialogs.
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
public val LightTuiColorScheme: TuiColorScheme = TuiColorScheme(
    background = Color.Unspecified,
    onBackground = Color(23, 29, 30),
    primary = Color(0, 104, 116),
    onPrimary = Color.White,
    primaryContainer = Color(158, 239, 253),
    onPrimaryContainer = Color(0, 79, 88),
    secondaryContainer = Color(205, 231, 236),
    onSecondaryContainer = Color(51, 75, 79),
    tertiaryContainer = Color(218, 226, 255),
    onTertiaryContainer = Color(59, 70, 100),
    surface = Color(245, 250, 251),
    onSurface = Color(23, 29, 30),
    onSurfaceVariant = Color(63, 72, 74),
    surfaceContainer = Color(233, 239, 240),
    surfaceContainerHigh = Color(227, 233, 234),
    surfaceContainerHighest = Color(222, 227, 229),
    outline = Color(111, 121, 122),
    error = Color(186, 26, 26),
    onError = Color.White,
    success = Color(0, 109, 61),
    onSuccess = Color.White,
)

/**
 * Generated dark scheme from the Kodex teal source color `#1C444A`.
 *
 * Palette generation used `@material/material-color-utilities` npm package `0.3.0`.
 */
public val DarkTuiColorScheme: TuiColorScheme = TuiColorScheme(
    background = Color.Unspecified,
    onBackground = Color(222, 227, 229),
    primary = Color(130, 211, 224),
    onPrimary = Color(0, 54, 61),
    primaryContainer = Color(0, 79, 88),
    onPrimaryContainer = Color(158, 239, 253),
    secondaryContainer = Color(51, 75, 79),
    onSecondaryContainer = Color(205, 231, 236),
    tertiaryContainer = Color(59, 70, 100),
    onTertiaryContainer = Color(218, 226, 255),
    surface = Color(14, 20, 21),
    onSurface = Color(222, 227, 229),
    onSurfaceVariant = Color(191, 200, 202),
    surfaceContainer = Color(27, 33, 34),
    surfaceContainerHigh = Color(37, 43, 44),
    surfaceContainerHighest = Color(48, 54, 55),
    outline = Color(137, 146, 148),
    error = Color(255, 180, 171),
    onError = Color(105, 0, 5),
    success = Color(0, 227, 133),
    onSuccess = Color(0, 57, 29),
)

/** Default scheme preserves the existing dark terminal appearance. */
public val DefaultTuiColorScheme: TuiColorScheme = DarkTuiColorScheme

/** Selects a static scheme from the terminal-reported appearance. */
public fun tuiColorSchemeFor(theme: Terminal.Theme): TuiColorScheme = when (theme) {
    Terminal.Theme.Light -> LightTuiColorScheme
    Terminal.Theme.Dark,
    Terminal.Theme.Unknown -> DarkTuiColorScheme
}

/** Default terminal typography using only portable ANSI text styles. */
public val DefaultTuiTypography: TuiTypography = TuiTypography(
    headline = TextStyle.Bold,
    title = TextStyle.Bold,
    body = TextStyle.Unspecified,
    label = TextStyle.Unspecified,
    supporting = TextStyle.Dim,
)

/**
 * Supplies semantic TUI theme values to [content].
 *
 * Defaults inherit the current theme, matching Compose theme nesting semantics.
 */
@Composable
public fun TuiTheme(
    colorScheme: TuiColorScheme = TuiTheme.colorScheme,
    typography: TuiTypography = TuiTheme.typography,
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(
        LocalTuiColorScheme provides colorScheme,
        LocalTuiTypography provides typography,
        content = content,
    )
}

/** Accesses semantic theme values at the current composition location. */
public object TuiTheme {
    public val colorScheme: TuiColorScheme
        @Composable
        @ReadOnlyComposable
        get() = LocalTuiColorScheme.current

    public val typography: TuiTypography
        @Composable
        @ReadOnlyComposable
        get() = LocalTuiTypography.current
}

private val LocalTuiColorScheme = staticCompositionLocalOf { DefaultTuiColorScheme }
private val LocalTuiTypography = staticCompositionLocalOf { DefaultTuiTypography }
