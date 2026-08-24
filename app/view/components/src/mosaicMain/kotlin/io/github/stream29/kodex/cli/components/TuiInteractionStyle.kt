package io.github.stream29.kodex.cli.components

import com.jakewharton.mosaic.ui.TextStyle

/**
 * Controls whether interaction feedback may exchange semantic foreground and container colors.
 */
public enum class TuiInteractionStyle {
    /** Uses terminal inverse video for pressed and selected states. */
    TerminalInvert,

    /** Preserves caller-provided colors and expresses interaction through text emphasis. */
    PreserveColors,
}

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
    interactionStyle: TuiInteractionStyle = TuiInteractionStyle.TerminalInvert,
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
