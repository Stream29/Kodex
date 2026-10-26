@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package io.github.stream29.kodex.app.ui

import de.infix.testBalloon.framework.core.testSuite
import kotlinx.browser.document
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLTextAreaElement
import org.w3c.dom.events.KeyboardEvent
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

val browserEditorBridgeTest by testSuite {
    test("disabling a value field restores an unchanged owner after a rejected same-length edit") {
        val input = document.createElement("textarea") as HTMLTextAreaElement
        val bridge = BrowserEditorBridge()
        bridge.project(input, "kept", 4, null)
        input.value = "lost"
        input.setSelectionRange(4, 4)
        var changes = 0
        bridge.publish(input) { _, _ -> changes++; null }
        input.disabled = true
        bridge.project(input, "kept", 4, null, disabled = true)
        assertEquals("kept", input.value)
        assertEquals(4, input.selectionEnd)
        input.disabled = false
        bridge.publish(input) { _, _ -> error("Restoring authority is not another user edit.") }
        assertEquals(1, changes)
    }

    test("disabled authority does not roll back an acknowledged revision with an older echo") {
        val input = document.createElement("textarea") as HTMLTextAreaElement
        val bridge = BrowserEditorBridge()
        bridge.project(input, "kept", 4, 1)
        input.value = "next"
        input.setSelectionRange(4, 4)
        bridge.publish(input) { _, _ -> 2L }
        input.disabled = true
        bridge.project(input, "kept", 4, 1, disabled = true)
        assertEquals("next", input.value)
        bridge.project(input, "next", 4, 2, disabled = true)
        assertEquals("next", input.value)
    }

    test("an overlay hides input without treating a pending unrevisioned echo as rejection") {
        val input = document.createElement("textarea") as HTMLTextAreaElement
        val bridge = BrowserEditorBridge()
        bridge.project(input, "kept", 4, null)
        input.value = "next"
        input.setSelectionRange(4, 4)
        bridge.publish(input) { _, _ -> null }
        configureBrowserEditorInteraction(input, enabled = true, obscured = true)
        assertTrue(input.disabled)
        bridge.project(input, "kept", 4, null)
        assertEquals("next", input.value)
        bridge.project(input, "next", 4, null)
        assertEquals("next", input.value)
    }

    test("native Escape routes back once without bubbling into the canvas host") {
        val parent = document.createElement("div")
        val input = document.createElement("textarea")
        parent.appendChild(input)
        document.body!!.appendChild(parent)
        val bridge = BrowserEditorBridge()
        var backs = 0
        var bubbled = 0
        input.addEventListener("keydown", { bridge.routeBack(it as KeyboardEvent, true) { backs++ } })
        parent.addEventListener("keydown", { bubbled++ })
        try {
            val event = browserEditorKey("Escape", false, false, 27)
            assertFalse(input.dispatchEvent(event))
            assertTrue(event.defaultPrevented)
            assertEquals(1, backs)
            assertEquals(0, bubbled)
        } finally { bridge.release(); parent.remove() }
    }

    test("Escape preserves IME cancellation, modifiers and disabled or released owners") {
        val bridge = BrowserEditorBridge()
        val action = { error("This event must not navigate.") }
        bridge.composing = true
        assertFalse(bridge.routeBack(browserEditorKey("Escape", false, false, 27), true, action))
        bridge.composing = false
        assertFalse(bridge.routeBack(browserEditorKey("Escape", true, false, 27), true, action))
        assertFalse(bridge.routeBack(browserEditorKey("Escape", false, true, 27), true, action))
        assertFalse(bridge.routeBack(browserEditorKey("Escape", false, false, 229), true, action))
        assertFalse(bridge.routeBack(browserEditorKey("Escape", false, false, 27), false, action))
        assertFalse(bridge.routeBack(browserEditorKey("Enter", false, false, 13), true, action))
        assertFalse(bridge.routeBack(browserEditorKey("Escape", false, false, 27), true, null))
        val consumed = browserEditorKey("Escape", false, false, 27)
        consumed.preventDefault()
        assertFalse(bridge.routeBack(consumed, true, action))
        bridge.release()
        assertFalse(bridge.routeBack(browserEditorKey("Escape", false, false, 27), true, action))
    }

    test("single-row value fields preserve pasted text and native UTF-16 selection without a revision") {
        val input = document.createElement("textarea") as HTMLTextAreaElement
        input.rows = 1
        input.wrap = "off"
        val bridge = BrowserEditorBridge()
        bridge.project(input, "", 0, null)
        input.value = "A\n\uD83D\uDE00\nB"
        input.setSelectionRange(2, 5, "backward")
        val changes = mutableListOf<Pair<String, Int>>()
        bridge.publish(input) { value, cursor -> changes += value to cursor; null }
        assertEquals("A\n\uD83D\uDE00\nB", input.value)
        assertEquals(2, input.selectionStart)
        assertEquals(5, input.selectionEnd)
        assertEquals("backward", input.selectionDirection)
        assertEquals(listOf("A\n\uD83D\uDE00\nB" to 2), changes)
        bridge.project(input, "A\n\uD83D\uDE00\nB", 2, null)
        assertEquals(5, input.selectionEnd, "An equal value echo preserves the selected range.")
    }

    test("a value-only field echo does not reset composition or selection") {
        val input = document.createElement("textarea") as HTMLTextAreaElement
        val bridge = BrowserEditorBridge()
        bridge.project(input, "", 0, null)
        input.value = "中文"
        input.setSelectionRange(0, 1, "backward")
        bridge.composing = true
        bridge.publish(input) { _, _ -> null }
        bridge.project(input, "中文", 0, null)
        assertEquals("中文", input.value)
        assertEquals(0, input.selectionStart)
        assertEquals(1, input.selectionEnd)
        assertTrue(bridge.composing)
    }

    test("disabled and released value-only fields cannot change the owner") {
        val input = document.createElement("textarea") as HTMLTextAreaElement
        val bridge = BrowserEditorBridge()
        input.value = "retained\ntext"
        input.disabled = true
        bridge.publish(input) { _, _ -> error("Disabled input") }
        assertEquals("retained\ntext", input.value)
        input.disabled = false
        bridge.release()
        bridge.publish(input) { _, _ -> error("Released input") }
        assertEquals("retained\ntext", input.value)
    }

    test("single-row HTML-looking text remains a literal value") {
        val input = document.createElement("textarea") as HTMLTextAreaElement
        val bridge = BrowserEditorBridge()
        input.value = "<img src=x onerror=alert(1)>\n<script>bad()</script>"
        bridge.publish(input) { _, _ -> null }
        assertEquals("<img src=x onerror=alert(1)>\n<script>bad()</script>", input.value)
        assertEquals(0, input.children.length)
    }

    test("input acknowledgment is synchronous and rejects an older changed projection") {
        val input = document.createElement("textarea") as HTMLTextAreaElement
        val bridge = BrowserEditorBridge()
        bridge.project(input, "abcd", 4, 1)
        input.value = "abXcd"
        input.setSelectionRange(3, 3)
        val changes = mutableListOf<Pair<String, Int>>()
        bridge.publish(input) { text, cursor -> changes += text to cursor; 2L }
        assertEquals(listOf("abXcd" to 3), changes)
        bridge.project(input, "stale", 1, 1)
        assertEquals("abXcd", input.value)
        assertEquals(3, input.selectionEnd)
        bridge.project(input, "reset", 0, 3)
        assertEquals("reset", input.value)
        assertEquals(0, input.selectionEnd)
        bridge.project(input, "late echo", 4, 2)
        assertEquals("reset", input.value)
        assertEquals(1, changes.size)
    }

    test("equal echo preserves backward selection and the composing lifetime") {
        val input = document.createElement("textarea") as HTMLTextAreaElement
        val bridge = BrowserEditorBridge()
        bridge.project(input, "abcd", 4, 1)
        input.setSelectionRange(1, 3, "backward")
        bridge.composing = true
        bridge.publish(input) { _, cursor -> assertEquals(1, cursor); 2L }
        bridge.project(input, "abcd", 1, 2)
        assertEquals(1, input.selectionStart)
        assertEquals(3, input.selectionEnd)
        assertEquals("backward", input.selectionDirection)
        assertTrue(bridge.composing)
    }

    test("external reset and released callbacks never publish another draft") {
        val input = document.createElement("textarea") as HTMLTextAreaElement
        val bridge = BrowserEditorBridge()
        bridge.project(input, "draft", 5, 1)
        bridge.project(input, "", 0, 2)
        bridge.publish(input) { _, _ -> error("An external projection is not user input.") }
        bridge.release()
        input.value = "late input"
        bridge.publish(input) { _, _ -> error("A released editor must not call its old owner.") }
    }

    test("consecutive cursor changes retain exact synchronous acknowledgments") {
        val input = document.createElement("textarea") as HTMLTextAreaElement
        val bridge = BrowserEditorBridge()
        bridge.project(input, "abcd", 4, 1)
        val offsets = mutableListOf<Int>()
        var revision = 1L
        for (offset in listOf(3, 2)) {
            input.setSelectionRange(offset, offset)
            bridge.publish(input) { _, cursor -> offsets += cursor; ++revision }
        }
        bridge.project(input, "abcd", 3, 2)
        assertEquals(2, input.selectionEnd)
        bridge.project(input, "abcd", 2, 3)
        bridge.publish(input) { _, _ -> error("An acknowledged cursor is not another edit.") }
        assertEquals(listOf(3, 2), offsets)
    }

    test("an exact acknowledgment cannot roll back native movement before keyup") {
        val input = document.createElement("textarea") as HTMLTextAreaElement
        val bridge = BrowserEditorBridge()
        bridge.project(input, "abcd", 4, 1)
        input.setSelectionRange(3, 3)
        bridge.publish(input) { _, _ -> 2L }
        input.setSelectionRange(2, 2)
        bridge.project(input, "abcd", 3, 2)
        assertEquals(2, input.selectionEnd)
        var observed = -1
        bridge.publish(input) { _, cursor -> observed = cursor; 3L }
        assertEquals(2, observed)
        input.setSelectionRange(1, 1)
        bridge.project(input, "abcd", 2, 4)
        assertEquals(2, input.selectionEnd, "A newer external command remains authoritative.")
    }

    test("released focus bindings cannot activate an old native element") {
        val bridge = BrowserEditorBridge()
        val focused = mutableListOf<Boolean>()
        bridge.bindFocus { focused += it }
        bridge.composeFocus(true)
        bridge.release()
        bridge.composeFocus(false)
        assertEquals(listOf(true), focused)
    }

    test("removing the focused DOM leaf restores its captured canvas after detachment") {
        val host = document.createElement("div") as HTMLElement
        val canvas = document.createElement("canvas") as HTMLElement
        val input = document.createElement("textarea") as HTMLTextAreaElement
        val other = document.createElement("textarea") as HTMLTextAreaElement
        canvas.tabIndex = 0
        host.appendChild(canvas)
        host.appendChild(input)
        host.appendChild(other)
        document.body!!.appendChild(host)
        try {
            val bridge = BrowserEditorBridge()
            input.focus()
            assertTrue(document.activeElement === input)
            bridge.rememberCanvasForRelease(input)
            input.remove()
            assertTrue(document.activeElement === document.body)
            bridge.release()
            assertTrue(document.activeElement === canvas)
            other.focus()
            bridge.release()
            assertTrue(document.activeElement === other, "Repeated release must not restore an old canvas.")
        } finally { host.remove() }
    }

    test("release neither steals another DOM editor nor focuses a detached canvas") {
        val host = document.createElement("div") as HTMLElement
        val canvas = document.createElement("canvas") as HTMLElement
        val input = document.createElement("textarea") as HTMLTextAreaElement
        val other = document.createElement("textarea") as HTMLTextAreaElement
        canvas.tabIndex = 0
        host.appendChild(canvas)
        host.appendChild(input)
        host.appendChild(other)
        document.body!!.appendChild(host)
        try {
            val bridge = BrowserEditorBridge()
            input.focus()
            bridge.rememberCanvasForRelease(input)
            other.focus()
            input.remove()
            bridge.release()
            assertTrue(document.activeElement === other)
            val detached = BrowserEditorBridge()
            detached.rememberCanvasForRelease(other)
            canvas.remove()
            other.remove()
            detached.release()
            assertTrue(document.activeElement === document.body)
        } finally { host.remove() }
    }

    test("obscured interop wrapper cannot intercept pointer or keyboard input") {
        val host = document.createElement("div") as HTMLElement
        val underneath = document.createElement("button") as HTMLElement
        val placement = document.createElement("div") as HTMLElement
        val input = document.createElement("textarea") as HTMLTextAreaElement
        val canvas = document.createElement("canvas") as HTMLElement
        host.style.cssText = "position:fixed;left:0;top:0;width:100px;height:100px;z-index:999999;pointer-events:none"
        underneath.style.cssText = "position:absolute;left:0;top:0;width:100px;height:100px;pointer-events:auto"
        placement.style.cssText = "position:absolute;left:0;top:0;width:100px;height:100px;pointer-events:auto;visibility:visible"
        input.style.cssText = "width:100px;height:100px;box-sizing:border-box"
        canvas.tabIndex = 0
        host.appendChild(canvas)
        host.appendChild(underneath)
        host.appendChild(placement)
        placement.appendChild(input)
        document.body!!.appendChild(host)
        try {
            input.value = "retained draft"
            input.setSelectionRange(3, 3)
            configureBrowserEditorInteraction(input, enabled = true, obscured = false)
            assertTrue(document.elementFromPoint(20.0, 20.0) === input)
            input.focus()
            configureBrowserEditorInteraction(input, enabled = true, obscured = true)
            assertTrue(document.activeElement === canvas)
            assertTrue(input.disabled)
            assertEquals("true", input.getAttribute("aria-hidden"))
            assertTrue(document.elementFromPoint(20.0, 20.0) === underneath)
            configureBrowserEditorInteraction(input, enabled = true, obscured = false)
            assertFalse(input.disabled)
            assertEquals("retained draft", input.value)
            assertEquals(3, input.selectionEnd)
            assertTrue(document.activeElement === canvas, "Uncovering alone must not focus an editor.")
            assertTrue(document.elementFromPoint(20.0, 20.0) === input)
        } finally { host.remove() }
    }
}

@JsFun("""(key, isComposing, ctrlKey, keyCode) =>
    new KeyboardEvent("keydown", {key, isComposing, ctrlKey, keyCode, bubbles: true, cancelable: true})""")
private external fun browserEditorKey(key: String, isComposing: Boolean, ctrlKey: Boolean, keyCode: Int): KeyboardEvent
