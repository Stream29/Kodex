package io.github.stream29.kodex.app.ui

import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.OutlinedTextFieldDefaults
import de.infix.testBalloon.framework.core.testSuite
import kotlin.test.assertEquals

val browserEditorPaddingTest by testSuite {
    test("adaptive composer keeps its height and adds horizontal inset") {
        val padding = browserEditorContentPadding(adaptive = true, multiline = true)
        assertEquals(8.dp, padding.calculateLeftPadding(LayoutDirection.Ltr))
        assertEquals(8.dp, padding.calculateRightPadding(LayoutDirection.Ltr))
        assertEquals(0.dp, padding.calculateTopPadding())
        assertEquals(0.dp, padding.calculateBottomPadding())
    }

    test("single-line fields use compact balanced padding") {
        val padding = browserEditorContentPadding(adaptive = false, multiline = false)
        assertEquals(8.dp, padding.calculateLeftPadding(LayoutDirection.Ltr))
        assertEquals(8.dp, padding.calculateRightPadding(LayoutDirection.Rtl))
        assertEquals(4.dp, padding.calculateTopPadding())
        assertEquals(4.dp, padding.calculateBottomPadding())
    }

    test("other multiline editors retain their existing padding") {
        @OptIn(ExperimentalMaterial3Api::class)
        val expected = OutlinedTextFieldDefaults.contentPadding()
        val padding = browserEditorContentPadding(adaptive = false, multiline = true)
        assertEquals(expected.calculateLeftPadding(LayoutDirection.Ltr), padding.calculateLeftPadding(LayoutDirection.Ltr))
        assertEquals(expected.calculateTopPadding(), padding.calculateTopPadding())
        assertEquals(expected.calculateRightPadding(LayoutDirection.Ltr), padding.calculateRightPadding(LayoutDirection.Ltr))
        assertEquals(expected.calculateBottomPadding(), padding.calculateBottomPadding())
    }
}
