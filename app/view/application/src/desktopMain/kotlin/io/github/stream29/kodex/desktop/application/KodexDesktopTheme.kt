package io.github.stream29.kodex.desktop.application

import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val KodexLightColors = lightColorScheme(
    primary = Color(0xFF175F65),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFC3EAED),
    onPrimaryContainer = Color(0xFF0B4B51),
    secondary = Color(0xFF4B6265),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFCFE7E9),
    onSecondaryContainer = Color(0xFF334B4E),
    tertiary = Color(0xFF496179),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFD0E4FF),
    onTertiaryContainer = Color(0xFF314960),
    error = Color(0xFFBA1A1A),
    onError = Color.White,
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF93000A),
    background = Color(0xFFF6FAFA),
    onBackground = Color(0xFF181C1D),
    surface = Color(0xFFF6FAFA),
    onSurface = Color(0xFF181C1D),
    surfaceVariant = Color(0xFFDAE4E5),
    onSurfaceVariant = Color(0xFF3F494A),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFF0F4F4),
    surfaceContainer = Color(0xFFEAEEEE),
    surfaceContainerHigh = Color(0xFFE4E9E9),
    surfaceContainerHighest = Color(0xFFDEE3E3),
    surfaceBright = Color(0xFFF6FAFA),
    surfaceDim = Color(0xFFD6DBDB),
    surfaceTint = Color(0xFF175F65),
    inverseSurface = Color(0xFF2D3131),
    inverseOnSurface = Color(0xFFEDF1F1),
    inversePrimary = Color(0xFF8BD1D7),
    outline = Color(0xFF6F797A),
    outlineVariant = Color(0xFFBEC8C9),
    scrim = Color.Black,
)

private val KodexDarkColors = darkColorScheme(
    primary = Color(0xFF8BD1D7),
    onPrimary = Color(0xFF00363A),
    primaryContainer = Color(0xFF1C444A),
    onPrimaryContainer = Color(0xFFC3EAED),
    secondary = Color(0xFFB3CBCD),
    onSecondary = Color(0xFF1E3437),
    secondaryContainer = Color(0xFF244E54),
    onSecondaryContainer = Color(0xFFCFE7E9),
    tertiary = Color(0xFFB1C9E5),
    onTertiary = Color(0xFF1A3249),
    tertiaryContainer = Color(0xFF314960),
    onTertiaryContainer = Color(0xFFD0E4FF),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF5B252B),
    onErrorContainer = Color(0xFFFFDAD6),
    background = Color(0xFF101414),
    onBackground = Color(0xFFE0E3E3),
    surface = Color(0xFF101414),
    onSurface = Color(0xFFE0E3E3),
    surfaceVariant = Color(0xFF3F494A),
    onSurfaceVariant = Color(0xFFBEC8C9),
    surfaceContainerLowest = Color(0xFF0B0F0F),
    surfaceContainerLow = Color(0xFF181C1C),
    surfaceContainer = Color(0xFF1C2020),
    surfaceContainerHigh = Color(0xFF262A2A),
    surfaceContainerHighest = Color(0xFF313535),
    surfaceBright = Color(0xFF363A3A),
    surfaceDim = Color(0xFF101414),
    surfaceTint = Color(0xFF8BD1D7),
    inverseSurface = Color(0xFFE0E3E3),
    inverseOnSurface = Color(0xFF2D3131),
    inversePrimary = Color(0xFF175F65),
    outline = Color(0xFF899394),
    outlineVariant = Color(0xFF3F494A),
    scrim = Color.Black,
)

/** Kodex Material 3 Expressive theme for a resolved platform appearance. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
public fun KodexDesktopTheme(
    darkTheme: Boolean,
    content: @Composable () -> Unit,
): Unit {
    MaterialExpressiveTheme(
        colorScheme = if (darkTheme) KodexDarkColors else KodexLightColors,
        content = content,
    )
}
