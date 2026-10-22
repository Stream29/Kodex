package io.github.stream29.kodex.cli.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import com.jakewharton.mosaic.ui.Color

public actual val SessionButtonForeground: Color
    @Composable
    @ReadOnlyComposable
    get() = TuiTheme.colorScheme.onPrimaryContainer

public actual val SessionButtonBackground: Color
    @Composable
    @ReadOnlyComposable
    get() = TuiTheme.colorScheme.primaryContainer

public actual val SettingsDialogHomeBackground: Color
    @Composable
    @ReadOnlyComposable
    get() = TuiTheme.colorScheme.surfaceContainer
