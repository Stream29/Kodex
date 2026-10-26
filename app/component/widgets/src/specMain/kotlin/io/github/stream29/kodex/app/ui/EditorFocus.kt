@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")

package io.github.stream29.kodex.app.ui

import androidx.compose.runtime.Composable

/**
 * Renderer-owned capability for one editor, never a ViewModel field or global focus registry.
 * Use on the renderer's interaction dispatcher, outside composition; returns false when
 * no enabled, placed editor is bound.
 */
public expect class UiEditorFocusHandle internal constructor() {
    public fun requestFocus(): Boolean
}

@Composable
public expect fun rememberUiEditorFocusHandle(): UiEditorFocusHandle

/** Scopes at most one editor leaf; other editors and dialogs must remain outside this provider. */
@Composable
public expect fun WithUiEditorFocus(handle: UiEditorFocusHandle, content: @Composable () -> Unit): Unit

/**
 * Binds a renderer leaf to the nearest [WithUiEditorFocus] until it leaves composition.
 * The callback admits only enabled, placed focus requests; one handle may bind only one leaf.
 */
@Composable
public expect fun BindUiEditorFocus(onRequest: () -> Boolean): Unit
