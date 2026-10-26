@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package io.github.stream29.kodex.app.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.FocusInteraction
import androidx.compose.foundation.focusable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.*
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.viewinterop.HtmlElementView
import androidx.navigationevent.DirectNavigationEventInput
import androidx.navigationevent.compose.LocalNavigationEventDispatcherOwner
import io.github.stream29.kodex.app.ui.browser.BrowserInputFontFamily
import kotlinx.browser.document
import kotlinx.browser.window
import org.w3c.dom.HTMLTextAreaElement
import org.w3c.dom.HTMLElement
import org.w3c.dom.events.KeyboardEvent
import kotlin.math.roundToInt
import kotlin.js.js

@OptIn(ExperimentalComposeUiApi::class, ExperimentalMaterial3Api::class)
@Composable
internal fun BrowserUiEditor(
    text: String,
    cursorOffset: Int,
    onChange: (String, Int) -> Long?,
    onSubmit: (text: String, cursorOffset: Int) -> Unit,
    label: String,
    enabled: Boolean,
    readOnly: Boolean,
    autoFocus: Boolean,
    multiline: Boolean,
    maximumLines: Int,
    submitKey: UiSubmitKey,
    revision: Long?,
) {
    require(maximumLines > 0)
    require(cursorOffset in 0..text.length)
    val change by rememberUpdatedState(onChange)
    val submit by rememberUpdatedState(onSubmit)
    val obscured = isUiEditorObscured()
    val interactive = enabled && !obscured
    val policy by rememberUpdatedState(EditorPolicy(interactive, readOnly, multiline, submitKey))
    val bridge = remember { BrowserEditorBridge() }
    val focus = remember { FocusRequester() }
    BindUiEditorFocus {
        if (!policy.enabled) false else try { focus.requestFocus() } catch (_: IllegalStateException) { false }
    }
    val focusManager = LocalFocusManager.current
    val backAction by rememberUpdatedState(rememberBrowserEditorBackAction())
    val colors = MaterialTheme.colorScheme
    val rows = if (multiline) maximumLines else 1
    val interactions = remember { MutableInteractionSource() }
    var focusInteraction by remember { mutableStateOf<FocusInteraction.Focus?>(null) }
    Box(
        Modifier.fillMaxWidth().padding(top = if (label.isEmpty()) 0.dp else 8.dp)
            .focusRequester(focus).onFocusChanged {
                bridge.composeFocus(it.isFocused)
                if (it.isFocused && focusInteraction == null) {
                    FocusInteraction.Focus().also { event -> focusInteraction = event; interactions.tryEmit(event) }
                } else if (!it.isFocused) {
                    focusInteraction?.let { event -> interactions.tryEmit(FocusInteraction.Unfocus(event)) }
                    focusInteraction = null
                }
            }.focusable(interactive)
            .onPointerEvent(PointerEventType.Press) { if (interactive) focus.requestFocus() },
    ) {
        OutlinedTextFieldDefaults.DecorationBox(
            value = text, enabled = enabled, singleLine = !multiline,
            visualTransformation = VisualTransformation.None, interactionSource = interactions,
            label = if (label.isEmpty()) null else { { Text(label) } },
            innerTextField = {
                HtmlElementView(
                    modifier = Modifier.fillMaxWidth().height((24f * rows).dp),
                    factory = {
                        (document.createElement("textarea") as HTMLTextAreaElement).apply {
                            style.cssText = "box-sizing:border-box;width:100%;height:100%;resize:none;" +
                                "font-size:16px;line-height:24px;font-weight:400;padding:0;" +
                                "border:0;outline:none;overflow:auto;background:transparent;"
                            style.fontFamily = BrowserInputFontFamily
                            setAttribute("autocomplete", "off")
                            setAttribute("autocapitalize", "off")
                            spellcheck = false
                            bridge.bindFocus { focused ->
                                if (focused && isConnected && !disabled) this.focus()
                                else if (!focused) bridge.restoreCanvasAfterFocusChange(this)
                            }
                            onfocus = {
                                bridge.rememberCanvasForRelease(this)
                                if (!bridge.composeFocused) focus.requestFocus()
                            }
                            oninput = { bridge.publish(this, change) }
                            onkeyup = { bridge.publish(this, change) }
                            onselect = { bridge.publish(this, change) }
                            addEventListener("selectionchange", { bridge.publish(this, change) })
                            addEventListener("compositionstart", { bridge.composing = true })
                            addEventListener("compositionend", {
                                bridge.composing = false
                                bridge.publish(this, change)
                            })
                            onkeydown = keyDown@{ event ->
                                val current = policy
                                if (bridge.routeBack(event, current.enabled, backAction)) return@keyDown
                                if (event.key == "Tab" && !event.ctrlKey && !event.altKey && !event.metaKey &&
                                    !bridge.composing && !event.isComposing
                                ) {
                                    val direction = if (event.shiftKey) FocusDirection.Previous else FocusDirection.Next
                                    val moved = focusManager.moveFocus(direction)
                                    if (moved) {
                                        event.preventDefault()
                                        if (nativeFocused()) focusCanvas()
                                    }
                                } else if (event.key == "Enter" && current.enabled && !current.readOnly &&
                                    !bridge.composing && !event.isComposing && event.keyCode != 229
                                ) {
                                    val command = !event.altKey && !event.metaKey && !event.shiftKey &&
                                        event.ctrlKey == (current.submitKey == UiSubmitKey.CtrlEnter)
                                    val newline = current.multiline && !event.ctrlKey && !event.altKey && !event.metaKey &&
                                        event.shiftKey == (current.submitKey == UiSubmitKey.Enter)
                                    if (command) {
                                        event.preventDefault()
                                        bridge.publish(this, change)
                                        submit(value, activeCursor())
                                    } else if (!newline) {
                                        event.preventDefault()
                                    }
                                }
                            }
                        }
                    },
                    update = { input ->
                        configureBrowserEditorInteraction(input, enabled, obscured)
                        input.readOnly = readOnly
                        input.rows = rows
                        input.wrap = if (multiline) "soft" else "off"
                        input.setAttribute("aria-label", label.ifEmpty { "Message" })
                        input.style.color = (if (enabled) colors.onSurface else colors.onSurface.copy(alpha = 0.38f)).css()
                        input.style.setProperty("caret-color", colors.primary.css())
                        bridge.project(input, text, cursorOffset, revision, disabled = !enabled)
                        bridge.focus(input, autoFocus && interactive)
                    },
                    onRelease = { input ->
                        bridge.release()
                        input.oninput = null
                        input.onfocus = null
                        input.onkeyup = null
                        input.onselect = null
                        input.onkeydown = null
                    },
                )
            },
        )
    }
}

