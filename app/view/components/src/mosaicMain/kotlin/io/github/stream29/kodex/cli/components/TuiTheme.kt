package io.github.stream29.kodex.cli.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import com.jakewharton.mosaic.ui.Color
import com.jakewharton.mosaic.ui.TextStyle

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

/** Default dark palette preserving the existing Kodex visual identity. */
public val DefaultTuiColorScheme: TuiColorScheme = TuiColorScheme(
    background = Color.Unspecified,
    onBackground = Color.White,
    primary = Color(28, 68, 74),
    onPrimary = Color.White,
    primaryContainer = Color(36, 78, 84),
    onPrimaryContainer = Color.White,
    secondaryContainer = Color(46, 58, 62),
    onSecondaryContainer = Color.White,
    tertiaryContainer = Color(42, 54, 58),
    onTertiaryContainer = Color.White,
    surface = Color(52, 52, 56),
    onSurface = Color.White,
    surfaceContainer = Color(42, 42, 46),
    surfaceContainerHigh = Color(58, 58, 64),
    surfaceContainerHighest = Color(62, 62, 66),
    outline = Color(96, 96, 102),
    error = Color.Red,
    onError = Color.White,
    success = Color.Green,
    onSuccess = Color.White,
)

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
