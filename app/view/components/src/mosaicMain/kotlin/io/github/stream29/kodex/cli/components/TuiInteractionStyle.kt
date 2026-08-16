package io.github.stream29.kodex.cli.components

import com.jakewharton.mosaic.ui.TextStyle

/**
 * Resolves the shared terminal interaction style without changing focus presentation.
 *
 * Focus remains represented by the physical terminal cursor. Hover adds bold, press combines bold
 * with inverse video, selection uses inverse video, and disabled content is dim.
 */
public fun tuiInteractionTextStyle(
    enabled: Boolean = true,
    hovered: Boolean = false,
    pressed: Boolean = false,
    selected: Boolean = false,
    idleTextStyle: TextStyle = TextStyle.Unspecified,
): TextStyle = when {
    !enabled -> TextStyle.Dim
    pressed -> TextStyle.Invert + TextStyle.Bold
    selected && hovered -> TextStyle.Invert + TextStyle.Bold
    selected -> TextStyle.Invert
    hovered -> idleTextStyle + TextStyle.Bold
    else -> idleTextStyle
}