@Composable
private fun rememberBrowserEditorBackAction(): (() -> Unit)? {
    val owner = LocalNavigationEventDispatcherOwner.current
    val input = remember(owner) { owner?.let { DirectNavigationEventInput() } }
    DisposableEffect(owner, input) {
        if (input != null) owner!!.navigationEventDispatcher.addInput(input)
        onDispose { if (input != null) owner!!.navigationEventDispatcher.removeInput(input) }
    }
    return remember(input) { input?.let { { it.backCompleted() } } }
}

internal fun configureBrowserEditorInteraction(input: HTMLTextAreaElement, enabled: Boolean, obscured: Boolean) {
    val interactive = enabled && !obscured
    if (!interactive && input.nativeFocused()) input.focusCanvas()
    // The interop placement wrapper is above the canvas as well as its textarea.
    // Let only the visible textarea hit-test; an empty wrapper must not block dialogs.
    (input.parentElement as? HTMLElement)?.style?.setProperty("pointer-events", "none")
    input.style.setProperty("pointer-events", if (interactive) "auto" else "none")
    input.disabled = !interactive
    input.style.visibility = if (obscured) "hidden" else "visible"
    input.setAttribute("aria-hidden", obscured.toString())
}

private data class EditorPolicy(
    val enabled: Boolean, val readOnly: Boolean, val multiline: Boolean, val submitKey: UiSubmitKey,
)
private data class EditorProjection(val text: String, val cursor: Int, val revision: Long?)

/** The DOM owns editing; this bridge only tracks projection/acknowledgment and mount lifetime. */
internal class BrowserEditorBridge {
    var composing = false
    private var alive = true
    private var external: EditorProjection? = null
    private var acceptedRevision: Long? = null
    private var published: Pair<String, Int>? = null
    private var autoFocused = false
    private var focusFrame: Int? = null
    private var focusBinding: ((Boolean) -> Unit)? = null
    private var canvasFocusTask: Int? = null
    private var releaseCanvasFocus: (() -> Unit)? = null
    var composeFocused: Boolean = false
        private set

