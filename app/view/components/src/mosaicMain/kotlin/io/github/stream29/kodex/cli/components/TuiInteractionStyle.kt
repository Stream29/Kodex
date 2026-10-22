package io.github.stream29.kodex.cli.components

import com.jakewharton.mosaic.ui.TextStyle

public actual fun tuiInteractionTextStyle(
    enabled: Boolean,
    hovered: Boolean,
    pressed: Boolean,
    selected: Boolean,
    idleTextStyle: TextStyle,
    interactionStyle: TuiInteractionStyle,
): TextStyle = when {
    !enabled -> TextStyle.Dim
    interactionStyle == TuiInteractionStyle.PreserveColors &&
        (pressed || selected || hovered) -> idleTextStyle + TextStyle.Bold

    pressed -> TextStyle.Invert + TextStyle.Bold
    selected && hovered -> TextStyle.Invert + TextStyle.Bold
    selected -> TextStyle.Invert
    hovered -> idleTextStyle + TextStyle.Bold
    else -> idleTextStyle
}
