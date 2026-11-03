@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package io.github.stream29.kodex.app.ui

import de.infix.testBalloon.framework.core.testSuite
import kotlinx.browser.document
import org.w3c.dom.HTMLTextAreaElement
import kotlin.test.assertEquals
import kotlin.test.assertTrue

val browserEditorMeasurementTest by testSuite {
    test("native mirror measures hard and soft lines without changing selection") {
        val input = document.createElement("textarea") as HTMLTextAreaElement
        input.style.cssText = "box-sizing:border-box;width:120px;height:24px;resize:none;" +
            "font:16px/24px monospace;padding:0;border:0;overflow:auto"
        document.body!!.appendChild(input)
        try {
            for ((text, height) in listOf("" to 24.0, "x" to 24.0, "x\n" to 48.0,
                "1\n2\n3\n4\n5\n6" to 144.0, "1\n2\n3\n4\n5\n6\n7" to 168.0)) {
                input.value = text
                input.setSelectionRange(0, text.length)
                assertEquals(height, measureEditorHeight(input))
                assertEquals(0, input.selectionStart)
                assertEquals(text.length, input.selectionEnd)
                assertEquals(text, input.value)
            }
            input.value = "a".repeat(80)
            val narrow = measureEditorHeight(input)
            assertTrue(narrow > 24.0)
            input.style.width = "1000px"
            assertEquals(24.0, measureEditorHeight(input))
        } finally {
            input.remove()
        }
    }
}