    fun bindFocus(handler: (Boolean) -> Unit) { focusBinding = handler }
    fun rememberCanvasForRelease(input: HTMLTextAreaElement) {
        val canvas = editorCanvas(input) ?: return
        releaseCanvasFocus = { restoreReleasedEditorCanvas(canvas, input) }
    }
    fun composeFocus(focused: Boolean) {
        if (!alive) return
        composeFocused = focused
        focusBinding?.invoke(focused)
    }

    fun restoreCanvasAfterFocusChange(input: HTMLTextAreaElement) {
        canvasFocusTask?.let(window::clearTimeout)
        canvasFocusTask = window.setTimeout({
            canvasFocusTask = null
            if (alive && !composeFocused && input.nativeFocused()) input.focusCanvas()
            null
        }, 0)
    }

    fun publish(input: HTMLTextAreaElement, change: (String, Int) -> Long?) {
        if (!alive || input.disabled) return
        val current = input.value to input.activeCursor()
        if (current == published) return
        published = current
        change(current.first, current.second)?.let { acceptedRevision = it }
    }

    fun routeBack(event: KeyboardEvent, enabled: Boolean, action: (() -> Unit)?): Boolean {
        if (!alive || !enabled || action == null || event.defaultPrevented || event.key != "Escape" ||
            event.ctrlKey || event.altKey || event.metaKey || event.shiftKey ||
            composing || event.isComposing || event.keyCode == 229
        ) return false
        event.preventDefault()
        event.stopPropagation()
        action()
        return true
    }

    fun project(input: HTMLTextAreaElement, text: String, cursor: Int, revision: Long?, disabled: Boolean = false) {
        val projection = EditorProjection(text, cursor, revision)
        // A model-disabled field acknowledges authority; temporary overlay hiding does not.
        if (projection == external && (!disabled || input.value == text)) return
        external = projection
        val accepted = acceptedRevision
        if (revision != null && accepted != null && revision < accepted) return
        // Native selection can advance between keydown and its select/keyup callback.
        // An exact acknowledgment must not replay the earlier cursor over that movement.
        if (published == (text to cursor) && (revision == null || revision == accepted)) return
        if (revision != null) acceptedRevision = revision
        if (input.value != text) input.value = text
        if (input.activeCursor() != cursor) input.setSelectionRange(cursor, cursor)
        published = text to cursor
    }

    fun focus(input: HTMLTextAreaElement, requested: Boolean) {
        if (requested && !autoFocused) {
            focusFrame = window.requestAnimationFrame {
                focusFrame = null
                if (alive && input.isConnected && !input.disabled) input.focus()
            }
        }
        autoFocused = requested
    }

    fun release() {
        alive = false
        releaseCanvasFocus?.invoke()
        releaseCanvasFocus = null
        focusFrame?.let(window::cancelAnimationFrame)
        focusFrame = null
        focusBinding = null
        canvasFocusTask?.let(window::clearTimeout)
        canvasFocusTask = null
    }
}

private fun HTMLTextAreaElement.activeCursor(): Int =
    (if (selectionDirection == "backward") selectionStart else selectionEnd) ?: value.length

private fun HTMLTextAreaElement.nativeFocused(): Boolean = nativeInputFocused(this)

private fun nativeInputFocused(input: HTMLTextAreaElement): Boolean =
    js("input.getRootNode().activeElement === input")

private fun HTMLTextAreaElement.focusCanvas() {
    editorCanvas(this)?.focus()
}

private fun editorCanvas(input: HTMLTextAreaElement): HTMLElement? = js("""{
    const find = root => {
        const direct = root.querySelector("canvas");
        if (direct) return direct;
        if (root.shadowRoot) {
            const own = find(root.shadowRoot);
            if (own) return own;
        }
        for (const node of root.querySelectorAll("*")) {
            if (node.shadowRoot) {
                const nested = find(node.shadowRoot);
                if (nested) return nested;
            }
        }
        return null;
    };
    for (let root = input.parentElement; root; root = root.parentElement) {
        const canvas = find(root);
        if (canvas) return canvas;
    }
    return null;
}""")

private fun restoreReleasedEditorCanvas(canvas: HTMLElement, input: HTMLTextAreaElement): Unit = js("""{
    if (canvas.isConnected &&
        (input.getRootNode().activeElement === input || document.activeElement === document.body)) {
        canvas.focus();
    }
}""")

private fun Color.css(): String =
    "rgba(${(red * 255).roundToInt()},${(green * 255).roundToInt()},${(blue * 255).roundToInt()},$alpha)"
