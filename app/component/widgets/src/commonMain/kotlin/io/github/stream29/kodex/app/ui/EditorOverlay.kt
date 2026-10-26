package io.github.stream29.kodex.app.ui

import androidx.compose.runtime.*

internal class EditorOverlayStack {
    private val layers = mutableStateListOf<Int>()
    fun obscures(depth: Int): Boolean = layers.any { it > depth }
    fun enter(depth: Int): AutoCloseable {
        require(depth > 0)
        layers.add(depth)
        var closed = false
        return AutoCloseable {
            if (!closed) {
                closed = true
                check(layers.remove(depth))
            }
        }
    }
}

internal val LocalEditorOverlayStack = staticCompositionLocalOf<EditorOverlayStack?> { null }
internal val LocalEditorOverlayDepth = staticCompositionLocalOf { 0 }

@Composable
public actual fun WithUiEditorSurface(content: @Composable () -> Unit) {
    val stack = remember { EditorOverlayStack() }
    CompositionLocalProvider(LocalEditorOverlayStack provides stack, LocalEditorOverlayDepth provides 0, content = content)
}

@Composable
public actual fun UiEditorOverlay(content: @Composable () -> Unit) {
    val stack = LocalEditorOverlayStack.current
    val depth = LocalEditorOverlayDepth.current + 1
    DisposableEffect(stack, depth) {
        val registration = stack?.enter(depth)
        onDispose { registration?.close() }
    }
    CompositionLocalProvider(LocalEditorOverlayDepth provides depth, content = content)
}


@Composable
public actual fun isUiEditorObscured(): Boolean =
    LocalEditorOverlayStack.current?.obscures(LocalEditorOverlayDepth.current) == true
