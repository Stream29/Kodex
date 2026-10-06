package io.github.stream29.kodex.cli.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import com.jakewharton.mosaic.ui.Color

/** Shared by Agent controls and draft statusbar visual buttons. */
public val SessionButtonForeground: Color
    @Composable
    @ReadOnlyComposable
    get() = TuiTheme.colorScheme.onPrimaryContainer

public val SessionButtonBackground: Color
    @Composable
    @ReadOnlyComposable
    get() = TuiTheme.colorScheme.primaryContainer

/** Shared by Sidebar popup surfaces and the root's history confirmation surface. */
public val SettingsDialogHomeBackground: Color
    @Composable
    @ReadOnlyComposable
    get() = TuiTheme.colorScheme.surfaceContainer
