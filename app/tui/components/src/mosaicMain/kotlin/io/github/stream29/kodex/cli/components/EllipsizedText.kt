package io.github.stream29.kodex.cli.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.jakewharton.mosaic.modifier.Modifier
import com.jakewharton.mosaic.ui.Color
import com.jakewharton.mosaic.ui.SubcomposeLayout
import com.jakewharton.mosaic.ui.Text
import com.jakewharton.mosaic.ui.TextStyle
import com.jakewharton.mosaic.ui.UnderlineStyle
import com.jakewharton.mosaic.ui.unit.constrainHeight
import com.jakewharton.mosaic.ui.unit.constrainWidth

@Composable
public actual fun EllipsizedText(
    value: String,
    modifier: Modifier,
    color: Color,
    background: Color,
    textStyle: TextStyle,
    underlineStyle: UnderlineStyle,
    underlineColor: Color,
) {
    require('\n' !in value && '\r' !in value) {
        "EllipsizedText only supports one hard line."
    }
    val contentCache = remember(color, background, textStyle, underlineStyle, underlineColor) {
        EllipsizedTextContentCache { displayValue ->
            {
                Text(
                    value = displayValue,
                    color = color,
                    background = background,
                    textStyle = textStyle,
                    underlineStyle = underlineStyle,
                    underlineColor = underlineColor,
                )
            }
        }
    }
    SubcomposeLayout(modifier = modifier) { constraints ->
        check(constraints.hasBoundedWidth) {
            "EllipsizedText must be measured with a finite maximum width."
        }
        val displayValue = value.ellipsizeToTerminalWidth(constraints.maxWidth)
        val placeable = subcompose(
            EllipsizedTextSlot,
            content = contentCache.contentFor(displayValue),
        ).single().measure(
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

// SubcomposeLayout compares content by identity. Cache one immutable capture, not snapshot state:
// recreating it on every measure recomposes Text and requests another layout during that layout.
private class EllipsizedTextContentCache(
    private val createContent: (String) -> (@Composable () -> Unit),
) {
    private var value: String? = null
    private var content: (@Composable () -> Unit)? = null

    fun contentFor(displayValue: String): @Composable () -> Unit {
        val cached = content
        if (cached != null && value == displayValue) return cached
        return createContent(displayValue).also {
            value = displayValue
            content = it
        }
    }
}

private data object EllipsizedTextSlot
