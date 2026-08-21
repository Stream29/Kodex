package io.github.stream29.kodex.cli.components

import androidx.compose.runtime.Composable
import com.jakewharton.mosaic.modifier.Modifier
import com.jakewharton.mosaic.ui.Color
import com.jakewharton.mosaic.ui.Row
import com.jakewharton.mosaic.ui.TextStyle
import com.jakewharton.mosaic.ui.UnderlineStyle

/**
 * Renders one ellipsized hard line followed immediately by independently measured content.
 *
 * The trailing content is measured first and remains visible while [value] receives only the
 * remaining bounded width.
 */
@Composable
public fun EllipsizedTextWithTrailingContent(
    value: String,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    background: Color = Color.Unspecified,
    textStyle: TextStyle = TextStyle.Unspecified,
    underlineStyle: UnderlineStyle = UnderlineStyle.Unspecified,
    underlineColor: Color = Color.Unspecified,
    trailingContent: @Composable () -> Unit,
) {
    Row(modifier = modifier) {
        EllipsizedText(
            value = value,
            modifier = Modifier.weight(1f, fill = false),
            color = color,
            background = background,
            textStyle = textStyle,
            underlineStyle = underlineStyle,
            underlineColor = underlineColor,
        )
        trailingContent()
    }
}
