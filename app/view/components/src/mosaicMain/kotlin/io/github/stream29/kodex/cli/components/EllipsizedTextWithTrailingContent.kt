package io.github.stream29.kodex.cli.components

import androidx.compose.runtime.Composable
import com.jakewharton.mosaic.modifier.Modifier
import com.jakewharton.mosaic.ui.Color
import com.jakewharton.mosaic.ui.Row
import com.jakewharton.mosaic.ui.TextStyle
import com.jakewharton.mosaic.ui.UnderlineStyle

@Composable
public actual fun EllipsizedTextWithTrailingContent(
    value: String,
    modifier: Modifier,
    color: Color,
    background: Color,
    textStyle: TextStyle,
    underlineStyle: UnderlineStyle,
    underlineColor: Color,
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
