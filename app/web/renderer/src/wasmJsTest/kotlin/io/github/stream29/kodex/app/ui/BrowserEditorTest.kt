@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class, androidx.compose.ui.ExperimentalComposeUiApi::class)

package io.github.stream29.kodex.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.window.ComposeViewport
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.jetbrains.skiko.wasm.onWasmReady
import androidx.compose.ui.focus.FocusDirection
import org.w3c.dom.HTMLElement
import org.w3c.dom.events.KeyboardEvent
import de.infix.testBalloon.framework.core.testSuite
import kotlinx.browser.document
import org.w3c.dom.HTMLTextAreaElement
import kotlin.test.*

val browserEditorTest by testSuite {
    test("initial mount focus handle focuses native feedback without autoFocus") {
        withContext(Dispatchers.Default) {
            val ready = CompletableDeferred<Unit>()
            onWasmReady { ready.complete(Unit) }
            withTimeout(10_000) { ready.await() }
            val host = document.createElement("div") as HTMLElement
            host.style.cssText = "position:fixed;left:0;top:0;width:600px;height:200px;z-index:10000"
            document.body!!.appendChild(host)
            var mounted by mutableStateOf(true)
            var requested = false
            try {
                ComposeViewport(host) {
                    MaterialTheme {
                        if (mounted) {
                            val handle = rememberUiEditorFocusHandle()
                            WithUiEditorFocus(handle) {
                                BrowserUiEditor(
                                    text = "", cursorOffset = 0, onChange = { _, _ -> null },
                                    onSubmit = { _, _ -> }, label = "Mount focus regression",
                                    enabled = true, readOnly = false, autoFocus = false,
                                    multiline = true, maximumLines = 2, submitKey = UiSubmitKey.CtrlEnter,
                                    revision = null,
                                )
                            }
                            SideEffect { if (!requested) requested = handle.requestFocus() }
                        }
                    }
                }
                withTimeout(10_000) {
                    while (!requested || !mountEditorFocused(host)) delay(20)
                }
                assertTrue(requested)
                assertTrue(mountEditorFocused(host))
            } finally {
                mounted = false
                delay(100)
                host.remove()
            }
        }
    }

    test("vertical focus eligibility preserves ambiguous multiline editing") {
        fun eligible(key: String, multiline: Boolean, value: String, start: Int?, end: Int? = start,
                     scroll: Int = 24, client: Int = 24) =
            shouldBridgeVerticalFocus(key, multiline, value, start, end, scroll, client)
        for (key in listOf("ArrowUp", "ArrowDown")) {
            assertTrue(eligible(key, true, "", 0))
            for (cursor in 0..3) assertTrue(eligible(key, false, "abc", cursor))
            assertFalse(eligible(key, false, "abc", null))
            assertFalse(eligible(key, true, "abc", 0, 3))
            for (value in listOf("a\nb", "a\rb", "a\r\nb")) {
                for (cursor in listOf(0, value.length)) assertFalse(eligible(key, true, value, cursor))
            }
            for (cursor in 0..3) assertFalse(eligible(key, true, "abc", cursor, scroll = 48))
            assertFalse(eligible(key, true, "abc", 1))
        }
        assertTrue(eligible("ArrowUp", true, "abc", 0))
        assertTrue(eligible("ArrowDown", true, "abc", 3))
        assertFalse(eligible("ArrowUp", true, "abc", 3))
        assertFalse(eligible("ArrowDown", true, "abc", 0))
        assertTrue(eligible("ArrowUp", true, "abc", 0, scroll = 26))
        assertFalse(eligible("ArrowUp", true, "abc", 0, scroll = 27))
        assertTrue(eligible("ArrowDown", true, "abc", 3, scroll = 48, client = 0))
        assertFalse(eligible("ArrowLeft", false, "", 0))
    }
    test("DOM vertical keys bridge only eligible selections and return focus to canvas on success") {
        verticalFocusDom { input, canvas ->
            val bridge = BrowserEditorBridge()
            val moves = mutableListOf<FocusDirection>()
            var multiline = true
            var succeeds = true
            input.onkeydown = { event -> bridge.routeVerticalFocus(event, input, multiline) {
                moves += it; succeeds
            } }
            fun dispatch(key: String, value: String, start: Int, end: Int = start, expected: Boolean) {
                input.value = value
                val normalizedValue = input.value
                input.setSelectionRange(start, end)
                input.focus()
                val count = moves.size
                val event = verticalEditorKey(key)
                assertEquals(!expected, input.dispatchEvent(event))
                assertEquals(expected, event.defaultPrevented)
                assertEquals(count + if (expected) 1 else 0, moves.size)
                assertEquals(if (expected) canvas else input, document.activeElement)
                assertEquals(normalizedValue, input.value)
                assertEquals(start, input.selectionStart)
                assertEquals(end, input.selectionEnd)
                if (expected) assertEquals(if (key == "ArrowUp") FocusDirection.Up else FocusDirection.Down, moves.last())
            }
            for (key in listOf("ArrowUp", "ArrowDown")) {
                dispatch(key, "", 0, expected = true)
                multiline = false
                for (cursor in 0..3) dispatch(key, "abc", cursor, expected = true)
                multiline = true
                dispatch(key, "abc", if (key == "ArrowUp") 0 else 3, expected = true)
                dispatch(key, "abc", 1, expected = false)
                dispatch(key, "abc", if (key == "ArrowUp") 3 else 0, expected = false)
                dispatch(key, "abc", 0, 3, expected = false)
                for (value in listOf("a\nb", "a\rb")) {
                    dispatch(key, value, 0, expected = false)
                    dispatch(key, value, value.length, expected = false)
                }
                input.wrap = "soft"
                syncOverlayElement(input, singleLine = false, adaptive = false, maximumLines = 1)
                input.value = "x".repeat(200)
                assertTrue(input.scrollHeight > input.clientHeight + 2)
                dispatch(key, input.value, if (key == "ArrowUp") 0 else input.value.length, expected = false)
            }
            input.value = ""
            input.setSelectionRange(0, 0)
            input.focus()
            succeeds = false
            val count = moves.size
            val event = verticalEditorKey("ArrowDown")
            assertTrue(input.dispatchEvent(event))
            assertFalse(event.defaultPrevented)
            assertEquals(count + 1, moves.size)
            assertEquals(input, document.activeElement)
            assertEquals(0, input.selectionStart)
            assertEquals(0, input.selectionEnd)
        }
    }
    test("DOM vertical keys preserve modifiers composition and already consumed events") {
        verticalFocusDom { input, _ ->
            val bridge = BrowserEditorBridge()
            var moves = 0
            input.onkeydown = { event -> bridge.routeVerticalFocus(event, input, true) { moves++; true } }
            input.focus()
            for (key in listOf("ArrowUp", "ArrowDown")) {
                for (modifier in listOf("shift", "ctrl", "alt", "meta", "composing", "229")) {
                    val event = verticalEditorKey(key, modifier)
                    assertTrue(input.dispatchEvent(event))
                    assertFalse(event.defaultPrevented)
                }
                bridge.composing = true
                assertTrue(input.dispatchEvent(verticalEditorKey(key)))
                bridge.composing = false
                val consumed = verticalEditorKey(key)
                consumed.preventDefault()
                input.dispatchEvent(consumed)
            }
            assertEquals(0, moves)
            assertEquals(input, document.activeElement)
            assertEquals("", input.value)
        }
    }
    test("adaptive overflow starts strictly above the six line cap") {
        for (height in listOf(0, 24, 120, 143, 144)) assertEquals("hidden", adaptiveOverflowY(height, 144))
        for (height in listOf(145, 168, 240)) assertEquals("auto", adaptiveOverflowY(height, 144))
    }
    test("single line scrolls horizontally without wrapping or visible scrollbars") {
        softWrapInput { input ->
            input.wrap = "off"
            input.value = "token".repeat(100)
            syncOverlayElement(input, singleLine = true, adaptive = false, maximumLines = 1)
            assertEquals("pre", input.style.whiteSpace)
            assertEquals("normal", input.style.getPropertyValue("overflow-wrap"))
            assertEquals("normal", input.style.getPropertyValue("word-break"))
            assertEquals("none", input.style.getPropertyValue("scrollbar-width"))
            assertEquals("auto", input.style.getPropertyValue("overflow-x"))
            assertEquals("hidden", input.style.getPropertyValue("overflow-y"))
            assertEquals(24.0, measureEditorHeight(input))
            assertTrue(input.scrollWidth > input.clientWidth)
            input.scrollLeft = 100.0
            assertTrue(input.scrollLeft > 0)
        }
    }
    test("long tokens wrap without changing text or selection and adaptive height shrinks") {
        softWrapInput { input ->
            input.wrap = "soft"
            syncOverlayElement(input, singleLine = false, adaptive = true, maximumLines = 6)
            for (text in listOf("https://example.test/" + "x".repeat(80), "/path/".repeat(30), "a".repeat(200))) {
                input.value = text
                input.setSelectionRange(2, text.length)
                val height = measureAdaptiveEditorHeight(input, 6)
                input.style.height = "${height}px"
                assertTrue(height > 24.0)
                assertTrue(input.scrollWidth <= input.clientWidth)
                assertEquals(text, input.value)
                assertEquals(2, input.selectionStart)
                assertEquals(text.length, input.selectionEnd)
            }
            for (lines in listOf(1, 2, 5, 6, 7, 10, 6, 3, 1)) {
                input.value = List(lines) { "x" }.joinToString("\n")
                val height = measureAdaptiveEditorHeight(input, 6)
                input.style.height = "${height}px"
                assertEquals(24.0 * minOf(lines, 6), height)
                assertEquals(if (lines > 6) "auto" else "hidden", input.style.getPropertyValue("overflow-y"))
                syncOverlayElement(input, singleLine = false, adaptive = true, maximumLines = 6)
                assertEquals(if (lines > 6) "auto" else "hidden", input.style.getPropertyValue("overflow-y"))
            }
        }
    }
}

