@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package io.github.stream29.kodex.app.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.layout.onSizeChanged
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
    val adaptive = LocalAdaptiveEditor.current && multiline
    val density = LocalDensity.current.density
    var editorHeight by remember { mutableFloatStateOf(24f) }
    var editorWidth by remember { mutableIntStateOf(0) }
    var nativeInput by remember { mutableStateOf<HTMLTextAreaElement?>(null) }
    val measurement = remember { EditorMeasurementFrame() }
    val currentMeasure by rememberUpdatedState {
        val input = nativeInput
        if (adaptive && input != null && input.isConnected && editorWidth > 0) {
            val cssWidth = editorCssWidth(input)
            if (cssWidth > 0.0) {
                val cssHeight = measureAdaptiveEditorHeight(input, rows)
                val heightDp = (cssHeight * editorWidth / cssWidth / density).toFloat()
                if (kotlin.math.abs(editorHeight - heightDp) > 0.1f) editorHeight = heightDp
            }
        }
    }
    fun scheduleMeasure() {
        if (adaptive && measurement.id == null) {
            measurement.id = window.requestAnimationFrame {
                measurement.id = null
                currentMeasure()
            }
        }
    }
    DisposableEffect(adaptive) {
        val resize: (org.w3c.dom.events.Event) -> Unit = { scheduleMeasure() }
        val fonts = browserEditorFontEvents()
        window.addEventListener("resize", resize)
        fonts.addEventListener("loadingdone", resize)
        fonts.addEventListener("loadingerror", resize)
        onDispose {
            window.removeEventListener("resize", resize)
            fonts.removeEventListener("loadingdone", resize)
            fonts.removeEventListener("loadingerror", resize)
            measurement.id?.let(window::cancelAnimationFrame)
            measurement.id = null
        }
    }
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
            contentPadding = browserEditorContentPadding(adaptive = adaptive, multiline = multiline),
            label = if (label.isEmpty()) null else { { Text(label) } },
            innerTextField = {
                HtmlElementView(
                    modifier = Modifier.fillMaxWidth().height(if (adaptive) editorHeight.dp else (24f * rows).dp)
                        .onSizeChanged { if (editorWidth != it.width) { editorWidth = it.width; scheduleMeasure() } },
                    factory = {
                        (document.createElement("textarea") as HTMLTextAreaElement).apply {
                            nativeInput = this
                            style.cssText = "box-sizing:border-box;width:100%;height:100%;resize:none;" +
                                "font-size:16px;line-height:24px;font-weight:400;padding:0;" +
                                "border:0;outline:none;overflow:auto;background:transparent;"
                            style.fontFamily = BrowserInputFontFamily
                            setAttribute("autocomplete", "off")
                            setAttribute("autocapitalize", "off")
                            spellcheck = false
                            bridge.bindFocus { focused ->
                                if (focused && policy.enabled) bridge.focusNative(this)
                                else if (!focused) bridge.restoreCanvasAfterFocusChange(this)
                            }
                            onfocus = {
                                bridge.rememberCanvasForRelease(this)
                                if (!bridge.composeFocused) focus.requestFocus()
                            }
                            oninput = { bridge.publish(this, change); scheduleMeasure() }
                            onkeyup = { bridge.publish(this, change) }
                            onselect = { bridge.publish(this, change) }
                            addEventListener("selectionchange", { bridge.publish(this, change) })
                            addEventListener("compositionstart", { bridge.composing = true })
                            addEventListener("compositionend", {
                                bridge.composing = false
                                bridge.publish(this, change)
                                scheduleMeasure()
                            })
                            onkeydown = keyDown@{ event ->
                                val current = policy
                                if (bridge.routeBack(event, current.enabled, backAction)) return@keyDown
                                if (bridge.routeVerticalFocus(event, this, current.multiline, focusManager::moveFocus)) {
                                    return@keyDown
                                }
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
                        syncOverlayElement(input, singleLine = !multiline, adaptive = adaptive, maximumLines = rows)
                        input.setAttribute("aria-label", label.ifEmpty { "Message" })
                        input.style.color = (if (enabled) colors.onSurface else colors.onSurface.copy(alpha = 0.38f)).css()
                        input.style.setProperty("caret-color", colors.primary.css())
                        bridge.project(input, text, cursorOffset, revision, disabled = !enabled)
                        scheduleMeasure()
                        bridge.focus(input, autoFocus && interactive)
                    },
                    onRelease = { input ->
                        bridge.release()
                        nativeInput = null
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

@OptIn(ExperimentalMaterial3Api::class)
internal fun browserEditorContentPadding(adaptive: Boolean, multiline: Boolean): PaddingValues =
    when {
        adaptive -> PaddingValues(horizontal = 8.dp, vertical = 0.dp)
        !multiline -> PaddingValues(horizontal = 8.dp, vertical = 4.dp)
        else -> OutlinedTextFieldDefaults.contentPadding()
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
private class EditorMeasurementFrame {
    // Scheduling is not UI state: observing this token would schedule another measure on every echo.
    var id: Int? = null
}
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

    fun routeVerticalFocus(
        event: KeyboardEvent, input: HTMLTextAreaElement, multiline: Boolean,
        moveFocus: (FocusDirection) -> Boolean,
    ): Boolean {
        if (!alive || input.disabled || event.defaultPrevented ||
            (event.key != "ArrowUp" && event.key != "ArrowDown") ||
            event.shiftKey || event.ctrlKey || event.altKey || event.metaKey ||
            composing || event.isComposing || event.keyCode == 229 ||
            !shouldBridgeVerticalFocus(
                event.key, multiline, input.value, input.selectionStart, input.selectionEnd,
                input.scrollHeight, input.clientHeight,
            )
        ) return false
        val moved = moveFocus(if (event.key == "ArrowUp") FocusDirection.Up else FocusDirection.Down)
        if (moved) {
            event.preventDefault()
            if (input.nativeFocused()) input.focusCanvas()
        }
        return moved
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
        if (composeFocused && !input.disabled) focusNative(input)
        if (requested && !autoFocused) {
            focusFrame?.let(window::cancelAnimationFrame)
            focusFrame = window.requestAnimationFrame {
                focusFrame = null
                if (alive && input.isConnected && !input.disabled) input.focus()
            }
        }
        autoFocused = requested
    }

    fun focusNative(input: HTMLTextAreaElement) {
        if (!alive || !composeFocused || input.nativeFocused()) return
        if (input.isConnected && !input.disabled) input.focus()
        if (!input.nativeFocused() && focusFrame == null) {
            focusFrame = window.requestAnimationFrame {
                focusFrame = null
                if (alive && composeFocused && input.isConnected && !input.disabled && !input.nativeFocused()) {
                    input.focus()
                }
            }
        }
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

internal fun shouldBridgeVerticalFocus(
    key: String, multiline: Boolean, value: String, selectionStart: Int?, selectionEnd: Int?,
    scrollHeight: Int, clientHeight: Int,
): Boolean {
    if (key != "ArrowUp" && key != "ArrowDown") return false
    if (selectionStart == null || selectionStart != selectionEnd) return false
    if (!multiline || value.isEmpty()) return true
    if (value.contains('\n') || value.contains('\r')) return false
    if (clientHeight > 0 && scrollHeight > clientHeight + 2) return false
    return (key == "ArrowUp" && selectionStart == 0) ||
        (key == "ArrowDown" && selectionStart == value.length)
}

private fun HTMLTextAreaElement.activeCursor(): Int =
    (if (selectionDirection == "backward") selectionStart else selectionEnd) ?: value.length

internal fun editorCssWidth(input: HTMLTextAreaElement): Double =
    js("input.getBoundingClientRect().width")

internal fun adaptiveOverflowY(scrollHeight: Int, maxHeight: Int): String =
    if (scrollHeight > maxHeight) "auto" else "hidden"

internal fun syncOverlayElement(
    input: HTMLTextAreaElement, singleLine: Boolean, adaptive: Boolean, maximumLines: Int,
) {
    input.style.apply {
        whiteSpace = if (singleLine) "pre" else "pre-wrap"
        setProperty("overflow-wrap", if (singleLine) "normal" else "anywhere")
        setProperty("word-break", if (singleLine) "normal" else "break-word")
        setProperty("overflow-x", if (singleLine) "auto" else "hidden")
        setProperty("scrollbar-width", if (singleLine) "none" else "thin")
        setProperty("overflow-y", when {
            singleLine -> "hidden"
            adaptive -> adaptiveOverflowY(measureEditorHeight(input).roundToInt(), 24 * maximumLines)
            else -> "auto"
        })
    }
}

internal fun measureAdaptiveEditorHeight(input: HTMLTextAreaElement, maximumLines: Int): Double {
    val height = measureEditorHeight(input)
    input.style.setProperty("overflow-y", adaptiveOverflowY(height.roundToInt(), 24 * maximumLines))
    return height.coerceIn(24.0, 24.0 * maximumLines)
}

private fun browserEditorFontEvents(): org.w3c.dom.events.EventTarget = js("document.fonts")

internal fun measureEditorHeight(input: HTMLTextAreaElement): Double = js("""{
    const mirror = input.cloneNode(false);
    const width = input.getBoundingClientRect().width;
    mirror.value = input.value;
    mirror.style.cssText = input.style.cssText;
    mirror.style.setProperty("position", "fixed");
    mirror.style.setProperty("visibility", "hidden");
    mirror.style.setProperty("pointer-events", "none");
    mirror.style.setProperty("width", width + "px");
    mirror.style.setProperty("height", "0px");
    mirror.style.setProperty("overflow", "hidden");
    mirror.setAttribute("tabindex", "-1");
    mirror.setAttribute("aria-hidden", "true");
    input.parentElement.appendChild(mirror);
    const height = mirror.scrollHeight;
    mirror.remove();
    return height;
}""")

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
