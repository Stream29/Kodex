package io.github.stream29.kodex.cli.components

import com.jakewharton.mosaic.ui.TextStyle
import de.infix.testBalloon.framework.core.testSuite
import kotlin.test.assertEquals

val tuiInteractionStyleTest by testSuite {
    test("shared interaction states preserve terminal focus semantics") {
        assertEquals(TextStyle.Unspecified, tuiInteractionTextStyle())
        assertEquals(TextStyle.Bold, tuiInteractionTextStyle(hovered = true))
        assertEquals(
            TextStyle.Invert + TextStyle.Bold,
            tuiInteractionTextStyle(pressed = true),
        )
        assertEquals(TextStyle.Invert, tuiInteractionTextStyle(selected = true))
        assertEquals(
            TextStyle.Invert + TextStyle.Bold,
            tuiInteractionTextStyle(selected = true, hovered = true),
        )
        assertEquals(
            TextStyle.Invert + TextStyle.Bold,
            tuiInteractionTextStyle(selected = true, pressed = true, hovered = true),
        )
        assertEquals(TextStyle.Dim, tuiInteractionTextStyle(enabled = false))
    }

    test("idle style is retained only outside stronger interaction states") {
        assertEquals(
            TextStyle.Dim,
            tuiInteractionTextStyle(idleTextStyle = TextStyle.Dim),
        )
        assertEquals(
            TextStyle.Dim + TextStyle.Bold,
            tuiInteractionTextStyle(hovered = true, idleTextStyle = TextStyle.Dim),
        )
        assertEquals(
            TextStyle.Invert,
            tuiInteractionTextStyle(selected = true, idleTextStyle = TextStyle.Dim),
        )
    }
}
