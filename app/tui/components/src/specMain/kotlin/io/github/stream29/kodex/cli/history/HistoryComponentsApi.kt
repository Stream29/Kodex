package io.github.stream29.kodex.cli.history

import androidx.compose.runtime.Composable
import com.jakewharton.mosaic.ui.Color
import com.jakewharton.mosaic.ui.TextStyle

/**
 * Shared read-only History/Index typography. Mosaic Text clips instead of wrapping;
 * subcomposition from the finite incoming width keeps lazy rows independently measurable.
 */
@Composable
public expect fun WrappedHistoryText(
    value: String,
    textStyle: TextStyle = TextStyle.Unspecified,
    color: Color = Color.Unspecified,
): Unit