private fun softWrapInput(block: (HTMLTextAreaElement) -> Unit) {
    val input = document.createElement("textarea") as HTMLTextAreaElement
    input.style.cssText = "box-sizing:border-box;width:120px;height:24px;resize:none;" +
        "font:16px/24px monospace;padding:0;border:0"
    document.body!!.appendChild(input)
    try { block(input) } finally { input.remove() }
}

private fun verticalFocusDom(block: (HTMLTextAreaElement, HTMLElement) -> Unit) {
    val host = document.createElement("div") as HTMLElement
    val canvas = document.createElement("canvas") as HTMLElement
    canvas.tabIndex = 0
    host.appendChild(canvas)
    document.body!!.appendChild(host)
    val input = document.createElement("textarea") as HTMLTextAreaElement
    input.style.cssText = "width:120px;height:24px;font:16px/24px monospace;padding:0;border:0"
    host.appendChild(input)
    try { block(input, canvas) } finally { input.onkeydown = null; host.remove() }
}

private fun verticalEditorKey(key: String, modifier: String = ""): KeyboardEvent = js("""new KeyboardEvent(
    "keydown", {key, code:key, bubbles:true, cancelable:true,
        shiftKey:modifier === "shift", ctrlKey:modifier === "ctrl", altKey:modifier === "alt",
        metaKey:modifier === "meta", isComposing:modifier === "composing", keyCode:modifier === "229" ? 229 : 0}
)""")

private fun mountEditorFocused(host: HTMLElement): Boolean = js("""(() => {
    function find(root) {
        for (const e of root.querySelectorAll('*')) {
            if (e.tagName === 'TEXTAREA' && e.getAttribute('aria-label') === 'Mount focus regression')
                return e.getRootNode().activeElement === e;
            if (e.shadowRoot && find(e.shadowRoot)) return true;
        }
        return false;
    }
    return find(host);
})()""")
