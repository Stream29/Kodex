@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")

package io.github.stream29.kodex.cli.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import com.jakewharton.mosaic.ui.Color
import com.jakewharton.mosaic.ui.TextStyle
import com.jakewharton.mosaic.terminal.Terminal

public actual val LightTuiColorScheme: TuiColorScheme = TuiColorScheme(
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
    surface = Color.Unspecified,
    onSurface = Color(23, 29, 30),
    onSurfaceVariant = Color(63, 72, 74),
    surfaceContainer = Color(233, 239, 240),
    surfaceContainerHigh = Color(227, 233, 234),
    surfaceContainerHighest = Color(222, 227, 229),
    surfaceContainerHover = Color(216, 222, 223),
    surfaceContainerActive = Color(199, 205, 206),
    outline = Color(111, 121, 122),
    error = Color(186, 26, 26),
    onError = Color.White,
    success = Color(0, 109, 61),
    onSuccess = Color.White,
)

public actual val DarkTuiColorScheme: TuiColorScheme = TuiColorScheme(
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
    surface = Color.Unspecified,
    onSurface = Color(222, 227, 229),
    onSurfaceVariant = Color(191, 200, 202),
    surfaceContainer = Color(27, 33, 34),
    surfaceContainerHigh = Color(37, 43, 44),
    surfaceContainerHighest = Color(48, 54, 55),
    surfaceContainerHover = Color(43, 49, 50),
    surfaceContainerActive = Color(58, 64, 65),
    outline = Color(137, 146, 148),
    error = Color(255, 180, 171),
    onError = Color(105, 0, 5),
    success = Color(0, 227, 133),
    onSuccess = Color(0, 57, 29),
)

public actual val DefaultTuiColorScheme: TuiColorScheme = TuiColorScheme(
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
    surface = Color.Unspecified,
    onSurface = Color.White,
    onSurfaceVariant = Color(190, 190, 195),
    surfaceContainer = Color(42, 42, 46),
    surfaceContainerHigh = Color(58, 58, 64),
    surfaceContainerHighest = Color(62, 62, 66),
    surfaceContainerHover = Color(59, 59, 63),
    surfaceContainerActive = Color(76, 76, 79),
    outline = Color(96, 96, 102),
    error = Color.Red,
    onError = Color.White,
    success = Color.Green,
    onSuccess = Color.White,
)

public actual fun tuiColorSchemeFor(theme: Terminal.Theme): TuiColorScheme = when (theme) {
    Terminal.Theme.Light -> LightTuiColorScheme
    Terminal.Theme.Dark,
    Terminal.Theme.Unknown -> DarkTuiColorScheme
}

public actual val DefaultTuiTypography: TuiTypography = TuiTypography(
    headline = TextStyle.Bold,
    title = TextStyle.Bold,
    body = TextStyle.Unspecified,
    label = TextStyle.Unspecified,
    supporting = TextStyle.Dim,
)

@Composable
public actual fun TuiTheme(
    colorScheme: TuiColorScheme,
    typography: TuiTypography,
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(
        LocalTuiColorScheme provides colorScheme,
        LocalTuiTypography provides typography,
        content = content,
    )
}

public actual object TuiTheme {
    public actual val colorScheme: TuiColorScheme
        @Composable
        @ReadOnlyComposable
        get() = LocalTuiColorScheme.current

    public actual val typography: TuiTypography
        @Composable
        @ReadOnlyComposable
        get() = LocalTuiTypography.current
}

private val LocalTuiColorScheme = staticCompositionLocalOf { DefaultTuiColorScheme }
private val LocalTuiTypography = staticCompositionLocalOf { DefaultTuiTypography }
