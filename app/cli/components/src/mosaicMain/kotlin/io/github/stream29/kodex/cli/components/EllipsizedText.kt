package io.github.stream29.kodex.cli.components

import androidx.compose.runtime.Composable
import com.jakewharton.mosaic.modifier.Modifier
import com.jakewharton.mosaic.ui.Color
import com.jakewharton.mosaic.ui.SubcomposeLayout
import com.jakewharton.mosaic.ui.Text
import com.jakewharton.mosaic.ui.TextStyle
import com.jakewharton.mosaic.ui.UnderlineStyle
import com.jakewharton.mosaic.ui.unit.constrainHeight
import com.jakewharton.mosaic.ui.unit.constrainWidth

/** Renders one hard line, adding an ellipsis when it exceeds the measured terminal-cell width. */
@Composable
public fun EllipsizedText(
    value: String,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    background: Color = Color.Unspecified,
    textStyle: TextStyle = TextStyle.Unspecified,
    underlineStyle: UnderlineStyle = UnderlineStyle.Unspecified,
    underlineColor: Color = Color.Unspecified,
) {
    require('\n' !in value && '\r' !in value) {
        "EllipsizedText only supports one hard line."
    }
    SubcomposeLayout(modifier = modifier) { constraints ->
        check(constraints.hasBoundedWidth) {
            "EllipsizedText must be measured with a finite maximum width."
        }
        val displayValue = value.ellipsizeToTerminalWidth(constraints.maxWidth)
        val placeable = subcompose(EllipsizedTextSlot) {
            Text(
                value = displayValue,
                color = color,
                background = background,
                textStyle = textStyle,
                underlineStyle = underlineStyle,
                underlineColor = underlineColor,
            )
        }.single().measure(
            constraints.copy(
                minWidth = 0,
                minHeight = 0,
            ),
        )
        layout(
            width = constraints.constrainWidth(placeable.width),
            height = constraints.constrainHeight(placeable.height),
        ) {
            placeable.place(0, 0)
        }
    }
}

private data object EllipsizedTextSlot
