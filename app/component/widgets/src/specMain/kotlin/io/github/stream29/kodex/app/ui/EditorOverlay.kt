package io.github.stream29.kodex.app.ui

import androidx.compose.runtime.Composable

/**
 * Marks the content of a renderer overlay, including nested dialogs and popup menus.
 * Browser editors below it remain mounted but are hidden and cannot accept input or focus.
 * This is a renderer-local stacking boundary, not a ViewModel or application popup owner.
 */
@Composable
public expect fun UiEditorOverlay(content: @Composable () -> Unit): Unit


/** Renderer-surface overlay scope. It does not create or dispose any business child. */
@Composable
public expect fun WithUiEditorSurface(content: @Composable () -> Unit): Unit

/** True only for an editor below a mounted overlay in this exact renderer surface. */
@Composable
public expect fun isUiEditorObscured(): Boolean
