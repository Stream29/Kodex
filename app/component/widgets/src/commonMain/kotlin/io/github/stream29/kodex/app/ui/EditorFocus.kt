@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")

package io.github.stream29.kodex.app.ui

import androidx.compose.runtime.*

public actual class UiEditorFocusHandle internal actual constructor() {
    private var binding: EditorFocusBinding? = null

    public actual fun requestFocus(): Boolean = binding?.request?.invoke() ?: false

    internal fun bind(request: () -> Boolean): AutoCloseable {
        check(binding == null) { "An editor focus handle cannot bind multiple mounted editors." }
        val current = EditorFocusBinding(request)
        binding = current
        return object : AutoCloseable {
            override fun close() { if (binding === current) binding = null }
        }
    }
}

private class EditorFocusBinding(val request: () -> Boolean)

internal val LocalUiEditorFocusHandle = staticCompositionLocalOf<UiEditorFocusHandle?> { null }

@Composable
public actual fun rememberUiEditorFocusHandle(): UiEditorFocusHandle = remember { UiEditorFocusHandle() }

@Composable
public actual fun WithUiEditorFocus(handle: UiEditorFocusHandle, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalUiEditorFocusHandle provides handle, content = content)
}

@Composable
public actual fun BindUiEditorFocus(onRequest: () -> Boolean) {
    val handle = LocalUiEditorFocusHandle.current
    val request by rememberUpdatedState(onRequest)
    DisposableEffect(handle) {
        val registration = handle?.bind { request() }
        onDispose { registration?.close() }
    }
}
