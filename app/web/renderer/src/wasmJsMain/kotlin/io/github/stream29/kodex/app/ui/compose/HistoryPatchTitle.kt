package io.github.stream29.kodex.app.ui.compose

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight

internal val LocalHistoryPatchTitle = staticCompositionLocalOf { false }

@Composable
internal fun historyPatchTitleStyle(): TextStyle =
    historyToolTitleStyle()

@Composable
internal fun historyToolTitleStyle(): TextStyle =
    MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold)
